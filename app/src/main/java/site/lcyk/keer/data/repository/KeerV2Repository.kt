package site.lcyk.keer.data.repository

import android.content.Context
import com.skydoves.sandwich.ApiResponse
import com.skydoves.sandwich.StatusCode
import com.skydoves.sandwich.getOrNull
import com.skydoves.sandwich.mapSuccess
import com.skydoves.sandwich.onSuccess
import com.skydoves.sandwich.retrofit.statusCode
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import site.lcyk.keer.data.api.KeerV2Api
import site.lcyk.keer.data.api.AddFriendRequest
import site.lcyk.keer.data.api.AddGroupMemberRequest
import site.lcyk.keer.data.api.CreateDirectGroupRequest
import site.lcyk.keer.data.api.CreateGroupMessageRequest
import site.lcyk.keer.data.api.CreateGroupRequest
import site.lcyk.keer.data.api.KeerV2Group
import site.lcyk.keer.data.api.KeerV2GroupMessage
import site.lcyk.keer.data.api.KeerV2MemoColumnConfig
import site.lcyk.keer.data.api.KeerV2ExploreDrawerEntryConfig
import site.lcyk.keer.data.api.KeerV2CreateMemoRequest
import site.lcyk.keer.data.api.KeerV2Memo
import site.lcyk.keer.data.api.KeerV2PayloadEnvelope
import site.lcyk.keer.data.api.KeerV2Resource
import site.lcyk.keer.data.api.KeerV2State
import site.lcyk.keer.data.api.KeerV2User
import site.lcyk.keer.data.api.KeerV2UserSettingGeneralSetting
import site.lcyk.keer.data.api.MarkGroupReadRequest
import site.lcyk.keer.data.api.MemosVisibility
import site.lcyk.keer.data.api.SyncPullRequest
import site.lcyk.keer.data.api.UpdateUserSettingBody
import site.lcyk.keer.data.api.UpdateUserSettingRequest
import site.lcyk.keer.data.api.UpdateGroupMessageRequest
import site.lcyk.keer.data.api.UpdateMemoRequest
import site.lcyk.keer.data.api.UpdateGroupRequest
import site.lcyk.keer.data.constant.KeerException
import site.lcyk.keer.data.model.Account
import site.lcyk.keer.data.model.GroupMember
import site.lcyk.keer.data.model.Memo
import site.lcyk.keer.data.model.MemoColumnConfig
import site.lcyk.keer.data.model.MemoEditGesture
import site.lcyk.keer.data.model.MemoGroup
import site.lcyk.keer.data.model.MemoGroupType
import site.lcyk.keer.data.model.MemoVisibility
import site.lcyk.keer.data.model.ExploreDrawerEntryConfig
import site.lcyk.keer.data.model.Resource
import site.lcyk.keer.data.model.StorageCleanupSummary
import site.lcyk.keer.data.model.User
import site.lcyk.keer.data.model.UserGeneralSettings
import site.lcyk.keer.data.security.AccountKeyManager
import site.lcyk.keer.data.security.AttachmentEncryptionManager
import site.lcyk.keer.data.security.AttachmentEncryptionMetadata
import site.lcyk.keer.data.security.E2eeKeyEnvelope
import site.lcyk.keer.data.security.EncryptedBlobMetadata
import site.lcyk.keer.data.security.EncryptedBlobVariant
import site.lcyk.keer.data.security.PreparedEncryptedThumbnail
import site.lcyk.keer.data.security.MemoContentCodec
import site.lcyk.keer.data.security.WrappedContentKey
import site.lcyk.keer.util.MEMO_QUOTE_STATUS_RESOLVED
import site.lcyk.keer.util.MEMO_QUOTE_STATUS_UNAVAILABLE
import site.lcyk.keer.util.buildMemoQuotePreviewText
import site.lcyk.keer.util.extractCollaboratorIds
import site.lcyk.keer.util.parseMemoQuoteDescriptor
import site.lcyk.keer.util.resolveAvatarUrl
import site.lcyk.keer.util.resolveQuoteSourceKind
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.time.Instant
import kotlin.math.min

private const val PAGE_SIZE = 200
private const val uploadChunkSizeBytes = 8L * 1024L * 1024L
private const val maxChunkRetryCount = 6
private const val retryDelayMillis = 500L
private const val uploadCheckpointTTLMillis = 24L * 60L * 60L * 1000L
private const val uploadCheckpointCleanupIntervalMillis = 15L * 60L * 1000L
private const val uploadCheckpointMaxEntries = 256
private const val maxSyncedUserIDs = 180
private const val maxUserBatchRequestSize = 200
private const val userFallbackRequestChunkSize = 12
private const val syncedUserIDPersistDebounceMillis = 15_000L
private const val encryptedAttachmentFilename = "blob.bin"
private const val encryptedThumbnailFilename = "blob.thumb.bin"
private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
private val uploadChunkMediaType = "application/offset+octet-stream".toMediaType()
private val uploadJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    explicitNulls = false
}

class KeerV2Repository(
    private val memosApi: KeerV2Api,
    private val account: Account.KeerV2,
    private val okHttpClient: OkHttpClient,
    appContext: Context,
    private val readUserSyncAnchor: suspend () -> Instant? = { null },
    private val writeUserSyncAnchor: suspend (Instant) -> Unit = {},
    private val readSyncedUserIDs: suspend () -> List<String> = { emptyList() },
    private val writeSyncedUserIDs: suspend (List<String>) -> Unit = {},
    private val attachmentEncryptionManager: AttachmentEncryptionManager,
    private val accountKeyManager: AccountKeyManager,
    private val memoContentCodec: MemoContentCodec
): RemoteRepository() {
    private val uploadCheckpointStore = ResumableUploadCheckpointStore(
        File(
            File(appContext.filesDir, "resumable_uploads"),
            "${sha256Hex(account.accountKey())}.json"
        )
    )
    @Volatile
    private var lastCheckpointCleanupAtMillis: Long = 0L
    private val userProfileCacheMutex = Mutex()
    private val userProfileCache = mutableMapOf<String, KeerV2User>()
    private val userSyncStateMutex = Mutex()
    private val trackedUserIDs = mutableSetOf<String>()
    @Volatile
    private var userSyncAnchorCache: Instant? = null
    @Volatile
    private var userSyncStateLoaded: Boolean = false
    @Volatile
    private var syncedUserIDsDirty: Boolean = false
    @Volatile
    private var lastSyncedUserIDsPersistAtMillis: Long = 0L

    private fun convertResource(resource: KeerV2Resource): Resource {
        val descriptor = decodeResourceDescriptor(
            ciphertext = resource.descriptorCiphertext,
            envelope = resource.descriptorEnvelope,
        )
        val encryptionMetadata = buildLocalAttachmentEncryptionMetadata(
            resource = resource,
            descriptor = descriptor,
        )
        return Resource(
            remoteId = requireNotNull(resource.name),
            date = resource.createTime ?: Instant.now(),
            filename = descriptor?.filename ?: resource.filename ?: "",
            uri = resource.uri(account.info.host).toString(),
            mimeType = descriptor?.originalMimeType
                ?: AttachmentEncryptionManager.resolveOriginalMimeType(encryptionMetadata, resource.type),
            encryptionMetadata = encryptionMetadata,
            thumbnailUri = resource.thumbnailUri(account.info.host)?.toString()
        )
    }

    private fun convertMemo(memo: KeerV2Memo): Memo {
        val payload = decodeMemoPayload(
            encryptedPayload = memo.encryptedPayload.orEmpty(),
            payloadEnvelope = memo.payloadEnvelope,
        )
        val quoteDescriptor = memo.quote?.let { quote ->
            val sourceKind = resolveQuoteSourceKind(quote.sourceKind)
            val source = quote.source.trim().ifEmpty { null }
            if (sourceKind != null && source != null) {
                sourceKind.tagSegment to source
            } else {
                null
            }
        } ?: parseMemoQuoteDescriptor(payload.tags)?.let { descriptor ->
            descriptor.sourceKind.tagSegment to descriptor.source
        }
        val quotePreview = convertQuotePreview(memo)
        return Memo(
            remoteId = memo.name,
            content = payload.content,
            date = memo.createTime ?: memo.updateTime ?: Instant.now(),
            pinned = memo.pinned ?: false,
            visibility = memo.visibility?.toMemoVisibility() ?: MemoVisibility.PRIVATE,
            resources = memo.attachments?.map { convertResource(it) } ?: emptyList(),
            tags = payload.tags,
            latitude = payload.latitude,
            longitude = payload.longitude,
            archived = memo.state == KeerV2State.ARCHIVED,
            updatedAt = memo.updateTime,
            quoteSourceKind = quoteDescriptor?.first,
            quoteSource = quoteDescriptor?.second,
            quoteStatus = quotePreview?.status,
            quoteContentPreview = quotePreview?.contentPreview,
            quoteDate = quotePreview?.date,
            quoteHasAttachments = quotePreview?.hasAttachments ?: false,
        )
    }

    private fun convertGroup(group: KeerV2Group): MemoGroup {
        val members = group.members.map { member ->
            GroupMember(
                userId = member.name,
                userName = member.username
            )
        }
        val groupType = group.type.toMemoGroupType()
        val currentUserId = account.info.id.toString()
        val directPeer = members.firstOrNull { member ->
            getId(member.userId) != currentUserId
        }
        val resolvedName = if (groupType == MemoGroupType.DIRECT) {
            directPeer?.userName?.ifBlank { null }
                ?: group.groupName.ifBlank { directPeer?.userId?.let(::getId) ?: getId(group.creator) }
        } else {
            group.groupName
        }
        val creatorName = members.firstOrNull { it.userId == group.creator }?.userName
            ?: getId(group.creator)
        return MemoGroup(
            id = getId(group.name),
            name = resolvedName,
            description = if (groupType == MemoGroupType.DIRECT) "" else group.description.orEmpty(),
            creatorId = group.creator,
            creatorName = creatorName,
            type = groupType,
            members = members,
            hasUnreadMessages = group.hasUnread,
            createdAtEpochMillis = group.createTime?.toEpochMilli() ?: System.currentTimeMillis(),
            updatedAtEpochMillis = group.updateTime?.toEpochMilli()
                ?: group.createTime?.toEpochMilli()
                ?: System.currentTimeMillis(),
        )
    }

    private fun convertUser(user: KeerV2User): User {
        return User(
            identifier = getId(user.name),
            name = user.username,
            startDate = user.createTime ?: Instant.now(),
            avatarUrl = resolveAvatarUrl(account.info.host, user.avatarUrl.orEmpty()),
            role = user.role.toRoleName(),
        )
    }

    private fun convertGroupMessage(
        groupMessage: KeerV2GroupMessage,
        userMap: Map<String, site.lcyk.keer.data.api.KeerV2User>
    ): Memo {
        val creatorId = groupMessage.creator
        val creator = (userMap[creatorId] ?: userMap[getId(creatorId)])?.let { user ->
            User(
                identifier = user.name,
                name = user.username,
                startDate = user.createTime ?: Instant.now(),
                avatarUrl = resolveAvatarUrl(account.info.host, user.avatarUrl.orEmpty()),
                role = user.role.toRoleName(),
            )
        } ?: User(
            identifier = creatorId,
            name = getId(creatorId),
            startDate = Instant.now()
        )

        val payload = decodeMemoPayload(
            encryptedPayload = groupMessage.encryptedPayload.orEmpty(),
            payloadEnvelope = groupMessage.payloadEnvelope,
        )
        val quoteDescriptor = groupMessage.quote?.let { quote ->
            val sourceKind = resolveQuoteSourceKind(quote.sourceKind)
            val source = quote.source.trim().ifEmpty { null }
            if (sourceKind != null && source != null) {
                sourceKind.tagSegment to source
            } else {
                null
            }
        } ?: parseMemoQuoteDescriptor(payload.tags)?.let { descriptor ->
            descriptor.sourceKind.tagSegment to descriptor.source
        }
        val quotePreview = groupMessage.quote?.let { quote ->
            val quotedMemo = quote.memo ?: return@let QuotePreviewSnapshot(
                status = MEMO_QUOTE_STATUS_UNAVAILABLE,
                contentPreview = null,
                date = null,
                hasAttachments = false,
            )
            val hasAttachments = quotedMemo.attachments?.isNotEmpty() == true
            val quotePayload = decodeMemoPayload(
                encryptedPayload = quotedMemo.encryptedPayload.orEmpty(),
                payloadEnvelope = quotedMemo.payloadEnvelope,
            )
            val quoteContent = quotePayload.content
            val resolvedStatus = if (quoteContent == encryptedContentUnavailablePlaceholder && !hasAttachments) {
                MEMO_QUOTE_STATUS_UNAVAILABLE
            } else {
                MEMO_QUOTE_STATUS_RESOLVED
            }
            QuotePreviewSnapshot(
                status = resolvedStatus,
                contentPreview = if (resolvedStatus == MEMO_QUOTE_STATUS_RESOLVED) {
                    buildMemoQuotePreviewText(quoteContent)
                } else {
                    null
                },
                date = quotedMemo.createTime ?: quotedMemo.updateTime,
                hasAttachments = hasAttachments,
            )
        }
        return Memo(
            remoteId = groupMessage.name,
            content = payload.content,
            date = groupMessage.createTime ?: groupMessage.updateTime ?: Instant.now(),
            pinned = false,
            visibility = MemoVisibility.PROTECTED,
            resources = groupMessage.attachments?.map { convertResource(it) } ?: emptyList(),
            tags = payload.tags,
            creator = creator,
            archived = false,
            updatedAt = groupMessage.updateTime,
            quoteSourceKind = quoteDescriptor?.first,
            quoteSource = quoteDescriptor?.second,
            quoteStatus = quotePreview?.status,
            quoteContentPreview = quotePreview?.contentPreview,
            quoteDate = quotePreview?.date,
            quoteHasAttachments = quotePreview?.hasAttachments ?: false,
        )
    }

    private suspend fun listMemosByState(state: KeerV2State): ApiResponse<List<Memo>> {
        var nextPageToken = ""
        val memos = arrayListOf<Memo>()

        do {
            val resp = memosApi.listMemos(PAGE_SIZE, nextPageToken, state, null)
                .onSuccess { nextPageToken = data.nextPageToken.orEmpty() }
                .mapSuccess { this.memos.map { convertMemo(it) } }
            if (resp is ApiResponse.Success) {
                memos.addAll(resp.data)
            } else {
                return resp
            }
        } while (nextPageToken.isNotEmpty())
        return ApiResponse.Success(memos)
    }

    private fun getId(identifier: String): String {
        return identifier.substringBefore('|').substringAfterLast('/')
    }

    private fun getName(identifier: String): String {
        return identifier.substringBefore('|')
    }

    private fun normalizeUserID(rawUserID: String): String {
        return getId(rawUserID).trim()
    }

    private fun pruneTrackedUserIDsLocked() {
        val currentUserID = account.info.id.toString()
        trackedUserIDs.removeAll { userID -> userID.isBlank() }
        trackedUserIDs.add(currentUserID)

        if (trackedUserIDs.size <= maxSyncedUserIDs) {
            return
        }

        val iterator = trackedUserIDs.iterator()
        while (trackedUserIDs.size > maxSyncedUserIDs && iterator.hasNext()) {
            val candidate = iterator.next()
            if (candidate == currentUserID) {
                continue
            }
            iterator.remove()
        }

        if (!trackedUserIDs.contains(currentUserID)) {
            if (trackedUserIDs.size >= maxSyncedUserIDs) {
                val oldest = trackedUserIDs.firstOrNull { userID -> userID != currentUserID }
                if (oldest != null) {
                    trackedUserIDs.remove(oldest)
                }
            }
            trackedUserIDs.add(currentUserID)
        }
    }

    private suspend fun ensureUserSyncStateLoaded() {
        if (userSyncStateLoaded) {
            return
        }
        userSyncStateMutex.withLock {
            if (userSyncStateLoaded) {
                return@withLock
            }
            userSyncAnchorCache = readUserSyncAnchor()
            trackedUserIDs.clear()
            trackedUserIDs += readSyncedUserIDs()
                .asSequence()
                .map(::normalizeUserID)
                .filter { userID -> userID.isNotEmpty() }
                .distinct()
                .toList()
            trackedUserIDs += account.info.id.toString()
            pruneTrackedUserIDsLocked()
            syncedUserIDsDirty = false
            lastSyncedUserIDsPersistAtMillis = System.currentTimeMillis()
            userSyncStateLoaded = true
        }
    }

    private suspend fun trackUserIDs(userIDs: Collection<String>) {
        if (userIDs.isEmpty()) {
            return
        }
        ensureUserSyncStateLoaded()
        val normalizedIDs = userIDs
            .asSequence()
            .map(::normalizeUserID)
            .filter { userID -> userID.isNotEmpty() }
            .toList()
        if (normalizedIDs.isEmpty()) {
            return
        }

        var changed = false
        userSyncStateMutex.withLock {
            val beforeSnapshot = trackedUserIDs.toList()
            trackedUserIDs += normalizedIDs
            trackedUserIDs += account.info.id.toString()
            pruneTrackedUserIDsLocked()
            if (trackedUserIDs.toList() != beforeSnapshot) {
                changed = true
                syncedUserIDsDirty = true
            }
        }
        if (changed) {
            persistTrackedUserIDsIfNeeded(force = false)
        }
    }

    private suspend fun persistTrackedUserIDsIfNeeded(force: Boolean) {
        ensureUserSyncStateLoaded()
        var snapshot: List<String>? = null
        val now = System.currentTimeMillis()
        userSyncStateMutex.withLock {
            if (!syncedUserIDsDirty) {
                return@withLock
            }
            val shouldPersistNow = force ||
                lastSyncedUserIDsPersistAtMillis <= 0L ||
                now - lastSyncedUserIDsPersistAtMillis >= syncedUserIDPersistDebounceMillis
            if (!shouldPersistNow) {
                return@withLock
            }
            snapshot = trackedUserIDs.toList()
            syncedUserIDsDirty = false
            lastSyncedUserIDsPersistAtMillis = now
        }

        val pendingSnapshot = snapshot ?: return
        try {
            writeSyncedUserIDs(pendingSnapshot)
        } catch (e: Throwable) {
            userSyncStateMutex.withLock {
                syncedUserIDsDirty = true
                lastSyncedUserIDsPersistAtMillis = 0L
            }
            throw e
        }
    }

    private suspend fun persistUserSyncAnchor(anchor: Instant) {
        val normalized = anchor
        userSyncStateMutex.withLock {
            userSyncAnchorCache = normalized
        }
        writeUserSyncAnchor(normalized)
    }

    private suspend fun cacheFetchedUsers(users: Collection<KeerV2User>) {
        if (users.isEmpty()) {
            return
        }
        userProfileCacheMutex.withLock {
            users.forEach(::cacheUserProfileLocked)
        }
    }

    private fun buildCurrentAccountUserProfile(): KeerV2User {
        val accountId = account.info.id.toString()
        val fallbackName = account.info.name.trim().ifBlank { accountId }
        return KeerV2User(
            name = "users/$accountId",
            username = fallbackName,
            avatarUrl = account.info.avatarUrl.ifBlank { null },
            createTime = if (account.info.startDateEpochSecond > 0L) {
                Instant.ofEpochSecond(account.info.startDateEpochSecond)
            } else {
                null
            },
            updateTime = Instant.now()
        )
    }

    private fun cacheUserProfileLocked(user: KeerV2User) {
        val keys = listOf(user.name, getId(user.name))
            .map { key -> key.trim() }
            .filter { key -> key.isNotEmpty() }
            .distinct()
        keys.forEach { key ->
            userProfileCache[key] = user
        }
    }

    private suspend fun getUsersByIDs(userIDs: Collection<String>): Map<String, KeerV2User> {
        val normalizedIDs = userIDs
            .asSequence()
            .map(::normalizeUserID)
            .map { userID -> userID.trim() }
            .filter { userID -> userID.isNotEmpty() }
            .distinct()
            .toList()
        if (normalizedIDs.isEmpty()) {
            return emptyMap()
        }
        trackUserIDs(normalizedIDs)

        val missingIDs = mutableListOf<String>()
        val usersByID = mutableMapOf<String, KeerV2User>()
        userProfileCacheMutex.withLock {
            normalizedIDs.forEach { userID ->
                val cached = userProfileCache[userID]
                if (cached != null) {
                    usersByID[userID] = cached
                } else {
                    missingIDs += userID
                }
            }
        }

        val missingNetworkIDs = missingIDs
            .filterNot { userID -> userID == account.info.id.toString() }
            .distinct()
            .toMutableList()

        val fetchedByID = mutableMapOf<String, KeerV2User?>()
        if (missingIDs.any { userID -> userID == account.info.id.toString() }) {
            fetchedByID[account.info.id.toString()] = buildCurrentAccountUserProfile()
        }

        if (missingNetworkIDs.isNotEmpty()) {
            val unresolvedIDs = linkedSetOf<String>()
            missingNetworkIDs.chunked(maxUserBatchRequestSize).forEach { chunk ->
                val batchResp = memosApi.getUsersBatch(chunk.joinToString(","))
                if (batchResp is ApiResponse.Success) {
                    batchResp.data.users.forEach { user ->
                        val userID = getId(user.name)
                        if (userID.isNotBlank()) {
                            fetchedByID[userID] = user
                        }
                    }
                    chunk.forEach { userID ->
                        if (!fetchedByID.containsKey(userID)) {
                            unresolvedIDs += userID
                        }
                    }
                } else {
                    unresolvedIDs += chunk
                }
            }

            if (unresolvedIDs.isNotEmpty()) {
                unresolvedIDs
                    .chunked(userFallbackRequestChunkSize)
                    .forEach { chunk ->
                        val fallback = coroutineScope {
                            chunk.map { userID ->
                                async { userID to memosApi.getUser(userID).getOrNull() }
                            }.awaitAll()
                        }
                        fallback.forEach { (userID, user) ->
                            fetchedByID[userID] = user
                        }
                    }
            }
        }

        return userProfileCacheMutex.withLock {
            fetchedByID.forEach { (userID, user) ->
                if (user == null) {
                    return@forEach
                }
                cacheUserProfileLocked(user)
                usersByID[userID] = userProfileCache[userID] ?: user
            }
            normalizedIDs.forEach { userID ->
                val cached = userProfileCache[userID]
                if (cached != null) {
                    usersByID.putIfAbsent(userID, cached)
                }
            }

            usersByID.values
                .flatMap { user ->
                    listOf(user.name, getId(user.name))
                        .map { key -> key.trim() }
                        .filter { key -> key.isNotEmpty() }
                        .distinct()
                        .map { key -> key to user }
                }
                .toMap()
        }
    }

    override suspend fun listMemos(): ApiResponse<List<Memo>> {
        return listMemosByState(KeerV2State.NORMAL)
    }

    override suspend fun pullSync(
        cursor: String,
        domains: Set<SyncPullDomain>,
        groupScopes: List<String>,
        limit: Int,
    ): ApiResponse<SyncPullResult> {
        val normalizedDomains = if (domains.isEmpty()) {
            SyncPullDomain.entries.toSet()
        } else {
            domains
        }
        val normalizedCursor = cursor.trim()
            .takeIf { value -> value.isNotEmpty() && value.all(Char::isDigit) && value.toLongOrNull() != null }
            ?: "0"
        val normalizedGroupScopes = groupScopes
            .asSequence()
            .map { scope -> scope.trim() }
            .filter { scope -> scope.isNotEmpty() }
            .distinct()
            .toList()
        val normalizedLimit = limit.coerceIn(1, 1000)

        val response = memosApi.pullSync(
            SyncPullRequest(
                cursor = normalizedCursor,
                domains = normalizedDomains.map { domain -> domain.name },
                groupScopes = normalizedGroupScopes,
                limit = normalizedLimit,
            )
        )
        if (response !is ApiResponse.Success) {
            return response.mapSuccess {
                SyncPullResult(
                    nextCursor = normalizedCursor,
                    hasMore = false,
                    patches = SyncPullPatches(),
                )
            }
        }

        val dto = response.data
        val groupMessageCreators = dto.patches.groupMessages.groups
            .flatMap { groupPatch -> groupPatch.messages.map { message -> message.creator } }
            .distinct()
        val userMap = getUsersByIDs(groupMessageCreators)

        val result = SyncPullResult(
            nextCursor = dto.nextCursor.trim().ifEmpty { normalizedCursor },
            hasMore = dto.hasMore,
            patches = SyncPullPatches(
                memos = SyncPullMemoPatch(
                    upserts = dto.patches.memos.upserts.map { memo -> convertMemo(memo) },
                    deletes = dto.patches.memos.deletes
                        .asSequence()
                        .map(::getName)
                        .map(::getId)
                        .filter { remoteId -> remoteId.isNotBlank() }
                        .distinct()
                        .toList(),
                ),
                users = SyncPullUserPatch(
                    upserts = dto.patches.users.upserts.map { user -> convertUser(user) },
                ),
                groups = SyncPullGroupPatch(
                    directory = dto.patches.groups.directory.map { group -> convertGroup(group) },
                ),
                groupMessages = SyncPullGroupMessagesPatch(
                    groups = dto.patches.groupMessages.groups.map { groupPatch ->
                        SyncPullGroupMessagesGroupPatch(
                            groupId = getId(groupPatch.group),
                            fullReplace = groupPatch.fullReplace,
                            hasUnread = groupPatch.hasUnread,
                            messages = groupPatch.messages.map { message ->
                                convertGroupMessage(message, userMap)
                            },
                            tags = normalizeTags(groupPatch.tags),
                        )
                    }
                ),
                settings = SyncPullSettingsPatch(
                    generalSettings = dto.patches.settings.generalSetting?.toUserGeneralSettings()
                ),
            ),
        )
        return ApiResponse.Success(result)
    }

    override suspend fun listArchivedMemos(): ApiResponse<List<Memo>> {
        return listMemosByState(KeerV2State.ARCHIVED)
    }

    override suspend fun listMemoChanges(since: Instant): ApiResponse<MemoChanges> {
        return memosApi.listMemoChanges(
            since = since.toString(),
            state = null,
            filter = null
        ).mapSuccess {
            MemoChanges(
                memos = memos.map { memo -> convertMemo(memo) },
                deletedMemoRemoteIds = deletedMemoNames
                    .asSequence()
                    .map { name -> getName(name) }
                    .filter { name -> name.isNotBlank() }
                    .distinct()
                    .toList(),
                syncAnchor = syncAnchor ?: Instant.now()
            )
        }
    }

    override suspend fun createMemo(
        content: String,
        visibility: MemoVisibility,
        resourceRemoteIds: List<String>,
        tags: List<String>?,
        createdAt: Instant?,
        latitude: Double?,
        longitude: Double?
    ): ApiResponse<Memo> {
        val encryptedPayload = buildEncryptedMemoPayload(
            content = content,
            tags = tags.orEmpty(),
            latitude = latitude,
            longitude = longitude,
        )
        val attachmentScope = memoAttachmentEncryptionScope(tags.orEmpty())
        val resp = memosApi.createMemo(
            KeerV2CreateMemoRequest(
                encryptedPayload = encryptedPayload.first,
                payloadEnvelope = encryptedPayload.second,
                visibility = MemosVisibility.fromMemoVisibility(visibility),
                attachments = buildAttachmentRequestResources(resourceRemoteIds, attachmentScope),
                tags = null,
                latitude = null,
                longitude = null,
                createTime = createdAt
            )
        )
            .mapSuccess { convertMemo(this) }
        return resp
    }

    override suspend fun updateMemo(
        remoteId: String,
        content: String?,
        resourceRemoteIds: List<String>?,
        visibility: MemoVisibility?,
        tags: List<String>?,
        pinned: Boolean?,
        archived: Boolean?
    ): ApiResponse<Memo> {
        val encryptedPayload = if (content != null || tags != null || archived != null) {
            buildEncryptedMemoPayload(
                content = content.orEmpty(),
                tags = tags.orEmpty(),
                latitude = null,
                longitude = null,
            )
        } else {
            null
        }
        val attachmentScope = memoAttachmentEncryptionScope(tags.orEmpty())
        val resp = memosApi.updateMemo(getId(remoteId), UpdateMemoRequest(
            encryptedPayload = encryptedPayload?.first,
            payloadEnvelope = encryptedPayload?.second,
            visibility = visibility?.let { MemosVisibility.fromMemoVisibility(it) },
            pinned = pinned,
            state = archived?.let { isArchived -> if (isArchived) KeerV2State.ARCHIVED else KeerV2State.NORMAL },
            tags = null,
            updateTime = Instant.now(),
            attachments = resourceRemoteIds?.let { buildAttachmentRequestResources(it, attachmentScope) }
        )).mapSuccess { convertMemo(this) }
        return resp
    }

    override suspend fun deleteMemo(remoteId: String): ApiResponse<Unit> {
        return memosApi.deleteMemo(getId(remoteId))
    }

    override suspend fun listFriends(): ApiResponse<List<User>> {
        return memosApi.listFriends().mapSuccess {
            users.map(::convertUser)
        }
    }

    override suspend fun addFriend(userIdentifier: String): ApiResponse<User> {
        return memosApi.addFriend(
            AddFriendRequest(user = userIdentifier.trim())
        ).mapSuccess(::convertUser)
    }

    override suspend fun removeFriend(userIdentifier: String): ApiResponse<Unit> {
        return memosApi.removeFriend(userIdentifier.trim())
    }

    override suspend fun changePassword(
        currentPassword: String,
        newPassword: String
    ): ApiResponse<Unit> {
        return accountKeyManager.changePassword(
            account = account,
            api = memosApi,
            currentPassword = currentPassword,
            newPassword = newPassword,
        )
    }

    override suspend fun getCurrentUserGeneralSettings(): ApiResponse<UserGeneralSettings> {
        return memosApi.getUserSetting(account.info.id.toString()).mapSuccess {
            generalSetting.toUserGeneralSettings()
        }
    }

    override suspend fun updateCurrentUserGeneralSettings(
        settings: UserGeneralSettings
    ): ApiResponse<UserGeneralSettings> {
        return memosApi.updateUserSetting(
            account.info.id.toString(),
            UpdateUserSettingRequest(
                generalSetting = UpdateUserSettingBody(
                    memoVisibility = MemosVisibility.fromMemoVisibility(settings.memoVisibility),
                    memoEditGesture = settings.memoEditGesture.name,
                    memoColumns = settings.memoColumns.map { column ->
                        KeerV2MemoColumnConfig(
                            id = column.id,
                            name = column.name,
                            requiredTags = column.requiredTags,
                            visibleInDrawer = column.visibleInDrawer,
                            pinnedMemoRemoteIds = column.pinnedMemoRemoteIds,
                        )
                    },
                    exploreDrawerEntries = settings.exploreDrawerEntries.map { entry ->
                        KeerV2ExploreDrawerEntryConfig(
                            entryId = entry.entryId,
                            visibleInExplore = entry.visibleInExplore,
                        )
                    },
                )
            )
        ).mapSuccess {
            generalSetting.toUserGeneralSettings()
        }
    }

    override suspend fun cleanupOrphanFiles(): ApiResponse<StorageCleanupSummary> {
        return memosApi.cleanupOrphanFiles().mapSuccess {
            StorageCleanupSummary(
                scannedKeys = cleanup.scannedKeys,
                deletedKeys = cleanup.deletedKeys,
                failedKeys = cleanup.failedKeys,
            )
        }
    }

    override suspend fun listGroups(): ApiResponse<List<MemoGroup>> {
        return memosApi.listGroups().mapSuccess {
            groups.map { group -> convertGroup(group) }
        }
    }

    override suspend fun createGroup(name: String, description: String): ApiResponse<MemoGroup> {
        return memosApi.createGroup(
            CreateGroupRequest(
                name = name.trim(),
                description = description.trim()
            )
        ).mapSuccess { convertGroup(this) }
    }

    override suspend fun createDirectGroup(userIdentifier: String): ApiResponse<MemoGroup> {
        return memosApi.createDirectGroup(
            CreateDirectGroupRequest(user = userIdentifier.trim())
        ).mapSuccess { convertGroup(this) }
    }

    override suspend fun addGroupMember(groupId: String, userIdentifier: String): ApiResponse<MemoGroup> {
        return memosApi.addGroupMember(
            getId(groupId),
            AddGroupMemberRequest(user = userIdentifier.trim())
        ).mapSuccess { convertGroup(this) }
    }

    override suspend fun updateGroup(
        groupId: String,
        name: String?,
        description: String?
    ): ApiResponse<MemoGroup> {
        return memosApi.updateGroup(
            getId(groupId),
            UpdateGroupRequest(
                name = name?.trim(),
                description = description?.trim()
            )
        ).mapSuccess { convertGroup(this) }
    }

    override suspend fun deleteOrLeaveGroup(groupId: String): ApiResponse<Unit> {
        return memosApi.deleteOrLeaveGroup(getId(groupId))
    }

    override suspend fun listGroupMessages(
        groupId: String,
        pageSize: Int,
        pageToken: String?
    ): ApiResponse<Pair<List<Memo>, String?>> {
        accountKeyManager.loadCurrentGroupKey(account, memosApi, groupId)
        val response = memosApi.listGroupMessages(getId(groupId), pageSize, pageToken)
        if (response !is ApiResponse.Success) {
            return response.mapSuccess { emptyList<Memo>() to null }
        }

        val creatorIDs = response.data.messages
            .map { message -> message.creator }
            .distinct()
        val userMap = getUsersByIDs(creatorIDs)

        return response.mapSuccess {
            messages.map { message ->
                convertGroupMessage(message, userMap)
            } to nextPageToken?.ifEmpty { null }
        }
    }

    override suspend fun createGroupMessage(
        groupId: String,
        content: String,
        tags: List<String>,
        resourceRemoteIds: List<String>
    ): ApiResponse<Memo> {
        val group = loadGroupById(groupId) ?: return ApiResponse.exception(
            IllegalStateException("Group not found: $groupId")
        )
        val encryptedPayload = buildEncryptedGroupPayload(
            group = group,
            content = content,
            tags = tags,
        )
        val response = memosApi.createGroupMessage(
            getId(groupId),
            CreateGroupMessageRequest(
                encryptedPayload = encryptedPayload.first,
                payloadEnvelope = encryptedPayload.second,
                tags = null,
                attachments = buildAttachmentRequestResources(
                    resourceRemoteIds,
                    ResourceEncryptionScope.Group(groupId),
                ),
            )
        )
        if (response !is ApiResponse.Success) {
            return response.mapSuccess { convertGroupMessage(this, emptyMap()) }
        }

        val userMap = getUsersByIDs(listOf(response.data.creator))
        return ApiResponse.Success(convertGroupMessage(response.data, userMap))
    }

    override suspend fun updateGroupMessage(
        groupId: String,
        messageRemoteId: String,
        content: String?,
        tags: List<String>?,
        resourceRemoteIds: List<String>?
    ): ApiResponse<Memo> {
        val group = loadGroupById(groupId) ?: return ApiResponse.exception(
            IllegalStateException("Group not found: $groupId")
        )
        val encryptedPayload = if (content != null || tags != null) {
            buildEncryptedGroupPayload(
                group = group,
                content = content.orEmpty(),
                tags = tags.orEmpty(),
            )
        } else {
            null
        }
        val request = UpdateGroupMessageRequest(
            encryptedPayload = encryptedPayload?.first,
            payloadEnvelope = encryptedPayload?.second,
            tags = null,
            attachments = resourceRemoteIds?.let {
                buildAttachmentRequestResources(it, ResourceEncryptionScope.Group(groupId))
            },
        )
        val response = memosApi.updateGroupMessage(
            getId(groupId),
            getId(messageRemoteId),
            request
        )
        if (response !is ApiResponse.Success) {
            return response.mapSuccess { convertGroupMessage(this, emptyMap()) }
        }

        val userMap = getUsersByIDs(listOf(response.data.creator))
        return ApiResponse.Success(convertGroupMessage(response.data, userMap))
    }

    override suspend fun deleteGroupMessage(groupId: String, messageRemoteId: String): ApiResponse<Unit> {
        return memosApi.deleteGroupMessage(getId(groupId), getId(messageRemoteId))
    }

    override suspend fun markGroupRead(groupId: String, lastReadMessageRemoteId: String?): ApiResponse<Unit> {
        val normalizedMessageName = lastReadMessageRemoteId
            ?.trim()
            ?.takeIf { value -> value.isNotEmpty() }
            ?.let(::getName)
        return memosApi.markGroupRead(
            getId(groupId),
            MarkGroupReadRequest(lastReadMessage = normalizedMessageName)
        )
    }

    override suspend fun syncKnownUsers(): ApiResponse<Unit> {
        ensureUserSyncStateLoaded()

        val syncSnapshot = userSyncStateMutex.withLock {
            trackedUserIDs += account.info.id.toString()
            Pair(
                trackedUserIDs.toList(),
                userSyncAnchorCache ?: Instant.EPOCH
            )
        }
        val tracked = syncSnapshot.first
            .asSequence()
            .map(::normalizeUserID)
            .filter { userID -> userID.isNotEmpty() }
            .distinct()
            .toList()
        if (tracked.isEmpty()) {
            return ApiResponse.Success(Unit)
        }

        val now = Instant.now()
        val changesResponse = memosApi.listUserChanges(
            since = syncSnapshot.second.toString(),
            ids = tracked.joinToString(",")
        )

        when (changesResponse) {
            is ApiResponse.Success -> {
                cacheFetchedUsers(changesResponse.data.users)
                trackUserIDs(changesResponse.data.users.map { user -> user.name })
                val nextAnchor = changesResponse.data.syncAnchor ?: now
                persistUserSyncAnchor(nextAnchor)
                persistTrackedUserIDsIfNeeded(force = true)
                return ApiResponse.Success(Unit)
            }
            is ApiResponse.Failure.Error -> {
                val statusCode = changesResponse.statusCode
                val shouldFallback = statusCode == StatusCode.NotFound ||
                    statusCode.code == 405 ||
                    statusCode == StatusCode.BadRequest
                if (!shouldFallback) {
                    return ApiResponse.exception(
                        IllegalStateException("sync users failed: HTTP ${statusCode.code}")
                    )
                }
            }
            is ApiResponse.Failure.Exception -> {
                return ApiResponse.exception(changesResponse.throwable)
            }
        }

        val batchResponse = memosApi.getUsersBatch(tracked.joinToString(","))
        when (batchResponse) {
            is ApiResponse.Success -> {
                cacheFetchedUsers(batchResponse.data.users)
                trackUserIDs(batchResponse.data.users.map { user -> user.name })
                persistUserSyncAnchor(now)
                persistTrackedUserIDsIfNeeded(force = true)
                return ApiResponse.Success(Unit)
            }
            is ApiResponse.Failure.Error -> {
                return ApiResponse.exception(
                    IllegalStateException("sync users fallback failed: HTTP ${batchResponse.statusCode.code}")
                )
            }
            is ApiResponse.Failure.Exception -> {
                return ApiResponse.exception(batchResponse.throwable)
            }
        }
    }

    override suspend fun listGroupTags(groupId: String): ApiResponse<List<String>> {
        return collectGroupTagsFromMessages(getId(groupId))
    }

    override suspend fun addGroupTag(groupId: String, tag: String): ApiResponse<List<String>> {
        val normalizedTag = tag.trim()
        if (normalizedTag.isEmpty()) {
            return ApiResponse.Success(emptyList())
        }
        val existing = when (val current = collectGroupTagsFromMessages(getId(groupId))) {
            is ApiResponse.Success -> current.data
            else -> emptyList()
        }
        return ApiResponse.Success(normalizeTags(existing + normalizedTag))
    }

    override suspend fun listTags(): ApiResponse<List<String>> {
        val normal = listMemosByState(KeerV2State.NORMAL)
        if (normal !is ApiResponse.Success) {
            return normal.mapSuccess { emptyList() }
        }
        val archived = listMemosByState(KeerV2State.ARCHIVED)
        if (archived !is ApiResponse.Success) {
            return archived.mapSuccess { emptyList() }
        }
        return ApiResponse.Success(
            normalizeTags(
                normal.data
                    .asSequence()
                    .plus(archived.data)
                    .flatMap { memo -> memo.tags.asSequence() }
                    .toList()
            )
        )
    }

    override suspend fun listResources(): ApiResponse<List<Resource>> {
        return memosApi.listResources().mapSuccess { this.attachments.map { convertResource(it) } }
    }

    override suspend fun createResource(
        filename: String,
        type: MediaType?,
        file: File,
        memoRemoteId: String?,
        encryptionScope: ResourceEncryptionScope,
        thumbnail: ResourceUploadThumbnail?,
        onProgress: (uploadedBytes: Long, totalBytes: Long) -> Unit
    ): ApiResponse<Resource> = withContext(Dispatchers.IO) {
        val originalTotalBytes = file.length()
        if (originalTotalBytes <= 0L) {
            return@withContext failure("prepare upload", "file is empty")
        }

        val baseUrl = account.info.host.trimEnd('/')
        maybeCleanupStaleUploadCheckpoints(baseUrl)
        val checkpointKey = buildUploadCheckpointKey(
            filename = filename,
            type = type,
            file = file,
            memoRemoteId = memoRemoteId,
            thumbnail = thumbnail
        )
        val encryptedUpload = runCatching {
            attachmentEncryptionManager.prepareEncryptedUpload(
                accountKey = account.accountKey(),
                checkpointKey = checkpointKey,
                sourceFile = file,
                originalMimeType = type?.toString(),
                thumbnail = thumbnail?.let {
                    PreparedEncryptedThumbnail(
                        filename = it.filename,
                        type = it.type,
                        content = it.content,
                    )
                },
            )
        }.getOrElse { throwable ->
            return@withContext failure("prepare upload", "encrypt attachment failed", throwable)
        }
        val uploadFile = encryptedUpload.file
        val uploadTypeValue = encryptedUpload.mimeType
        val uploadMediaType = runCatching { uploadTypeValue.toMediaType() }.getOrElse { throwable ->
            return@withContext failure("prepare upload", "invalid encrypted upload mime type", throwable)
        }
        val uploadThumbnail = encryptedUpload.thumbnail?.let {
            ResourceUploadThumbnail(
                filename = encryptedThumbnailFilename,
                type = it.type,
                content = it.content,
            )
        }
        val uploadEncryptionMetadata = AttachmentEncryptionManager.parseMetadata(encryptedUpload.encryptionMetadata)
            ?: return@withContext failure("prepare upload", "invalid encrypted attachment metadata")
        val uploadScope = ResourceEncryptionScope.Account
        val descriptorPayload = runCatching {
            buildEncryptedAttachmentDescriptor(
                filename = filename,
                originalMimeType = type?.toString(),
                thumbnailMimeType = thumbnail?.type,
                encryptionScope = uploadScope,
            )
        }.getOrElse { throwable ->
            return@withContext failure("prepare upload", "encrypt attachment descriptor failed", throwable)
        }
        val mainWrappedKeys = runCatching {
            val mainKey = attachmentEncryptionManager.unwrapVariantKey(
                accountKey = account.accountKey(),
                rawMetadata = encryptedUpload.encryptionMetadata,
                variant = EncryptedBlobVariant.MAIN,
            ) ?: error("missing main attachment key")
            resolveAttachmentWrappedKeys(uploadScope, mainKey)
        }.getOrElse { throwable ->
            return@withContext failure("prepare upload", "wrap main attachment key failed", throwable)
        }
        val wrappedMainBlob = uploadEncryptionMetadata.main.withWrappedKeys(mainWrappedKeys)
        val wrappedThumbnailBlob = uploadEncryptionMetadata.thumbnail?.let { _ ->
            runCatching {
                val thumbnailKey = attachmentEncryptionManager.unwrapVariantKey(
                    accountKey = account.accountKey(),
                    rawMetadata = encryptedUpload.encryptionMetadata,
                    variant = EncryptedBlobVariant.THUMBNAIL,
                ) ?: error("missing thumbnail attachment key")
                val thumbnailWrappedKeys = resolveAttachmentWrappedKeys(uploadScope, thumbnailKey)
                uploadEncryptionMetadata.thumbnail.withWrappedKeys(thumbnailWrappedKeys)
            }.getOrElse { throwable ->
                return@withContext failure("prepare upload", "wrap thumbnail attachment key failed", throwable)
            }
        }
        val blobEncryption = uploadJson.encodeToString(
            EncryptedBlobMetadata.serializer(),
            wrappedMainBlob,
        )
        val thumbnailBlobEncryption = wrappedThumbnailBlob?.let { thumb ->
            uploadJson.encodeToString(EncryptedBlobMetadata.serializer(), thumb)
        }
        val totalBytes = uploadFile.length()
        if (totalBytes <= 0L) {
            return@withContext failure("prepare upload", "encrypted attachment is empty")
        }

        var uploadId = ""
        var offset = 0L
        var useBackendChunkUpload = true
        var multipartPartSizeBytes = 0L

        val resumedSession = resolveExistingUploadSession(baseUrl, checkpointKey, totalBytes)
        if (resumedSession != null) {
            uploadId = resumedSession.uploadId
            offset = resumedSession.offset
            if (resumedSession.uploadMode.equals("DIRECT_MULTIPART", ignoreCase = true)) {
                useBackendChunkUpload = false
                multipartPartSizeBytes = (resumedSession.multipartPartSizeBytes
                    ?: uploadChunkSizeBytes).coerceAtLeast(1L)
            }
            onProgress(offset.coerceAtMost(totalBytes), totalBytes)
        }

        if (uploadId.isEmpty()) {
            val createRequestBody = runCatching {
                uploadJson.encodeToString(
                    ResumableUploadCreateRequest.serializer(),
                    ResumableUploadCreateRequest(
                        descriptorCiphertext = descriptorPayload.first,
                        descriptorEnvelope = descriptorPayload.second,
                        blobEncryption = blobEncryption,
                        thumbnailBlobEncryption = thumbnailBlobEncryption,
                        filename = encryptedAttachmentFilename,
                        type = uploadTypeValue,
                        size = totalBytes,
                        memo = memoRemoteId?.let { getName(it) },
                        thumbnail = uploadThumbnail?.let {
                            ResumableUploadThumbnailRequest(
                                filename = it.filename,
                                type = it.type,
                                content = it.content
                            )
                        }
                    )
                )
            }.getOrElse { throwable ->
                return@withContext failure("prepare upload", "serialize create payload failed", throwable)
            }

            val createURL = "$baseUrl/api/v1/attachments/uploads"
            val createRequest = runCatching {
                Request.Builder()
                    .url(createURL)
                    .post(createRequestBody.toRequestBody(jsonMediaType))
                    .build()
            }.getOrElse { throwable ->
                return@withContext failure("prepare upload", "build create request failed ($createURL)", throwable)
            }

            val createResponse = try {
                okHttpClient.newCall(createRequest).execute()
            } catch (e: Throwable) {
                return@withContext failure("create upload", "request execution failed ($createURL)", e)
            }

            var createFailure: ApiResponse.Failure.Exception? = null
            var session: ResumableUploadCreateResponse? = null
            createResponse.use { response ->
                if (!response.isSuccessful) {
                    createFailure = httpFailure("create upload", response)
                    return@use
                }
                val body = response.body.string()
                if (body.isBlank()) {
                    createFailure = failure("create upload", "response empty")
                    return@use
                }
                try {
                    session = uploadJson.decodeFromString(ResumableUploadCreateResponse.serializer(), body)
                } catch (e: Throwable) {
                    createFailure = failure("create upload", "decode response failed", e)
                }
            }
            val resolvedCreateFailure = createFailure
            if (resolvedCreateFailure != null) {
                return@withContext resolvedCreateFailure
            }
            val resolvedSession = session ?: return@withContext failure(
                "create upload",
                "missing upload session in response"
            )
            val resolvedUploadID = resolvedSession.uploadId.trim()
            if (resolvedUploadID.isEmpty()) {
                return@withContext failure("create upload", "missing upload id")
            }

            uploadId = resolvedUploadID
            val directUploadUrl = resolvedSession.directUploadUrl?.trim().orEmpty()
            val uploadMode = resolvedSession.uploadMode?.trim().orEmpty()

            if (directUploadUrl.isNotEmpty()) {
                useBackendChunkUpload = false
                val contentTypeValue = uploadTypeValue
                var attempt = 0
                while (true) {
                    val directBody = ChunkFileRequestBody(
                        file = uploadFile,
                        offset = 0L,
                        length = totalBytes,
                        mediaType = uploadMediaType,
                        onProgress = { sent, _ ->
                            onProgress(sent.coerceAtMost(totalBytes), totalBytes)
                        }
                    )
                    val directRequest = Request.Builder()
                        .url(directUploadUrl)
                        .put(directBody)
                        .header("Content-Type", contentTypeValue)
                        .build()

                    val directResponse = try {
                        okHttpClient.newCall(directRequest).execute()
                    } catch (e: Throwable) {
                        attempt += 1
                        if (attempt > maxChunkRetryCount) {
                            cancelUploadSession(baseUrl, uploadId)
                            return@withContext failure(
                                "direct upload",
                                "request execution failed after retries",
                                e
                            )
                        }
                        delay(retryDelayMillis)
                        continue
                    }

                    var shouldRetry = false
                    var fatalFailure: ApiResponse.Failure.Exception? = null
                    directResponse.use { response ->
                        if (response.isSuccessful) {
                            offset = totalBytes
                            onProgress(totalBytes, totalBytes)
                        } else if (response.code in 500..599 || response.code == 408 || response.code == 429) {
                            shouldRetry = true
                        } else {
                            fatalFailure = httpFailure("direct upload", response)
                        }
                    }
                    val resolvedFatalFailure = fatalFailure
                    if (resolvedFatalFailure != null) {
                        cancelUploadSession(baseUrl, uploadId)
                        return@withContext resolvedFatalFailure
                    }
                    if (!shouldRetry) {
                        break
                    }

                    attempt += 1
                    if (attempt > maxChunkRetryCount) {
                        cancelUploadSession(baseUrl, uploadId)
                        return@withContext failure("direct upload", "request failed after retries")
                    }
                    delay(retryDelayMillis)
                }
                uploadCheckpointStore.remove(checkpointKey)
            } else if (uploadMode.equals("DIRECT_MULTIPART", ignoreCase = true)) {
                useBackendChunkUpload = false
                multipartPartSizeBytes = (resolvedSession.multipartPartSize?.toLongOrNull()
                    ?: uploadChunkSizeBytes).coerceAtLeast(1L)
                offset = (resolvedSession.uploadedSize.toLongOrNull() ?: 0L).coerceIn(0L, totalBytes)
                uploadCheckpointStore.upsert(
                    checkpointKey,
                    UploadCheckpoint(
                        uploadId = resolvedUploadID,
                        totalBytes = totalBytes,
                        uploadedBytes = offset,
                        updatedAtMillis = System.currentTimeMillis(),
                        uploadMode = "DIRECT_MULTIPART",
                        multipartPartSizeBytes = multipartPartSizeBytes
                    )
                )
            } else {
                if (uploadMode.equals("DIRECT_PRESIGNED_PUT", ignoreCase = true)) {
                    return@withContext failure("create upload", "missing direct upload url")
                }
                offset = (resolvedSession.uploadedSize.toLongOrNull() ?: 0L).coerceIn(0L, totalBytes)
                uploadCheckpointStore.upsert(
                    checkpointKey,
                    UploadCheckpoint(
                        uploadId = resolvedUploadID,
                        totalBytes = totalBytes,
                        uploadedBytes = offset,
                        updatedAtMillis = System.currentTimeMillis(),
                        uploadMode = "RESUMABLE"
                    )
                )
            }
        }

        if (uploadId.isBlank()) {
            return@withContext failure("create upload", "missing upload id")
        }
        val resolvedUploadId = uploadId

        if (useBackendChunkUpload) {
            var retryCount = 0
            while (offset < totalBytes) {
                val chunkLength = min(uploadChunkSizeBytes, totalBytes - offset)
                val chunkBody = ChunkFileRequestBody(
                    file = uploadFile,
                    offset = offset,
                    length = chunkLength,
                    mediaType = uploadChunkMediaType,
                    onProgress = { sent, _ ->
                        onProgress((offset + sent).coerceAtMost(totalBytes), totalBytes)
                    }
                )
                val patchRequest = Request.Builder()
                    .url("$baseUrl/api/v1/attachments/uploads/$resolvedUploadId")
                    .patch(chunkBody)
                    .header("Upload-Offset", offset.toString())
                    .build()

                val patchResponse = try {
                    okHttpClient.newCall(patchRequest).execute()
                } catch (e: Throwable) {
                    retryCount += 1
                    if (retryCount > maxChunkRetryCount) {
                        return@withContext failure("upload chunk", "request execution failed after retries", e)
                    }
                    val latestOffset = queryUploadOffset(baseUrl, resolvedUploadId)
                    if (latestOffset >= 0L) {
                        offset = latestOffset.coerceIn(0L, totalBytes)
                        uploadCheckpointStore.updateProgress(checkpointKey, offset)
                    }
                    delay(retryDelayMillis)
                    continue
                }

                var chunkFatal: ApiResponse.Failure.Exception? = null
                val handled = patchResponse.use { response ->
                    if (response.isSuccessful) {
                        val nextOffset = response.header("Upload-Offset")?.toLongOrNull()
                        offset = (nextOffset ?: (offset + chunkLength)).coerceIn(0L, totalBytes)
                        uploadCheckpointStore.updateProgress(checkpointKey, offset)
                        onProgress(offset.coerceAtMost(totalBytes), totalBytes)
                        retryCount = 0
                        true
                    } else {
                        val isConflict = response.code == 409 || response.code == 412
                        if (isConflict) {
                            val latestOffset = response.header("Upload-Offset")?.toLongOrNull()
                                ?: queryUploadOffset(baseUrl, resolvedUploadId)
                            if (latestOffset >= 0L) {
                                offset = latestOffset.coerceIn(0L, totalBytes)
                                uploadCheckpointStore.updateProgress(checkpointKey, offset)
                            }
                            retryCount += 1
                            false
                        } else {
                            if (response.code == 404 || response.code == 410) {
                                uploadCheckpointStore.remove(checkpointKey)
                            }
                            chunkFatal = httpFailure("upload chunk", response)
                            false
                        }
                    }
                }
                val resolvedChunkFatal = chunkFatal
                if (resolvedChunkFatal != null) {
                    return@withContext resolvedChunkFatal
                }
                if (!handled) {
                    if (retryCount > maxChunkRetryCount) {
                        return@withContext failure("upload chunk", "offset conflict after retries")
                    }
                    delay(retryDelayMillis)
                }
            }
        } else if (multipartPartSizeBytes > 0L) {
            while (offset < totalBytes) {
                val chunkLength = min(multipartPartSizeBytes, totalBytes - offset)
                val partNumber = ((offset / multipartPartSizeBytes) + 1L).toInt()
                var requestRetryCount = 0
                var partUpload: MultipartPartUploadResponse? = null
                var offsetChanged = false
                while (partUpload == null) {
                    partUpload = requestMultipartPartUploadURL(
                        baseUrl = baseUrl,
                        uploadId = resolvedUploadId,
                        partNumber = partNumber,
                        offset = offset,
                        size = chunkLength
                    )
                    if (partUpload != null) {
                        break
                    }
                    requestRetryCount += 1
                    if (requestRetryCount > maxChunkRetryCount) {
                        return@withContext failure(
                            "upload multipart part",
                            "failed to request multipart part upload url after retries"
                        )
                    }
                    val latestOffset = queryUploadOffset(baseUrl, resolvedUploadId)
                    if (latestOffset >= 0L) {
                        val resolvedLatestOffset = latestOffset.coerceIn(0L, totalBytes)
                        if (resolvedLatestOffset != offset) {
                            offset = resolvedLatestOffset
                            offsetChanged = true
                        }
                        uploadCheckpointStore.updateProgress(checkpointKey, offset)
                        if (offset >= totalBytes) {
                            break
                        }
                    }
                    delay(retryDelayMillis)
                }
                if (offsetChanged) {
                    continue
                }
                if (offset >= totalBytes) {
                    break
                }
                val resolvedPartUpload = partUpload ?: return@withContext failure(
                    "upload multipart part",
                    "missing multipart upload url"
                )

                var retryCount = 0
                while (true) {
                    val chunkBody = ChunkFileRequestBody(
                        file = uploadFile,
                        offset = offset,
                        length = chunkLength,
                        mediaType = uploadMediaType,
                        onProgress = { sent, _ ->
                            onProgress((offset + sent).coerceAtMost(totalBytes), totalBytes)
                        }
                    )
                    val uploadRequest = Request.Builder()
                        .url(resolvedPartUpload.uploadUrl)
                        .put(chunkBody)
                        .header("Content-Type", uploadTypeValue)
                        .build()

                    val uploadResponse = try {
                        okHttpClient.newCall(uploadRequest).execute()
                    } catch (e: Throwable) {
                        retryCount += 1
                        if (retryCount > maxChunkRetryCount) {
                            return@withContext failure("upload multipart part", "request execution failed after retries", e)
                        }
                        val latestOffset = queryUploadOffset(baseUrl, resolvedUploadId)
                        if (latestOffset >= 0L) {
                            offset = latestOffset.coerceIn(0L, totalBytes)
                            uploadCheckpointStore.updateProgress(checkpointKey, offset)
                        }
                        delay(retryDelayMillis)
                        continue
                    }

                    var shouldRetry = false
                    var fatalFailure: ApiResponse.Failure.Exception? = null
                    uploadResponse.use { response ->
                        if (response.isSuccessful) {
                            offset = (offset + chunkLength).coerceAtMost(totalBytes)
                            uploadCheckpointStore.updateProgress(checkpointKey, offset)
                            onProgress(offset.coerceAtMost(totalBytes), totalBytes)
                        } else if (response.code in 500..599 || response.code == 408 || response.code == 429) {
                            shouldRetry = true
                        } else {
                            fatalFailure = httpFailure("upload multipart part", response)
                        }
                    }
                    val resolvedFatalFailure = fatalFailure
                    if (resolvedFatalFailure != null) {
                        return@withContext resolvedFatalFailure
                    }
                    if (!shouldRetry) {
                        break
                    }

                    retryCount += 1
                    if (retryCount > maxChunkRetryCount) {
                        return@withContext failure("upload multipart part", "request failed after retries")
                    }
                    val latestOffset = queryUploadOffset(baseUrl, resolvedUploadId)
                    if (latestOffset >= 0L) {
                        offset = latestOffset.coerceIn(0L, totalBytes)
                        uploadCheckpointStore.updateProgress(checkpointKey, offset)
                    }
                    delay(retryDelayMillis)
                }
            }
        }

        val completeRequest = Request.Builder()
            .url("$baseUrl/api/v1/attachments/uploads/$resolvedUploadId/complete")
            .post(ByteArray(0).toRequestBody(null))
            .build()
        val completeResponse = try {
            okHttpClient.newCall(completeRequest).execute()
        } catch (e: Throwable) {
            return@withContext failure("complete upload", "request execution failed", e)
        }

        var completeFailure: ApiResponse.Failure.Exception? = null
        var uploadedResource: KeerV2Resource? = null
        completeResponse.use { response ->
            if (!response.isSuccessful) {
                if (response.code == 404 || response.code == 410) {
                    uploadCheckpointStore.remove(checkpointKey)
                }
                completeFailure = httpFailure("complete upload", response)
                return@use
            }
            val body = response.body.string()
            if (body.isBlank()) {
                completeFailure = failure("complete upload", "response empty")
                return@use
            }
            try {
                uploadedResource = uploadJson.decodeFromString(KeerV2Resource.serializer(), body)
            } catch (e: Throwable) {
                completeFailure = failure("complete upload", "decode response failed", e)
            }
        }
        val resolvedCompleteFailure = completeFailure
        if (resolvedCompleteFailure != null) {
            return@withContext resolvedCompleteFailure
        }
        val resolvedResource = uploadedResource ?: return@withContext failure(
            "complete upload",
            "missing attachment in response"
        )
        uploadCheckpointStore.remove(checkpointKey)
        attachmentEncryptionManager.clearPreparedUpload(account.accountKey(), checkpointKey)

        return@withContext ApiResponse.Success(convertResource(resolvedResource))
    }

    private fun maybeCleanupStaleUploadCheckpoints(baseUrl: String) {
        val now = System.currentTimeMillis()
        val shouldRun = synchronized(this) {
            if (now - lastCheckpointCleanupAtMillis < uploadCheckpointCleanupIntervalMillis) {
                false
            } else {
                lastCheckpointCleanupAtMillis = now
                true
            }
        }
        if (!shouldRun) {
            return
        }
        val removed = uploadCheckpointStore.prune(
            now = now,
            maxAgeMillis = uploadCheckpointTTLMillis,
            maxEntries = uploadCheckpointMaxEntries
        )
        removed
            .asSequence()
            .map { it.uploadId }
            .filter { it.isNotBlank() }
            .distinct()
            .forEach { staleUploadId ->
                cancelUploadSession(baseUrl, staleUploadId)
            }
    }

    private fun resolveExistingUploadSession(
        baseUrl: String,
        checkpointKey: String,
        totalBytes: Long
    ): UploadSessionState? {
        val checkpoint = uploadCheckpointStore.get(checkpointKey) ?: return null
        if (checkpoint.totalBytes != totalBytes || checkpoint.uploadId.isBlank()) {
            uploadCheckpointStore.remove(checkpointKey)
            return null
        }

        val queryResult = queryUploadOffsetWithStatus(baseUrl, checkpoint.uploadId)
        val resolvedOffset = when (queryResult.status) {
            UploadOffsetQueryStatus.SUCCESS -> queryResult.offset
            UploadOffsetQueryStatus.NOT_FOUND -> {
                uploadCheckpointStore.remove(checkpointKey)
                return null
            }
            UploadOffsetQueryStatus.DIRECT_UNSUPPORTED -> {
                uploadCheckpointStore.remove(checkpointKey)
                cancelUploadSession(baseUrl, checkpoint.uploadId)
                return null
            }
            UploadOffsetQueryStatus.ERROR -> checkpoint.uploadedBytes
        }.coerceIn(0L, totalBytes)

        uploadCheckpointStore.updateProgress(checkpointKey, resolvedOffset)
        val resolvedMode = when (queryResult.status) {
            UploadOffsetQueryStatus.SUCCESS -> queryResult.uploadMode ?: checkpoint.uploadMode
            UploadOffsetQueryStatus.ERROR -> checkpoint.uploadMode
            UploadOffsetQueryStatus.NOT_FOUND,
            UploadOffsetQueryStatus.DIRECT_UNSUPPORTED -> null
        }
        val resolvedMultipartPartSizeBytes = when (queryResult.status) {
            UploadOffsetQueryStatus.SUCCESS -> queryResult.multipartPartSizeBytes ?: checkpoint.multipartPartSizeBytes
            UploadOffsetQueryStatus.ERROR -> checkpoint.multipartPartSizeBytes
            UploadOffsetQueryStatus.NOT_FOUND,
            UploadOffsetQueryStatus.DIRECT_UNSUPPORTED -> null
        }
        return UploadSessionState(
            uploadId = checkpoint.uploadId,
            offset = resolvedOffset,
            uploadMode = resolvedMode,
            multipartPartSizeBytes = resolvedMultipartPartSizeBytes
        )
    }

    private fun cancelUploadSession(baseUrl: String, uploadId: String) {
        if (uploadId.isBlank()) {
            return
        }
        val request = Request.Builder()
            .url("$baseUrl/api/v1/attachments/uploads/$uploadId")
            .delete()
            .build()
        runCatching {
            okHttpClient.newCall(request).execute().use { }
        }
    }

    private fun buildUploadCheckpointKey(
        filename: String,
        type: MediaType?,
        file: File,
        memoRemoteId: String?,
        thumbnail: ResourceUploadThumbnail?
    ): String {
        val raw = buildString {
            append(account.accountKey())
            append('\n')
            append(file.absolutePath)
            append('\n')
            append(file.length())
            append('\n')
            append(file.lastModified())
            append('\n')
            append(filename.trim())
            append('\n')
            append(type?.toString().orEmpty())
            append('\n')
            append(memoRemoteId?.let(::getName).orEmpty())
            append('\n')
            append(thumbnail?.filename.orEmpty())
            append('\n')
            append(thumbnail?.type.orEmpty())
            append('\n')
            append(thumbnail?.content?.let(::sha256Hex).orEmpty())
        }
        return sha256Hex(raw)
    }

    private fun requestMultipartPartUploadURL(
        baseUrl: String,
        uploadId: String,
        partNumber: Int,
        offset: Long,
        size: Long
    ): MultipartPartUploadResponse? {
        val url = "$baseUrl/api/v1/attachments/uploads/$uploadId/parts/$partNumber?offset=$offset&size=$size"
        val request = Request.Builder()
            .url(url)
            .get()
            .build()
        val response = try {
            okHttpClient.newCall(request).execute()
        } catch (_: Throwable) {
            return null
        }
        return response.use {
            if (!response.isSuccessful) {
                return null
            }
            val body = response.body.string()
            if (body.isBlank()) {
                return null
            }
            return runCatching {
                uploadJson.decodeFromString(MultipartPartUploadResponse.serializer(), body)
            }.getOrNull()
        }
    }

    private fun queryUploadOffset(baseUrl: String, uploadId: String): Long {
        val result = queryUploadOffsetWithStatus(baseUrl, uploadId)
        return if (result.status == UploadOffsetQueryStatus.SUCCESS) {
            result.offset
        } else {
            -1L
        }
    }

    private fun queryUploadOffsetWithStatus(baseUrl: String, uploadId: String): UploadOffsetQueryResult {
        val request = Request.Builder()
            .url("$baseUrl/api/v1/attachments/uploads/$uploadId")
            .head()
            .build()
        val response = try {
            okHttpClient.newCall(request).execute()
        } catch (_: Throwable) {
            return UploadOffsetQueryResult(UploadOffsetQueryStatus.ERROR, -1L)
        }
        return response.use {
            if (!response.isSuccessful) {
                if (response.code == 404 || response.code == 410) {
                    return UploadOffsetQueryResult(UploadOffsetQueryStatus.NOT_FOUND, -1L)
                }
                return UploadOffsetQueryResult(UploadOffsetQueryStatus.ERROR, -1L)
            }
            val uploadMode = response.header("Upload-Mode")?.trim().orEmpty()
            if (uploadMode.equals("DIRECT_PRESIGNED_PUT", ignoreCase = true)) {
                return UploadOffsetQueryResult(UploadOffsetQueryStatus.DIRECT_UNSUPPORTED, -1L)
            }
            val offset = response.header("Upload-Offset")?.toLongOrNull()
                ?: return UploadOffsetQueryResult(UploadOffsetQueryStatus.ERROR, -1L)
            val multipartPartSizeBytes = response.header("Upload-Part-Size")
                ?.toLongOrNull()
                ?.takeIf { it > 0L }
            UploadOffsetQueryResult(
                status = UploadOffsetQueryStatus.SUCCESS,
                offset = offset,
                uploadMode = uploadMode.ifBlank { "RESUMABLE" },
                multipartPartSizeBytes = multipartPartSizeBytes
            )
        }
    }

    private fun httpFailure(stage: String, response: Response): ApiResponse.Failure.Exception {
        val code = response.code
        val detail = runCatching { response.body.string().trim() }
            .getOrElse { "" }
            .ifEmpty { response.message.trim() }
        val message = if (detail.isNotEmpty()) {
            "$stage failed: HTTP $code - $detail"
        } else {
            "$stage failed: HTTP $code"
        }
        return ApiResponse.Failure.Exception(IllegalStateException(message))
    }

    private fun failure(
        stage: String,
        message: String,
        throwable: Throwable? = null
    ): ApiResponse.Failure.Exception {
        val causeDetail = throwable?.let {
            val className = it::class.simpleName ?: "Throwable"
            val reason = it.message?.trim().orEmpty()
            if (reason.isNotEmpty()) "$className: $reason" else className
        }
        val fullMessage = if (!causeDetail.isNullOrEmpty()) {
            "$stage failed: $message - $causeDetail"
        } else {
            "$stage failed: $message"
        }
        return ApiResponse.Failure.Exception(IllegalStateException(fullMessage, throwable))
    }

    override suspend fun deleteResource(remoteId: String): ApiResponse<Unit> {
        return memosApi.deleteResource(getId(remoteId))
    }

    override suspend fun getCurrentUser(): ApiResponse<User> {
        val resp = memosApi.getCurrentUser().mapSuccess {
            if (user == null) {
                throw KeerException.notLogin
            }
            User(
                user.name,
                user.username,
                user.createTime ?: Instant.now(),
                avatarUrl = user.avatarUrl
            )
        }
        if (resp !is ApiResponse.Success) {
            return resp
        }

        return memosApi.getUserSetting(getId(resp.data.identifier)).mapSuccess {
            resp.data.copy(
                defaultVisibility = generalSetting?.memoVisibility?.toMemoVisibility() ?: MemoVisibility.PRIVATE
            )
        }
    }

    private fun KeerV2UserSettingGeneralSetting?.toUserGeneralSettings(): UserGeneralSettings {
        if (this == null) {
            return UserGeneralSettings()
        }
        return UserGeneralSettings(
            memoVisibility = memoVisibility?.toMemoVisibility() ?: MemoVisibility.PRIVATE,
            memoEditGesture = memoEditGesture.toMemoEditGesture(),
            memoColumns = memoColumns.map { column ->
                MemoColumnConfig(
                    id = column.id,
                    name = column.name,
                    requiredTags = column.requiredTags,
                    visibleInDrawer = column.visibleInDrawer,
                    pinnedMemoRemoteIds = column.pinnedMemoRemoteIds,
                )
            },
            exploreDrawerEntries = exploreDrawerEntries
                .mapNotNull { entry ->
                    val normalizedEntryId = entry.entryId.trim()
                    if (normalizedEntryId.isEmpty()) {
                        null
                    } else {
                        ExploreDrawerEntryConfig(
                            entryId = normalizedEntryId,
                            visibleInExplore = entry.visibleInExplore,
                        )
                    }
                }
                .distinctBy { entry -> entry.entryId },
        )
    }

    private fun site.lcyk.keer.data.api.MemosRole.toRoleName(): String {
        return when (this) {
            site.lcyk.keer.data.api.MemosRole.ADMIN -> "ADMIN"
            site.lcyk.keer.data.api.MemosRole.HOST -> "HOST"
            site.lcyk.keer.data.api.MemosRole.USER -> "USER"
            else -> "USER"
        }
    }

    private fun String?.toMemoEditGesture(): MemoEditGesture {
        return runCatching { MemoEditGesture.valueOf(this?.trim().orEmpty()) }
            .getOrDefault(MemoEditGesture.NONE)
    }

    private suspend fun buildEncryptedMemoPayload(
        content: String,
        tags: List<String>,
        latitude: Double?,
        longitude: Double?,
    ): Pair<String, KeerV2PayloadEnvelope> {
        val contentKey = E2eeKeyEnvelope.randomBytes(32)
        val collaboratorIds = extractCollaboratorIds(tags)
        val wrappedKeys = if (collaboratorIds.isEmpty()) {
            listOf(accountKeyManager.wrapForAccountMasterKey(account, contentKey))
        } else {
            accountKeyManager.encryptForCollaborators(account, memosApi, collaboratorIds, contentKey)
        }
        val plaintext = uploadJson.encodeToString(
            RemoteMemoPayload.serializer(),
            RemoteMemoPayload(
                content = content,
                tags = tags,
                latitude = latitude,
                longitude = longitude,
            )
        )
        return encryptPayload(plaintext, contentKey, wrappedKeys)
    }

    private suspend fun buildEncryptedGroupPayload(
        group: KeerV2Group,
        content: String,
        tags: List<String>,
    ): Pair<String, KeerV2PayloadEnvelope> {
        val contentKey = E2eeKeyEnvelope.randomBytes(32)
        val wrappedKey = accountKeyManager.encryptForGroupVersion(account, memosApi, group, contentKey)
        val plaintext = uploadJson.encodeToString(
            RemoteMemoPayload.serializer(),
            RemoteMemoPayload(
                content = content,
                tags = tags,
            )
        )
        return encryptPayload(plaintext, contentKey, listOf(wrappedKey))
    }

    private fun encryptPayload(
        plaintext: String,
        contentKey: ByteArray,
        wrappedKeys: List<WrappedContentKey>,
    ): Pair<String, KeerV2PayloadEnvelope> {
        val iv = E2eeKeyEnvelope.randomBytes(12)
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            javax.crypto.Cipher.ENCRYPT_MODE,
            javax.crypto.spec.SecretKeySpec(contentKey, "AES"),
            javax.crypto.spec.GCMParameterSpec(128, iv),
        )
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val payload = RemoteEncryptedPayload(
            iv = java.util.Base64.getEncoder().encodeToString(iv),
            ciphertext = java.util.Base64.getEncoder().encodeToString(ciphertext),
        )
        return uploadJson.encodeToString(RemoteEncryptedPayload.serializer(), payload) to
            KeerV2PayloadEnvelope(
                wrappedKeys = wrappedKeys.map { wrappedKey ->
                    site.lcyk.keer.data.api.KeerV2WrappedKeySlot(
                        slotType = wrappedKey.slotType,
                        slotRef = wrappedKey.slotRef,
                        wrapAlgorithm = wrappedKey.wrapAlgorithm,
                        wrappedKey = wrappedKey.wrappedKey,
                    )
                }
            )
    }

    private fun decodeMemoPayload(
        encryptedPayload: String,
        payloadEnvelope: KeerV2PayloadEnvelope?,
    ): DecodedMemoPayload {
        val plaintext = decryptPayload(encryptedPayload, payloadEnvelope)
            ?: return DecodedMemoPayload(
                content = encryptedContentUnavailablePlaceholder,
                tags = emptyList(),
                latitude = null,
                longitude = null,
            )
        return runCatching {
            uploadJson.decodeFromString(RemoteMemoPayload.serializer(), plaintext)
        }.map { payload ->
            DecodedMemoPayload(
                content = payload.content,
                tags = payload.tags,
                latitude = payload.latitude,
                longitude = payload.longitude,
            )
        }.getOrElse {
            DecodedMemoPayload(
                content = encryptedContentUnavailablePlaceholder,
                tags = emptyList(),
                latitude = null,
                longitude = null,
            )
        }
    }

    private fun decryptPayload(
        encryptedPayload: String,
        payloadEnvelope: KeerV2PayloadEnvelope?,
    ): String? {
        if (encryptedPayload.isBlank() || payloadEnvelope == null) {
            return null
        }
        val payload = runCatching {
            uploadJson.decodeFromString(RemoteEncryptedPayload.serializer(), encryptedPayload)
        }.getOrNull() ?: return null
        val contentKey = accountKeyManager.unwrapContentKey(
            account,
            payloadEnvelope.wrappedKeys.map { wrappedKey ->
                WrappedContentKey(
                    slotType = wrappedKey.slotType,
                    slotRef = wrappedKey.slotRef,
                    wrapAlgorithm = wrappedKey.wrapAlgorithm,
                    wrappedKey = wrappedKey.wrappedKey,
                )
            }
        ) ?: return null
        return runCatching {
            val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                javax.crypto.Cipher.DECRYPT_MODE,
                javax.crypto.spec.SecretKeySpec(contentKey, "AES"),
                javax.crypto.spec.GCMParameterSpec(128, java.util.Base64.getDecoder().decode(payload.iv)),
            )
            cipher.doFinal(java.util.Base64.getDecoder().decode(payload.ciphertext)).toString(Charsets.UTF_8)
        }.getOrNull()
    }

    private fun convertQuotePreview(memo: KeerV2Memo): QuotePreviewSnapshot? {
        val quotedMemo = memo.quote?.memo ?: return memo.quote?.let {
            QuotePreviewSnapshot(
                status = MEMO_QUOTE_STATUS_UNAVAILABLE,
                contentPreview = null,
                date = null,
                hasAttachments = false,
            )
        }
        val hasAttachments = quotedMemo.attachments?.isNotEmpty() == true
        val payload = decodeMemoPayload(
            encryptedPayload = quotedMemo.encryptedPayload.orEmpty(),
            payloadEnvelope = quotedMemo.payloadEnvelope,
        )
        val quoteContent = payload.content
        val resolvedStatus = if (quoteContent == encryptedContentUnavailablePlaceholder && !hasAttachments) {
            MEMO_QUOTE_STATUS_UNAVAILABLE
        } else {
            MEMO_QUOTE_STATUS_RESOLVED
        }
        return QuotePreviewSnapshot(
            status = resolvedStatus,
            contentPreview = if (resolvedStatus == MEMO_QUOTE_STATUS_RESOLVED) {
                buildMemoQuotePreviewText(quoteContent)
            } else {
                null
            },
            date = quotedMemo.createTime ?: quotedMemo.updateTime,
            hasAttachments = hasAttachments,
        )
    }

    private fun decodeResourceDescriptor(
        ciphertext: String?,
        envelope: KeerV2PayloadEnvelope?,
    ): RemoteAttachmentDescriptor? {
        val plaintext = decryptPayload(ciphertext.orEmpty(), envelope) ?: return null
        return runCatching {
            uploadJson.decodeFromString(RemoteAttachmentDescriptor.serializer(), plaintext)
        }.getOrNull()
    }

    private fun buildLocalAttachmentEncryptionMetadata(
        resource: KeerV2Resource,
        descriptor: RemoteAttachmentDescriptor?,
    ): String? {
        val main = resource.blobEncryption?.takeIf { it.isNotBlank() }?.let { raw ->
            runCatching { uploadJson.decodeFromString(EncryptedBlobMetadata.serializer(), raw) }.getOrNull()
        } ?: return null
        val thumbnail = resource.thumbnailBlobEncryption?.takeIf { it.isNotBlank() }?.let { raw ->
            runCatching { uploadJson.decodeFromString(EncryptedBlobMetadata.serializer(), raw) }.getOrNull()
        }
        return uploadJson.encodeToString(
            AttachmentEncryptionMetadata.serializer(),
            AttachmentEncryptionMetadata(
                originalMimeType = descriptor?.originalMimeType,
                main = main,
                thumbnail = thumbnail,
            )
        )
    }

    private suspend fun buildAttachmentRequestResources(
        resourceRemoteIds: List<String>,
        encryptionScope: ResourceEncryptionScope,
    ): List<KeerV2Resource> {
        val distinctIds = resourceRemoteIds
            .map(::getName)
            .filter { remoteId -> remoteId.isNotBlank() }
            .distinct()
        if (distinctIds.isEmpty()) {
            return emptyList()
        }
        val remoteResources = loadResourcesByRemoteIds(distinctIds)
        return distinctIds.map { remoteId ->
            val remoteResource = remoteResources[getId(remoteId)]
            if (remoteResource == null) {
                KeerV2Resource(name = getName(remoteId))
            } else {
                buildAttachmentRequestResource(remoteResource, encryptionScope)
            }
        }
    }

    private suspend fun loadResourcesByRemoteIds(
        resourceRemoteIds: List<String>,
    ): Map<String, KeerV2Resource> {
        if (resourceRemoteIds.isEmpty()) {
            return emptyMap()
        }
        val needed = resourceRemoteIds.map(::getId).toSet()
        return when (val response = memosApi.listResources()) {
            is ApiResponse.Success -> response.data.attachments
                .filter { resource ->
                    val name = resource.name ?: return@filter false
                    getId(name) in needed
                }
                .associateBy { resource -> getId(resource.name.orEmpty()) }
            else -> emptyMap()
        }
    }

    private suspend fun buildAttachmentRequestResource(
        resource: KeerV2Resource,
        encryptionScope: ResourceEncryptionScope,
    ): KeerV2Resource {
        val descriptor = decodeResourceDescriptor(
            ciphertext = resource.descriptorCiphertext,
            envelope = resource.descriptorEnvelope,
        )
        val rawMetadata = buildLocalAttachmentEncryptionMetadata(resource, descriptor)
            ?.takeIf { metadata -> metadata.isNotBlank() }
            ?: return KeerV2Resource(name = resource.name)
        val parsedMetadata = AttachmentEncryptionManager.parseMetadata(rawMetadata)
            ?: return KeerV2Resource(name = resource.name)
        val descriptorPayload = runCatching {
            buildEncryptedAttachmentDescriptor(
                filename = descriptor?.filename ?: resource.filename ?: encryptedAttachmentFilename,
                originalMimeType = descriptor?.originalMimeType,
                thumbnailMimeType = descriptor?.thumbnailMimeType ?: resource.thumbnailType,
                encryptionScope = encryptionScope,
            )
        }.getOrElse {
            return KeerV2Resource(name = resource.name)
        }
        val mainWrappedBlob = runCatching {
            val mainKey = attachmentEncryptionManager.unwrapVariantKey(
                accountKey = account.accountKey(),
                rawMetadata = rawMetadata,
                variant = EncryptedBlobVariant.MAIN,
            ) ?: error("missing main attachment key")
            parsedMetadata.main.withWrappedKeys(resolveAttachmentWrappedKeys(encryptionScope, mainKey))
        }.getOrElse {
            return KeerV2Resource(name = resource.name)
        }
        val thumbnailWrappedBlob = parsedMetadata.thumbnail?.let {
            runCatching {
                val thumbnailKey = attachmentEncryptionManager.unwrapVariantKey(
                    accountKey = account.accountKey(),
                    rawMetadata = rawMetadata,
                    variant = EncryptedBlobVariant.THUMBNAIL,
                ) ?: error("missing thumbnail attachment key")
                it.withWrappedKeys(resolveAttachmentWrappedKeys(encryptionScope, thumbnailKey))
            }.getOrElse {
                return@let null
            }
        }
        return KeerV2Resource(
            name = resource.name,
            descriptorCiphertext = descriptorPayload.first,
            descriptorEnvelope = descriptorPayload.second,
            blobEncryption = uploadJson.encodeToString(
                EncryptedBlobMetadata.serializer(),
                mainWrappedBlob,
            ),
            thumbnailBlobEncryption = thumbnailWrappedBlob?.let { thumb ->
                uploadJson.encodeToString(EncryptedBlobMetadata.serializer(), thumb)
            },
        )
    }

    private fun memoAttachmentEncryptionScope(tags: List<String>): ResourceEncryptionScope {
        val collaboratorIds = extractCollaboratorIds(tags)
            .map(::normalizeUserID)
            .filter { collaboratorId -> collaboratorId.isNotBlank() }
            .distinct()
        return if (collaboratorIds.isEmpty()) {
            ResourceEncryptionScope.Account
        } else {
            ResourceEncryptionScope.Collaborators(collaboratorIds)
        }
    }

    private fun EncryptedBlobMetadata.withWrappedKeys(
        wrappedKeys: List<WrappedContentKey>,
    ): EncryptedBlobMetadata {
        return copy(
            wrappedKeys = wrappedKeys,
        )
    }

    private suspend fun buildEncryptedAttachmentDescriptor(
        filename: String,
        originalMimeType: String?,
        thumbnailMimeType: String?,
        encryptionScope: ResourceEncryptionScope,
    ): Pair<String, KeerV2PayloadEnvelope> {
        val descriptorKey = E2eeKeyEnvelope.randomBytes(32)
        val wrappedKeys = resolveAttachmentWrappedKeys(encryptionScope, descriptorKey)
        val plaintext = uploadJson.encodeToString(
            RemoteAttachmentDescriptor.serializer(),
            RemoteAttachmentDescriptor(
                filename = filename,
                originalMimeType = originalMimeType,
                thumbnailMimeType = thumbnailMimeType,
            )
        )
        return encryptPayload(plaintext, descriptorKey, wrappedKeys)
    }

    private suspend fun resolveAttachmentWrappedKeys(
        encryptionScope: ResourceEncryptionScope,
        rawKey: ByteArray,
    ): List<WrappedContentKey> {
        return when (encryptionScope) {
            ResourceEncryptionScope.Account -> listOf(accountKeyManager.wrapForAccountMasterKey(account, rawKey))
            is ResourceEncryptionScope.Collaborators -> {
                val collaboratorIds = encryptionScope.userIds
                    .map(::normalizeUserID)
                    .filter { collaboratorId -> collaboratorId.isNotBlank() }
                    .distinct()
                if (collaboratorIds.isEmpty()) {
                    listOf(accountKeyManager.wrapForAccountMasterKey(account, rawKey))
                } else {
                    accountKeyManager.encryptForCollaborators(account, memosApi, collaboratorIds, rawKey)
                }
            }
            is ResourceEncryptionScope.Group -> {
                val group = loadGroupById(encryptionScope.groupId)
                    ?: throw IllegalStateException("Group not found: ${encryptionScope.groupId}")
                listOf(accountKeyManager.encryptForGroupVersion(account, memosApi, group, rawKey))
            }
        }
    }

    private suspend fun loadGroupById(groupId: String): KeerV2Group? {
        return when (val response = memosApi.listGroups()) {
            is ApiResponse.Success -> response.data.groups.firstOrNull { group ->
                getId(group.name) == getId(groupId)
            }
            else -> null
        }
    }

    private suspend fun collectGroupTagsFromMessages(groupId: String): ApiResponse<List<String>> {
        val normalizedGroupId = groupId.trim()
        if (normalizedGroupId.isEmpty()) {
            return ApiResponse.Success(emptyList())
        }
        accountKeyManager.loadCurrentGroupKey(account, memosApi, normalizedGroupId)
        var nextPageToken: String? = null
        val tags = linkedSetOf<String>()
        do {
            val response = memosApi.listGroupMessages(normalizedGroupId, PAGE_SIZE, nextPageToken)
            if (response !is ApiResponse.Success) {
                return response.mapSuccess { emptyList() }
            }
            response.data.messages.forEach { message ->
                normalizeTags(
                    decodeMemoPayload(
                        encryptedPayload = message.encryptedPayload.orEmpty(),
                        payloadEnvelope = message.payloadEnvelope,
                    ).tags
                ).forEach(tags::add)
            }
            nextPageToken = response.data.nextPageToken?.ifBlank { null }
        } while (!nextPageToken.isNullOrBlank())
        return ApiResponse.Success(tags.toList())
    }

    private fun normalizeTags(rawTags: Collection<String>): List<String> {
        val normalized = linkedSetOf<String>()
        rawTags.forEach { rawTag ->
            val trimmed = rawTag.trim()
            if (trimmed.isNotEmpty()) {
                normalized += trimmed
            }
        }
        return normalized.toList()
    }

    private fun String.toMemoGroupType(): MemoGroupType {
        return runCatching { MemoGroupType.valueOf(trim().uppercase()) }
            .getOrDefault(MemoGroupType.GROUP)
    }
}

@Serializable
private data class ResumableUploadCreateRequest(
    val descriptorCiphertext: String,
    val descriptorEnvelope: KeerV2PayloadEnvelope? = null,
    val blobEncryption: String,
    val thumbnailBlobEncryption: String? = null,
    val filename: String,
    val type: String,
    val size: Long,
    val memo: String?,
    val thumbnail: ResumableUploadThumbnailRequest? = null
)

@Serializable
private data class ResumableUploadThumbnailRequest(
    val filename: String,
    val type: String,
    val content: String
)

@Serializable
private data class ResumableUploadCreateResponse(
    val uploadId: String,
    val uploadedSize: String = "0",
    val size: String? = null,
    val uploadMode: String? = null,
    val directUploadUrl: String? = null,
    val directUploadMethod: String? = null,
    val multipartPartSize: String? = null
)

@Serializable
private data class MultipartPartUploadResponse(
    val uploadId: String,
    val partNumber: Int,
    val offset: String,
    val size: String,
    val uploadUrl: String,
    val method: String? = null
)

private data class UploadSessionState(
    val uploadId: String,
    val offset: Long,
    val uploadMode: String? = null,
    val multipartPartSizeBytes: Long? = null
)

private enum class UploadOffsetQueryStatus {
    SUCCESS,
    NOT_FOUND,
    DIRECT_UNSUPPORTED,
    ERROR
}

private data class UploadOffsetQueryResult(
    val status: UploadOffsetQueryStatus,
    val offset: Long,
    val uploadMode: String? = null,
    val multipartPartSizeBytes: Long? = null
)

@Serializable
private data class UploadCheckpoint(
    val uploadId: String,
    val totalBytes: Long,
    val uploadedBytes: Long = 0L,
    val updatedAtMillis: Long,
    val uploadMode: String? = null,
    val multipartPartSizeBytes: Long? = null
)

@Serializable
private data class UploadCheckpointSnapshot(
    val entries: Map<String, UploadCheckpoint> = emptyMap()
)

private class ResumableUploadCheckpointStore(
    private val file: File
) {
    private val lock = Any()
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
    }

    fun get(key: String): UploadCheckpoint? = synchronized(lock) {
        readSnapshot().entries[key]
    }

    fun upsert(key: String, checkpoint: UploadCheckpoint) = synchronized(lock) {
        val entries = readSnapshot().entries.toMutableMap()
        entries[key] = checkpoint
        writeSnapshot(UploadCheckpointSnapshot(entries))
    }

    fun updateProgress(key: String, uploadedBytes: Long) = synchronized(lock) {
        val snapshot = readSnapshot()
        val current = snapshot.entries[key] ?: return@synchronized
        val next = current.copy(
            uploadedBytes = uploadedBytes.coerceIn(0L, current.totalBytes),
            updatedAtMillis = System.currentTimeMillis()
        )
        val entries = snapshot.entries.toMutableMap()
        entries[key] = next
        writeSnapshot(UploadCheckpointSnapshot(entries))
    }

    fun remove(key: String): UploadCheckpoint? = synchronized(lock) {
        val entries = readSnapshot().entries.toMutableMap()
        val removed = entries.remove(key) ?: return@synchronized null
        writeSnapshot(UploadCheckpointSnapshot(entries))
        removed
    }

    fun prune(now: Long, maxAgeMillis: Long, maxEntries: Int): List<UploadCheckpoint> = synchronized(lock) {
        val snapshot = readSnapshot()
        if (snapshot.entries.isEmpty()) {
            return@synchronized emptyList()
        }
        val entries = snapshot.entries.toMutableMap()
        val removed = mutableListOf<UploadCheckpoint>()
        val expireBefore = now - maxAgeMillis

        val expiredKeys = entries
            .filterValues { it.updatedAtMillis < expireBefore }
            .keys
        expiredKeys.forEach { key ->
            entries.remove(key)?.let(removed::add)
        }

        if (entries.size > maxEntries) {
            val overflowKeys = entries.entries
                .sortedBy { it.value.updatedAtMillis }
                .take(entries.size - maxEntries)
                .map { it.key }
            overflowKeys.forEach { key ->
                entries.remove(key)?.let(removed::add)
            }
        }

        if (removed.isNotEmpty()) {
            writeSnapshot(UploadCheckpointSnapshot(entries))
        }
        removed
    }

    private fun readSnapshot(): UploadCheckpointSnapshot {
        if (!file.exists()) {
            return UploadCheckpointSnapshot()
        }
        return runCatching {
            val raw = file.readText()
            if (raw.isBlank()) {
                UploadCheckpointSnapshot()
            } else {
                json.decodeFromString(UploadCheckpointSnapshot.serializer(), raw)
            }
        }.getOrElse {
            UploadCheckpointSnapshot()
        }
    }

    private fun writeSnapshot(snapshot: UploadCheckpointSnapshot) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(json.encodeToString(UploadCheckpointSnapshot.serializer(), snapshot))
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
    }
}

private class ChunkFileRequestBody(
    private val file: File,
    private val offset: Long,
    private val length: Long,
    private val mediaType: MediaType,
    private val onProgress: (uploadedBytes: Long, totalBytes: Long) -> Unit
) : RequestBody() {
    override fun contentType(): MediaType = mediaType

    override fun contentLength(): Long = length

    override fun writeTo(sink: BufferedSink) {
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(offset)
            val buffer = ByteArray(8 * 1024)
            var written = 0L
            while (written < length) {
                val remaining = length - written
                val toRead = min(buffer.size.toLong(), remaining).toInt()
                val read = raf.read(buffer, 0, toRead)
                if (read <= 0) {
                    break
                }
                sink.write(buffer, 0, read)
                written += read
                onProgress(written, length)
            }
        }
    }
}

private fun sha256Hex(input: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val bytes = digest.digest(input.toByteArray())
    return buildString(bytes.size * 2) {
        for (byte in bytes) {
            val v = byte.toInt() and 0xFF
            append("0123456789abcdef"[v ushr 4])
            append("0123456789abcdef"[v and 0x0F])
        }
    }
}

@Serializable
private data class RemoteMemoPayload(
    val content: String,
    val tags: List<String> = emptyList(),
    val latitude: Double? = null,
    val longitude: Double? = null,
)

@Serializable
private data class RemoteEncryptedPayload(
    val version: Int = 1,
    val algorithm: String = "AES_GCM_PAYLOAD_V1",
    val iv: String,
    val ciphertext: String,
)

@Serializable
private data class RemoteAttachmentDescriptor(
    val filename: String,
    val originalMimeType: String? = null,
    val thumbnailMimeType: String? = null,
)

private data class DecodedMemoPayload(
    val content: String,
    val tags: List<String>,
    val latitude: Double?,
    val longitude: Double?,
)

private data class QuotePreviewSnapshot(
    val status: String,
    val contentPreview: String?,
    val date: Instant?,
    val hasAttachments: Boolean,
)

private const val encryptedContentUnavailablePlaceholder = "[Encrypted content unavailable]"
