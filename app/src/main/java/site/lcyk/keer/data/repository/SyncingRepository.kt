package site.lcyk.keer.data.repository

import android.net.Uri
import androidx.room.withTransaction
import androidx.core.net.toUri
import com.skydoves.sandwich.ApiResponse
import com.skydoves.sandwich.StatusCode
import com.skydoves.sandwich.getOrNull
import com.skydoves.sandwich.retrofit.statusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import site.lcyk.keer.R
import site.lcyk.keer.data.local.FileStorage
import site.lcyk.keer.data.local.KeerDatabase
import site.lcyk.keer.data.local.dao.MemoDao
import site.lcyk.keer.data.local.entity.MemoEntity
import site.lcyk.keer.data.local.entity.ResourceEntity
import site.lcyk.keer.data.constant.KeerException
import site.lcyk.keer.data.model.Account
import site.lcyk.keer.data.model.Memo
import site.lcyk.keer.data.model.MemoVisibility
import site.lcyk.keer.data.model.Resource
import site.lcyk.keer.data.model.SyncStatus
import site.lcyk.keer.data.model.User
import site.lcyk.keer.ext.getErrorMessage
import site.lcyk.keer.ext.string
import site.lcyk.keer.util.extractCollaboratorIds
import site.lcyk.keer.util.isValidTagName
import site.lcyk.keer.util.normalizeTagName
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import timber.log.Timber
import java.io.File
import java.time.Instant
import java.util.Base64
import java.util.UUID

class SyncingRepository(
    private val database: KeerDatabase,
    private val memoDao: MemoDao,
    private val fileStorage: FileStorage,
    private val remoteRepository: RemoteRepository,
    private val account: Account,
    private val readMemoSyncCursor: suspend () -> String? = { null },
    private val writeMemoSyncCursor: suspend (String) -> Unit = {},
    private val onUserSynced: suspend (User) -> Unit = {},
) : AbstractMemoRepository() {
    private data class UploadedResourcesResult(
        val remoteResourceIds: List<String>,
        val failedUploads: Int
    )

    private data class PendingUpload(
        val resource: ResourceEntity,
        val file: File?,
        val sizeBytes: Long
    )

    private data class PendingRemoteApply(
        val remoteMemo: Memo,
        val preferredLocalIdentifier: String?,
    )

    private data class PendingMarkSynced(
        val local: MemoEntity,
        val remoteMemo: Memo,
    )

    private val accountKey = account.accountKey()
    private val recentTagUsageSince: Instant = Instant.now().minusSeconds(30L * 24L * 60L * 60L)
    private var currentUser: User = account.toUser()
    @Volatile
    private var lastCurrentUserRefreshAtMillis: Long = 0L
    private val operationMutex = Mutex()
    private val operationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pendingDetailedSyncError: String? = null
    private val _syncStatus = MutableStateFlow(SyncStatus())
    private val syncApplyPipeline = SyncApplyPipeline(SYNC_APPLY_CHUNK_SIZE)
    private val thumbnailUploadScheduler = ThumbnailUploadTaskScheduler()
    override val syncStatus: StateFlow<SyncStatus> = _syncStatus.asStateFlow()

    init {
        operationScope.launch {
            refreshUnsyncedCount()
        }
    }

    override fun observeMemos(): Flow<List<MemoEntity>> {
        return memoDao.observeAllMemos(accountKey).map { memos ->
            memos.map { item ->
                item.memo.copy().also {
                    it.resources = item.resources
                    it.tags = item.tags.map { tag -> tag.name }
                }
            }
        }.flowOn(Dispatchers.Default)
    }

    override fun observeResources(): Flow<List<ResourceEntity>> {
        return memoDao.observeAllResources(accountKey)
    }

    override fun observeTags(): Flow<List<String>> {
        return memoDao.observeTagsByRecentUsage(accountKey, recentTagUsageSince)
    }

    override fun observeResource(identifier: String): Flow<ResourceEntity?> {
        return memoDao.observeResourceByIdentifier(identifier, accountKey)
    }

    override suspend fun listMemos(): ApiResponse<List<MemoEntity>> {
        return try {
            val memos = memoDao.getAllMemoItems(accountKey).map(::toMemoEntity)
            ApiResponse.Success(memos)
        } catch (e: Exception) {
            ApiResponse.Failure.Exception(e)
        }
    }

    override suspend fun listArchivedMemos(): ApiResponse<List<MemoEntity>> {
        return try {
            val memos = memoDao.getArchivedMemoItems(accountKey)
                .map(::toMemoEntity)
                .filterNot { it.isDeleted }
            ApiResponse.Success(memos)
        } catch (e: Exception) {
            ApiResponse.Failure.Exception(e)
        }
    }

    override suspend fun createMemo(
        content: String,
        visibility: MemoVisibility,
        resources: List<ResourceEntity>,
        tags: List<String>?,
        createdAt: Instant?,
        latitude: Double?,
        longitude: Double?
    ): ApiResponse<MemoEntity> {
        return try {
            val now = Instant.now()
            val memoDate = createdAt ?: now
            val localMemo = MemoEntity(
                identifier = UUID.randomUUID().toString(),
                remoteId = null,
                accountKey = accountKey,
                content = content,
                date = memoDate,
                visibility = visibility,
                pinned = false,
                archived = false,
                latitude = latitude,
                longitude = longitude,
                needsSync = true,
                isDeleted = false,
                lastModified = now,
                lastSyncedAt = null
            )
            memoDao.insertMemo(localMemo)
            memoDao.replaceMemoTags(localMemo.identifier, accountKey, tags.orEmpty())

            resources.forEach { resource ->
                memoDao.insertResource(
                    resource.copy(
                        accountKey = accountKey,
                        memoId = localMemo.identifier
                    )
                )
            }
            notifyMemoRelationsChanged(localMemo)

            refreshUnsyncedCount()
            enqueuePushMemo(localMemo.identifier)
            ApiResponse.Success(withResources(localMemo))
        } catch (e: Exception) {
            ApiResponse.Failure.Exception(e)
        }
    }

    override suspend fun updateMemo(
        identifier: String,
        content: String?,
        resources: List<ResourceEntity>?,
        visibility: MemoVisibility?,
        tags: List<String>?,
        pinned: Boolean?
    ): ApiResponse<MemoEntity> {
        return try {
            val existingMemo = memoDao.getMemoById(identifier, accountKey)
                ?: return ApiResponse.Failure.Exception(Exception(R.string.memo_not_found.string))

            val updatedMemo = existingMemo.copy(
                content = content ?: existingMemo.content,
                visibility = visibility ?: existingMemo.visibility,
                pinned = pinned ?: existingMemo.pinned,
                needsSync = true,
                isDeleted = false,
                lastModified = Instant.now()
            )
            memoDao.insertMemo(updatedMemo)
            if (tags != null) {
                memoDao.replaceMemoTags(identifier, accountKey, tags)
            }

            if (resources != null) {
                val existingResources = memoDao.getMemoResources(identifier, accountKey)
                val incomingIds = resources.mapTo(hashSetOf()) { it.identifier }
                existingResources.forEach { existing ->
                    if (existing.identifier !in incomingIds) {
                        deleteLocalFile(existing)
                        memoDao.deleteResource(existing)
                    }
                }
                resources.forEach { resource ->
                    memoDao.insertResource(
                        resource.copy(
                            accountKey = accountKey,
                            memoId = identifier
                        )
                    )
                }
            }
            notifyMemoRelationsChanged(updatedMemo)

            refreshUnsyncedCount()
            enqueuePushMemo(updatedMemo.identifier)
            ApiResponse.Success(withResources(updatedMemo))
        } catch (e: Exception) {
            ApiResponse.Failure.Exception(e)
        }
    }

    override suspend fun deleteMemo(identifier: String): ApiResponse<Unit> {
        return try {
            val memo = memoDao.getMemoById(identifier, accountKey)
                ?: return ApiResponse.Failure.Exception(Exception(R.string.memo_not_found.string))
            memoDao.insertMemo(
                memo.copy(
                    isDeleted = true,
                    needsSync = true,
                    lastModified = Instant.now()
                )
            )
            refreshUnsyncedCount()
            enqueuePushMemo(identifier)
            ApiResponse.Success(Unit)
        } catch (e: Exception) {
            ApiResponse.Failure.Exception(e)
        }
    }

    override suspend fun archiveMemo(identifier: String): ApiResponse<Unit> {
        return try {
            val memo = memoDao.getMemoById(identifier, accountKey)
                ?: return ApiResponse.Failure.Exception(Exception(R.string.memo_not_found.string))
            memoDao.insertMemo(
                memo.copy(
                    archived = true,
                    needsSync = true,
                    lastModified = Instant.now()
                )
            )
            refreshUnsyncedCount()
            enqueuePushMemo(identifier)
            ApiResponse.Success(Unit)
        } catch (e: Exception) {
            ApiResponse.Failure.Exception(e)
        }
    }

    override suspend fun restoreMemo(identifier: String): ApiResponse<Unit> {
        return try {
            val memo = memoDao.getMemoById(identifier, accountKey)
                ?: return ApiResponse.Failure.Exception(Exception(R.string.memo_not_found.string))
            memoDao.insertMemo(
                memo.copy(
                    archived = false,
                    needsSync = true,
                    lastModified = Instant.now()
                )
            )
            refreshUnsyncedCount()
            enqueuePushMemo(identifier)
            ApiResponse.Success(Unit)
        } catch (e: Exception) {
            ApiResponse.Failure.Exception(e)
        }
    }

    override suspend fun listTags(): ApiResponse<List<String>> {
        return try {
            val tags = memoDao.listTagsByRecentUsage(
                accountKey = accountKey,
                since = recentTagUsageSince
            )
            ApiResponse.Success(tags)
        } catch (e: Exception) {
            ApiResponse.Failure.Exception(e)
        }
    }

    override suspend fun renameTag(oldTag: String, newTag: String): ApiResponse<Unit> {
        return try {
            val normalizedOldTag = normalizeTagName(oldTag)
            val normalizedNewTag = normalizeTagName(newTag)
            if (normalizedOldTag.isEmpty() || normalizedNewTag.isEmpty()) {
                return ApiResponse.Failure.Exception(IllegalArgumentException(R.string.tag_name_empty.string))
            }
            if (!isValidTagName(normalizedNewTag)) {
                return ApiResponse.Failure.Exception(IllegalArgumentException(R.string.invalid_tag_name.string))
            }
            if (normalizedOldTag == normalizedNewTag) {
                return ApiResponse.Success(Unit)
            }

            operationMutex.withLock {
                val now = Instant.now()
                val memoIds = memoDao.listMemoIdsByTagPrefix(
                    accountKey = accountKey,
                    tag = normalizedOldTag,
                    tagPrefix = "$normalizedOldTag/%"
                )

                memoIds.forEach { memoId ->
                    val memo = memoDao.getMemoById(memoId, accountKey) ?: return@forEach
                    if (memo.isDeleted) {
                        return@forEach
                    }
                    val updatedTags = memoDao.getMemoTags(memoId, accountKey)
                        .map { renameTagWithPrefix(it, normalizedOldTag, normalizedNewTag) }
                        .distinct()
                    memoDao.replaceMemoTags(memoId, accountKey, updatedTags)
                    memoDao.insertMemo(
                        memo.copy(
                            needsSync = true,
                            isDeleted = false,
                            lastModified = now
                        )
                    )
                    enqueuePushMemo(memoId)
                }
                memoDao.pruneUnusedTags(accountKey)
                refreshUnsyncedCount()
            }
            ApiResponse.Success(Unit)
        } catch (e: Exception) {
            ApiResponse.Failure.Exception(e)
        }
    }

    override suspend fun deleteTag(tag: String, deleteAssociatedMemos: Boolean): ApiResponse<Unit> {
        return try {
            val normalizedTag = normalizeTagName(tag)
            if (normalizedTag.isEmpty()) {
                return ApiResponse.Failure.Exception(IllegalArgumentException(R.string.tag_name_empty.string))
            }

            operationMutex.withLock {
                val memoIds = memoDao.listMemoIdsByTagPrefix(
                    accountKey = accountKey,
                    tag = normalizedTag,
                    tagPrefix = "$normalizedTag/%"
                )

                if (deleteAssociatedMemos) {
                    memoIds.forEach { memoId ->
                        val result = deleteMemo(memoId)
                        if (result !is ApiResponse.Success) {
                            return result
                        }
                    }
                    memoDao.pruneUnusedTags(accountKey)
                    refreshUnsyncedCount()
                    return ApiResponse.Success(Unit)
                }

                val now = Instant.now()
                memoIds.forEach { memoId ->
                    val memo = memoDao.getMemoById(memoId, accountKey) ?: return@forEach
                    if (memo.isDeleted) {
                        return@forEach
                    }
                    val updatedTags = memoDao.getMemoTags(memoId, accountKey)
                        .filterNot { matchesTagOrDescendant(it, normalizedTag) }
                    memoDao.replaceMemoTags(memoId, accountKey, updatedTags)
                    memoDao.insertMemo(
                        memo.copy(
                            needsSync = true,
                            isDeleted = false,
                            lastModified = now
                        )
                    )
                    enqueuePushMemo(memoId)
                }
                memoDao.pruneUnusedTags(accountKey)
                refreshUnsyncedCount()
            }
            ApiResponse.Success(Unit)
        } catch (e: Exception) {
            ApiResponse.Failure.Exception(e)
        }
    }

    override suspend fun listResources(): ApiResponse<List<ResourceEntity>> {
        return try {
            when (val remoteResponse = remoteRepository.listResources()) {
                is ApiResponse.Success -> {
                    val localResources = memoDao.getAllResources(accountKey)
                    val localByRemoteId = localResources
                        .mapNotNull { resource -> resource.remoteId?.let { remoteId -> remoteId to resource } }
                        .toMap()
                    val remoteResources = remoteResponse.data.map { remote ->
                        val existing = localByRemoteId[remote.remoteId]
                        ResourceEntity(
                            identifier = existing?.identifier ?: "${remote.remoteId}|resource",
                            remoteId = remote.remoteId,
                            accountKey = accountKey,
                            date = remote.date,
                            filename = remote.filename,
                            uri = remote.uri,
                            localUri = existing?.localUri,
                            mimeType = remote.mimeType,
                            encryptionMetadata = remote.encryptionMetadata,
                            thumbnailUri = remote.thumbnailUri,
                            thumbnailLocalUri = existing?.thumbnailLocalUri,
                            memoId = existing?.memoId,
                        )
                    }
                    remoteResources.forEach { resource ->
                        memoDao.insertResource(resource)
                    }
                    val localDrafts = localResources.filter { resource -> resource.remoteId == null }
                    ApiResponse.Success(
                        (remoteResources + localDrafts).sortedByDescending { resource -> resource.date }
                    )
                }
                is ApiResponse.Failure.Error -> ApiResponse.Failure.Error(remoteResponse.payload)
                is ApiResponse.Failure.Exception -> ApiResponse.Failure.Exception(remoteResponse.throwable)
            }
        } catch (e: Exception) {
            ApiResponse.Failure.Exception(e)
        }
    }

    override suspend fun createResource(
        filename: String,
        type: MediaType?,
        sourceUri: Uri,
        memoIdentifier: String?,
        onProgress: (uploadedBytes: Long, totalBytes: Long) -> Unit
    ): ApiResponse<ResourceEntity> {
        return try {
            val localUri = fileStorage.savePersistentFileFromUri(
                accountKey = accountKey,
                sourceUri = sourceUri,
                filename = UUID.randomUUID().toString() + "_" + filename,
                onProgress = onProgress
            )
            val thumbnailLocalUri = if (type.isImageMimeType()) {
                fileStorage.saveImageThumbnailFromUri(
                    accountKey = accountKey,
                    sourceUri = sourceUri,
                    filename = "thumb_${UUID.randomUUID()}.jpg"
                )?.toString()
            } else if (type.isVideoMimeType()) {
                fileStorage.saveVideoThumbnailFromUri(
                    accountKey = accountKey,
                    sourceUri = sourceUri,
                    filename = "video_thumb_${UUID.randomUUID()}.jpg"
                )?.toString()
            } else {
                null
            }

            val resource = ResourceEntity(
                identifier = UUID.randomUUID().toString(),
                remoteId = null,
                accountKey = accountKey,
                date = Instant.now(),
                filename = filename,
                uri = localUri.toString(),
                localUri = localUri.toString(),
                mimeType = type?.toString(),
                encryptionMetadata = null,
                thumbnailLocalUri = thumbnailLocalUri,
                memoId = memoIdentifier
            )
            memoDao.insertResource(resource)
            refreshUnsyncedCount()
            ApiResponse.Success(resource)
        } catch (e: Exception) {
            ApiResponse.Failure.Exception(e)
        }
    }

    override suspend fun deleteResource(identifier: String): ApiResponse<Unit> {
        return try {
            val resource = memoDao.getResourceById(identifier, accountKey)
                ?: return ApiResponse.Failure.Exception(Exception(R.string.resource_not_found.string))

            val memoId = resource.memoId
            deleteLocalFile(resource)
            memoDao.deleteResource(resource)

            if (!memoId.isNullOrBlank()) {
                memoDao.getMemoById(memoId, accountKey)?.let { memo ->
                    memoDao.insertMemo(
                        memo.copy(
                            needsSync = true,
                            lastModified = Instant.now()
                        )
                    )
                }
            }

            resource.remoteId?.let { remoteId ->
                enqueueDeleteRemoteResource(remoteId)
            }
            if (!memoId.isNullOrBlank()) {
                enqueuePushMemo(memoId)
            }
            refreshUnsyncedCount()
            ApiResponse.Success(Unit)
        } catch (e: Exception) {
            ApiResponse.Failure.Exception(e)
        }
    }

    override suspend fun resolveRemoteResourceIds(
        resourceIdentifiers: List<String>,
        encryptionScope: ResourceEncryptionScope
    ): ApiResponse<List<String>> {
        return try {
            val uploaded = ensureUploadedResourcesByIdentifiers(
                resourceIdentifiers = resourceIdentifiers,
                memoRemoteId = null,
                encryptionScope = encryptionScope,
            )
            if (uploaded.failedUploads > 0) {
                ApiResponse.Failure.Exception(
                    IllegalStateException(
                        pendingDetailedSyncError ?: ATTACHMENT_UPLOAD_FAILED_MESSAGE
                    )
                )
            } else {
                ApiResponse.Success(uploaded.remoteResourceIds)
            }
        } catch (e: Exception) {
            ApiResponse.Failure.Exception(e)
        }
    }

    override suspend fun cacheResourceFile(identifier: String, downloadedUri: Uri): ApiResponse<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val resource = memoDao.getResourceById(identifier, accountKey)
                    ?: return@withContext ApiResponse.Failure.Exception(Exception(R.string.resource_not_found.string))
                val existingLocal = existingLocalUri(resource)
                if (existingLocal != null) {
                    return@withContext ApiResponse.Success(Unit)
                }

                val canonical = fileStorage.saveFileFromUri(
                    accountKey = accountKey,
                    sourceUri = downloadedUri,
                    filename = "${resource.identifier}_${resource.filename}"
                ).toString()

                resource.localUri?.takeIf { it != canonical }?.let { oldLocal ->
                    val oldUri = oldLocal.toUri()
                    if (oldUri.scheme == "file") {
                        fileStorage.deleteFile(oldUri)
                    }
                }

                val updatedUri = if (resource.remoteId == null && resource.uri.toUri().scheme == "file") {
                    canonical
                } else {
                    resource.uri
                }
                memoDao.insertResource(
                    resource.copy(
                        uri = updatedUri,
                        localUri = canonical
                    )
                )
                ApiResponse.Success(Unit)
            } catch (e: Exception) {
                ApiResponse.Failure.Exception(e)
            }
        }

    override suspend fun cacheResourceThumbnail(identifier: String, downloadedUri: Uri): ApiResponse<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val resource = memoDao.getResourceById(identifier, accountKey)
                    ?: return@withContext ApiResponse.Failure.Exception(Exception(R.string.resource_not_found.string))
                val existingLocal = existingThumbnailLocalUri(resource)
                if (existingLocal != null) {
                    return@withContext ApiResponse.Success(Unit)
                }

                val canonical = fileStorage.saveThumbnailFromUri(
                    accountKey = accountKey,
                    sourceUri = downloadedUri,
                    filename = buildCachedThumbnailFilename(resource)
                ).toString()

                resource.thumbnailLocalUri?.takeIf { it != canonical }?.let { oldLocal ->
                    val oldUri = oldLocal.toUri()
                    if (oldUri.scheme == "file") {
                        fileStorage.deleteFile(oldUri)
                    }
                }

                memoDao.insertResource(
                    resource.copy(
                        thumbnailLocalUri = canonical
                    )
                )
                ApiResponse.Success(Unit)
            } catch (e: Exception) {
                ApiResponse.Failure.Exception(e)
            }
        }

    override suspend fun getResourceById(identifier: String): ResourceEntity? = withContext(Dispatchers.IO) {
        memoDao.getResourceById(identifier, accountKey)
            ?: memoDao.getResourceByRemoteId(identifier, accountKey)
    }

    override suspend fun updateResourceThumbnail(identifier: String, thumbnailUri: String): ApiResponse<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val resource = memoDao.getResourceById(identifier, accountKey)
                    ?: memoDao.getResourceByRemoteId(identifier, accountKey)
                    ?: return@withContext ApiResponse.Failure.Exception(Exception(R.string.resource_not_found.string))
                logThumbnailUploadTrace(
                    resourceIdentifier = identifier,
                    resource = resource,
                    stage = "update_local_thumb_start",
                )

                val normalizedThumbnailUri = thumbnailUri.trim()
                if (normalizedThumbnailUri.isEmpty()) {
                    logThumbnailUploadTrace(
                        resourceIdentifier = identifier,
                        resource = resource,
                        stage = "update_local_thumb_failed",
                        detail = "reason=empty_thumbnail_uri",
                    )
                    return@withContext ApiResponse.Failure.Exception(Exception("thumbnail uri is empty"))
                }

                if (resource.thumbnailLocalUri != normalizedThumbnailUri) {
                    resource.thumbnailLocalUri?.let { oldLocal ->
                        val oldUri = oldLocal.toUri()
                        if (oldUri.scheme == "file") {
                            fileStorage.deleteFile(oldUri)
                        }
                    }
                }

                val updated = resource.copy(thumbnailLocalUri = normalizedThumbnailUri)
                memoDao.insertResource(updated)
                logThumbnailUploadTrace(
                    resourceIdentifier = identifier,
                    resource = updated,
                    stage = "update_local_thumb_saved",
                )

                if (!updated.remoteId.isNullOrBlank()) {
                    logThumbnailUploadTrace(
                        resourceIdentifier = identifier,
                        resource = updated,
                        stage = "update_local_thumb_enqueue_upload",
                    )
                    enqueueResourceThumbnailUpload(updated.identifier)
                } else {
                    logThumbnailUploadTrace(
                        resourceIdentifier = identifier,
                        resource = updated,
                        stage = "update_local_thumb_skip_upload",
                        detail = "reason=no_remote_id",
                    )
                }
                ApiResponse.Success(Unit)
            } catch (e: Exception) {
                logThumbnailUploadTrace(
                    resourceIdentifier = identifier,
                    stage = "update_local_thumb_exception",
                    detail = "error=${e.message ?: e::class.simpleName ?: "unknown"}",
                )
                ApiResponse.Failure.Exception(e)
            }
        }

    override suspend fun getCurrentUser(): ApiResponse<User> {
        return when (val refresh = refreshCurrentUserFromRemoteIfNeeded()) {
            is ApiResponse.Success -> ApiResponse.Success(currentUser)
            is ApiResponse.Failure.Error -> ApiResponse.Failure.Error(refresh.payload)
            is ApiResponse.Failure.Exception -> {
                if (refresh.throwable == KeerException.accessTokenInvalid) {
                    ApiResponse.Failure.Exception(KeerException.notLogin)
                } else {
                    ApiResponse.Failure.Exception(refresh.throwable)
                }
            }
        }
    }

    override suspend fun sync(): ApiResponse<Unit> {
        return withContext(Dispatchers.IO) {
            operationMutex.withLock {
            setSyncing(true)
            pendingDetailedSyncError = null
            try {
                val result = syncInternal()
                if (result is ApiResponse.Success) {
                    setSyncError(null)
                } else {
                    setSyncError(result.getErrorMessage())
                }
                refreshUnsyncedCount()
                result
            } catch (e: Throwable) {
                val failure = ApiResponse.Failure.Exception(e)
                setSyncError(failure.getErrorMessage())
                refreshUnsyncedCount()
                failure
            } finally {
                setSyncing(false)
            }
            }
        }
    }

    private suspend fun syncInternal(): ApiResponse<Unit> {
        val initialCursor = readMemoSyncCursor()?.trim().orEmpty().ifEmpty { "0" }
        var nextSyncCursor = initialCursor
        val isInitialPull = initialCursor == "0"
        val seenRemoteIDs = linkedSetOf<String>()
        val deletedRemoteIDs = linkedSetOf<String>()
        var hasMore = true
        var pageCount = 0

        while (hasMore && pageCount < MAX_PULL_SYNC_PAGES_PER_SESSION) {
            val pageCursor = nextSyncCursor
            val syncPullResponse = remoteRepository.pullSync(
                cursor = pageCursor,
                domains = setOf(SyncPullDomain.MEMOS),
                limit = PULL_SYNC_MEMO_PAGE_SIZE,
            )
            when (syncPullResponse) {
                is ApiResponse.Success -> {
                    val pageRemoteByID = linkedMapOf<String, Memo>()
                    syncPullResponse.data.patches.memos.upserts.forEach { memo ->
                        val remoteId = remoteMemoId(memo).trim()
                        if (remoteId.isNotEmpty()) {
                            pageRemoteByID[remoteId] = memo
                        }
                    }
                    val pageDeletedRemoteIDs = linkedSetOf<String>()
                    syncPullResponse.data.patches.memos.deletes.forEach { rawIdentifier ->
                        normalizeDeletedMemoRemoteID(rawIdentifier)
                            .takeIf { remoteID -> remoteID.isNotEmpty() }
                            ?.let { remoteID -> pageDeletedRemoteIDs += remoteID }
                    }

                    seenRemoteIDs += pageRemoteByID.keys
                    deletedRemoteIDs += pageDeletedRemoteIDs

                    val pageApplyResult = applyPulledMemoPage(
                        remoteMemos = pageRemoteByID.values.toList(),
                        remoteDeletedIDs = pageDeletedRemoteIDs,
                    )
                    if (pageApplyResult !is ApiResponse.Success) {
                        return pageApplyResult
                    }

                    val nextCursor = syncPullResponse.data.nextCursor.trim()
                    val stalledCursor = nextCursor.isEmpty() || nextCursor == pageCursor
                    if (!stalledCursor) {
                        nextSyncCursor = nextCursor
                        try {
                            writeMemoSyncCursor(nextSyncCursor)
                        } catch (e: Throwable) {
                            return ApiResponse.Failure.Exception(e)
                        }
                    }
                    hasMore = syncPullResponse.data.hasMore && !stalledCursor
                }
                is ApiResponse.Failure.Error -> {
                    return syncPullResponse.mapFailureToUnit()
                }
                is ApiResponse.Failure.Exception -> {
                    return syncPullResponse.mapFailureToUnit()
                }
            }
            pageCount += 1
        }

        if (
            !isInitialPull &&
            seenRemoteIDs.isEmpty() &&
            deletedRemoteIDs.isEmpty() &&
            memoDao.countUnsyncedMemos(accountKey) <= 0
        ) {
            return ApiResponse.Success(Unit)
        }

        val reconcileResult = reconcileLocalAfterPull(
            seenRemoteIDs = seenRemoteIDs,
            deletedRemoteIDs = deletedRemoteIDs,
            treatMissingRemoteAsDeleted = isInitialPull,
        )
        if (reconcileResult !is ApiResponse.Success) {
            return reconcileResult
        }
        return ApiResponse.Success(Unit)
    }

    private suspend fun applyPulledMemoPage(
        remoteMemos: List<Memo>,
        remoteDeletedIDs: Set<String>,
    ): ApiResponse<Unit> {
        if (remoteMemos.isEmpty() && remoteDeletedIDs.isEmpty()) {
            return ApiResponse.Success(Unit)
        }
        var hadErrors = false
        var firstErrorMessage: String? = null
        fun recordFailure() {
            hadErrors = true
            if (firstErrorMessage == null) {
                firstErrorMessage = consumeDetailedSyncError()
            }
        }
        val localMemos = memoDao.getAllMemosForSync(accountKey)
        val localByRemoteId = localMemos.mapNotNull { memo ->
            memo.remoteId?.let { it to memo }
        }.toMap()
        val pendingRemoteAppliesByRemoteId = linkedMapOf<String, PendingRemoteApply>()
        val pendingMarkSyncedByLocalIdentifier = linkedMapOf<String, PendingMarkSynced>()
        val pendingDeleteMemoIdentifiers = linkedSetOf<String>()

        fun queueRemoteApply(remoteMemo: Memo, preferredLocalIdentifier: String? = null) {
            pendingRemoteAppliesByRemoteId[remoteMemoId(remoteMemo)] =
                PendingRemoteApply(
                    remoteMemo = remoteMemo,
                    preferredLocalIdentifier = preferredLocalIdentifier,
                )
        }

        fun queueMarkSynced(local: MemoEntity, remoteMemo: Memo) {
            pendingMarkSyncedByLocalIdentifier[local.identifier] =
                PendingMarkSynced(
                    local = local,
                    remoteMemo = remoteMemo,
                )
        }

        for (remoteMemo in remoteMemos) {
            val remoteId = remoteMemoId(remoteMemo)
            val local = localByRemoteId[remoteId]

            if (local == null) {
                queueRemoteApply(remoteMemo)
                continue
            }

            if (local.isDeleted) {
                if (!local.needsSync) {
                    queueRemoteApply(remoteMemo, local.identifier)
                    continue
                }

                val remoteChanged = hasRemoteChanged(local, remoteMemo)
                val equivalent = memoEquivalent(local, remoteMemo)
                if (remoteChanged || !equivalent) {
                    queueRemoteApply(remoteMemo, local.identifier)
                } else {
                    val deleted = remoteRepository.deleteMemo(remoteId)
                    if (deleted is ApiResponse.Success) {
                        pendingDeleteMemoIdentifiers += local.identifier
                    } else {
                        recordFailure()
                    }
                }
                continue
            }

            val equivalent = memoEquivalent(local, remoteMemo)
            if (equivalent) {
                val remoteChanged = hasRemoteChanged(local, remoteMemo)
                if (local.needsSync || local.remoteId == null || remoteChanged) {
                    queueMarkSynced(local, remoteMemo)
                }
                continue
            }

            val localChanged = local.needsSync
            val remoteChanged = hasRemoteChanged(local, remoteMemo)

            when {
                !localChanged -> queueRemoteApply(remoteMemo, local.identifier)
                !remoteChanged -> {
                    if (!pushLocalMemo(local.identifier)) {
                        recordFailure()
                    }
                }
                else -> {
                    if (!duplicateConflict(local, remoteMemo)) {
                        recordFailure()
                    }
                }
            }
        }

        for (deletedRemoteID in remoteDeletedIDs) {
            val local = localByRemoteId[deletedRemoteID] ?: continue
            when {
                local.isDeleted -> pendingDeleteMemoIdentifiers += local.identifier
                local.needsSync -> {
                    if (!pushLocalMemo(local.identifier, forceCreate = true)) {
                        recordFailure()
                    }
                }
                else -> pendingDeleteMemoIdentifiers += local.identifier
            }
        }

        remoteDeletedIDs.forEach { deletedRemoteId ->
            pendingRemoteAppliesByRemoteId.remove(deletedRemoteId)
        }
        pendingMarkSyncedByLocalIdentifier.entries.removeAll { entry ->
            entry.value.local.remoteId?.let(remoteDeletedIDs::contains) == true
        }
        if (
            pendingRemoteAppliesByRemoteId.isNotEmpty() ||
            pendingMarkSyncedByLocalIdentifier.isNotEmpty() ||
            pendingDeleteMemoIdentifiers.isNotEmpty()
        ) {
            applySyncMemoMutationsBatch(
                remoteApplies = pendingRemoteAppliesByRemoteId.values.toList(),
                markSyncedEntries = pendingMarkSyncedByLocalIdentifier.values.toList(),
                deleteMemoIdentifiers = pendingDeleteMemoIdentifiers,
            )
        }

        return if (hadErrors) {
            ApiResponse.Failure.Exception(
                Exception(firstErrorMessage ?: "Sync finished with partial failures")
            )
        } else {
            ApiResponse.Success(Unit)
        }
    }

    private suspend fun reconcileLocalAfterPull(
        seenRemoteIDs: Set<String>,
        deletedRemoteIDs: Set<String>,
        treatMissingRemoteAsDeleted: Boolean,
    ): ApiResponse<Unit> {
        var hadErrors = false
        var firstErrorMessage: String? = null
        fun recordFailure() {
            hadErrors = true
            if (firstErrorMessage == null) {
                firstErrorMessage = consumeDetailedSyncError()
            }
        }

        val latestLocals = memoDao.getAllMemosForSync(accountKey)
        val pendingFinalDeleteMemoIdentifiers = linkedSetOf<String>()
        for (local in latestLocals) {
            val localRemoteID = local.remoteId
            if (localRemoteID != null && seenRemoteIDs.contains(localRemoteID)) {
                continue
            }

            if (localRemoteID != null && deletedRemoteIDs.contains(localRemoteID)) {
                if (local.isDeleted) {
                    pendingFinalDeleteMemoIdentifiers += local.identifier
                } else if (local.needsSync) {
                    if (!pushLocalMemo(local.identifier, forceCreate = true)) {
                        recordFailure()
                    }
                } else {
                    pendingFinalDeleteMemoIdentifiers += local.identifier
                }
                continue
            }

            if (localRemoteID != null && !seenRemoteIDs.contains(localRemoteID)) {
                if (treatMissingRemoteAsDeleted) {
                    if (local.isDeleted) {
                        pendingFinalDeleteMemoIdentifiers += local.identifier
                    } else if (local.needsSync) {
                        if (!pushLocalMemo(local.identifier, forceCreate = true)) {
                            recordFailure()
                        }
                    } else {
                        pendingFinalDeleteMemoIdentifiers += local.identifier
                    }
                } else {
                    if (local.needsSync) {
                        if (!pushLocalMemo(local.identifier)) {
                            recordFailure()
                        }
                    }
                }
                continue
            }

            if (localRemoteID == null) {
                if (local.isDeleted) {
                    pendingFinalDeleteMemoIdentifiers += local.identifier
                } else if (local.needsSync) {
                    if (!pushLocalMemo(local.identifier, forceCreate = true)) {
                        recordFailure()
                    }
                }
            }
        }

        if (pendingFinalDeleteMemoIdentifiers.isNotEmpty()) {
            permanentlyDeleteMemosBatch(pendingFinalDeleteMemoIdentifiers)
        }

        return if (hadErrors) {
            ApiResponse.Failure.Exception(
                Exception(firstErrorMessage ?: "Sync finished with partial failures")
            )
        } else {
            ApiResponse.Success(Unit)
        }
    }

    private suspend fun applySyncMemoMutationsBatch(
        remoteApplies: List<PendingRemoteApply>,
        markSyncedEntries: List<PendingMarkSynced>,
        deleteMemoIdentifiers: Collection<String>,
    ) {
        if (
            remoteApplies.isEmpty() &&
            markSyncedEntries.isEmpty() &&
            deleteMemoIdentifiers.isEmpty()
        ) {
            return
        }
        val deleteChunks = syncApplyPipeline.split(deleteMemoIdentifiers.toList())
        for (chunk in deleteChunks) {
            val staleResources = mutableListOf<ResourceEntity>()
            database.withTransaction {
                chunk.forEach { identifier ->
                    permanentlyDeleteMemoInTransaction(identifier, staleResources)
                }
                memoDao.pruneUnusedTags(accountKey)
            }
            staleResources.forEach(::deleteLocalFile)
        }

        val applyChunks = syncApplyPipeline.split(remoteApplies)
        for (chunk in applyChunks) {
            val staleResources = mutableListOf<ResourceEntity>()
            database.withTransaction {
                chunk.forEach { pending ->
                    applyRemoteMemoInTransaction(
                        remoteMemo = pending.remoteMemo,
                        preferredLocalIdentifier = pending.preferredLocalIdentifier,
                        staleResources = staleResources,
                    )
                }
                memoDao.pruneUnusedTags(accountKey)
            }
            staleResources.forEach(::deleteLocalFile)
        }

        val markChunks = syncApplyPipeline.split(markSyncedEntries)
        for (chunk in markChunks) {
            database.withTransaction {
                chunk.forEach { pending ->
                    markSyncedInTransaction(
                        local = pending.local,
                        remoteMemo = pending.remoteMemo,
                    )
                }
                memoDao.pruneUnusedTags(accountKey)
            }
        }
    }

    private suspend fun refreshCurrentUserFromRemoteIfNeeded(): ApiResponse<Unit> {
        val now = System.currentTimeMillis()
        if (
            lastCurrentUserRefreshAtMillis > 0L &&
            now - lastCurrentUserRefreshAtMillis < CURRENT_USER_REFRESH_INTERVAL_MILLIS
        ) {
            return ApiResponse.Success(Unit)
        }

        val remoteUser = try {
            remoteRepository.getCurrentUser()
        } catch (e: Throwable) {
            return ApiResponse.Failure.Exception(e)
        }

        return when (remoteUser) {
            is ApiResponse.Success -> {
                currentUser = remoteUser.data
                lastCurrentUserRefreshAtMillis = now
                try {
                    onUserSynced(remoteUser.data)
                    ApiResponse.Success(Unit)
                } catch (e: Throwable) {
                    ApiResponse.Failure.Exception(e)
                }
            }
            is ApiResponse.Failure.Error -> {
                if (remoteUser.statusCode == StatusCode.Forbidden || remoteUser.statusCode == StatusCode.Unauthorized) {
                    ApiResponse.Failure.Exception(KeerException.accessTokenInvalid)
                } else {
                    remoteUser.mapFailureToUnit()
                }
            }
            is ApiResponse.Failure.Exception -> remoteUser.mapFailureToUnit()
        }
    }

    private suspend fun pushLocalMemo(identifier: String, forceCreate: Boolean = false): Boolean {
        pendingDetailedSyncError = null
        val local = memoDao.getMemoById(identifier, accountKey) ?: return true
        val localTags = memoDao.getMemoTags(local.identifier, accountKey)

        if (local.isDeleted) {
            return if (local.remoteId != null) {
                val deleted = remoteRepository.deleteMemo(local.remoteId)
                if (deleted is ApiResponse.Success) {
                    permanentlyDeleteMemo(local.identifier)
                    true
                } else {
                    false
                }
            } else {
                permanentlyDeleteMemo(local.identifier)
                true
            }
        }

        val uploadedResources = ensureUploadedResources(local, localTags)
        if (uploadedResources.failedUploads > 0) {
            if (pendingDetailedSyncError == null) {
                pendingDetailedSyncError = ATTACHMENT_UPLOAD_FAILED_MESSAGE
            }
            return false
        }
        val remoteResourceIds = uploadedResources.remoteResourceIds

        return if (!forceCreate && local.remoteId != null) {
            val updated = remoteRepository.updateMemo(
                remoteId = local.remoteId,
                content = local.content,
                resourceRemoteIds = remoteResourceIds,
                visibility = local.visibility,
                tags = localTags,
                pinned = local.pinned,
                archived = local.archived
            )
            if (updated is ApiResponse.Success) {
                reconcileServerCreatedMemo(
                    local.identifier,
                    updated.data.copy(archived = local.archived)
                )
                true
            } else {
                false
            }
        } else {
            val created = remoteRepository.createMemo(
                content = local.content,
                visibility = local.visibility,
                resourceRemoteIds = remoteResourceIds,
                tags = localTags,
                createdAt = local.date,
                latitude = local.latitude,
                longitude = local.longitude
            )
            if (created !is ApiResponse.Success) {
                return false
            }

            val createdRemoteId = remoteMemoId(created.data)

            reconcileServerCreatedMemo(
                local.identifier,
                created.data.copy(
                    remoteId = createdRemoteId,
                )
            )
            true
        }
    }

    private suspend fun duplicateConflict(local: MemoEntity, remoteMemo: Memo): Boolean {
        val localTags = memoDao.getMemoTags(local.identifier, accountKey)
        val duplicateLocal = local.copy(
            identifier = UUID.randomUUID().toString(),
            remoteId = null,
            needsSync = true,
            isDeleted = false,
            lastSyncedAt = null,
            lastModified = Instant.now()
        )

        memoDao.insertMemo(duplicateLocal)
        memoDao.replaceMemoTags(
            memoId = duplicateLocal.identifier,
            accountKey = accountKey,
            tags = localTags,
        )
        memoDao.getMemoResources(local.identifier, accountKey).forEach { resource ->
            memoDao.insertResource(
                resource.copy(
                    identifier = UUID.randomUUID().toString(),
                    memoId = duplicateLocal.identifier
                )
            )
        }

        applyRemoteMemo(remoteMemo, local.identifier)
        return pushLocalMemo(duplicateLocal.identifier, forceCreate = true)
    }

    private suspend fun reconcileServerCreatedMemo(localIdentifier: String, remoteMemo: Memo) {
        applyRemoteMemo(remoteMemo, preferredLocalIdentifier = localIdentifier)
    }

    private suspend fun ensureUploadedResources(
        localMemo: MemoEntity,
        localTags: List<String>,
    ): UploadedResourcesResult {
        val resources = memoDao.getMemoResources(localMemo.identifier, accountKey)
        return ensureUploadedResourceEntities(
            resources = resources,
            memoRemoteId = localMemo.remoteId,
            encryptionScope = memoResourceEncryptionScope(localTags),
        )
    }

    private suspend fun ensureUploadedResourcesByIdentifiers(
        resourceIdentifiers: List<String>,
        memoRemoteId: String?,
        encryptionScope: ResourceEncryptionScope,
    ): UploadedResourcesResult {
        val resources = resourceIdentifiers.mapNotNull { identifier ->
            memoDao.getResourceById(identifier, accountKey)
                ?: memoDao.getResourceByRemoteId(identifier, accountKey)
                ?: identifier.trim().takeIf { it.isNotEmpty() }?.let { remoteIdentifier ->
                    ResourceEntity(
                        identifier = remoteIdentifier,
                        remoteId = remoteIdentifier,
                        accountKey = accountKey,
                        date = Instant.EPOCH,
                        filename = remoteIdentifier,
                        uri = remoteIdentifier,
                        mimeType = null,
                    )
                }
        }
        return ensureUploadedResourceEntities(
            resources = resources,
            memoRemoteId = memoRemoteId,
            encryptionScope = encryptionScope,
        )
    }

    private suspend fun ensureUploadedResourceEntities(
        resources: List<ResourceEntity>,
        memoRemoteId: String?,
        encryptionScope: ResourceEncryptionScope,
    ): UploadedResourcesResult {
        val uploaded = arrayListOf<String>()
        var failedUploads = 0
        val pendingUploads = linkedMapOf<String, PendingUpload>()
        resources.filter { it.remoteId == null }.forEach { resource ->
            val file = prepareUploadFile(resource)
            pendingUploads[resource.identifier] = PendingUpload(
                resource = resource,
                file = file,
                sizeBytes = file?.length()?.takeIf { it > 0L } ?: 0L
            )
        }

        setUploadProgressTotals(
            totalBytes = pendingUploads.values.sumOf { it.sizeBytes },
            totalFiles = pendingUploads.size
        )

        var committedBytes = 0L
        var completedFiles = 0

        for (resource in resources) {
            if (resource.remoteId != null) {
                uploaded.add(resource.remoteId)
                continue
            }

            val pending = pendingUploads[resource.identifier]
            val fileSize = pending?.sizeBytes ?: 0L
            val ensured = ensureUploadedResource(
                resource = resource,
                memoRemoteId = memoRemoteId,
                encryptionScope = encryptionScope,
                localFile = pending?.file
            ) { uploadedBytes, _ ->
                val uploadedForCurrentFile = if (fileSize > 0L) {
                    uploadedBytes.coerceIn(0L, fileSize)
                } else {
                    0L
                }
                updateUploadProgress(
                    uploadedBytes = committedBytes + uploadedForCurrentFile,
                    uploadedFiles = completedFiles
                )
            }
            if (ensured?.remoteId != null) {
                uploaded.add(ensured.remoteId)
            } else {
                failedUploads += 1
            }

            committedBytes += fileSize
            completedFiles += 1
            updateUploadProgress(
                uploadedBytes = committedBytes,
                uploadedFiles = completedFiles
            )
        }

        return UploadedResourcesResult(uploaded.distinct(), failedUploads)
    }

    private suspend fun ensureUploadedResource(
        resource: ResourceEntity,
        memoRemoteId: String?,
        encryptionScope: ResourceEncryptionScope,
        localFile: File? = null,
        onProgress: (uploadedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> }
    ): ResourceEntity? {
        if (resource.remoteId != null) {
            return resource
        }

        val file = localFile ?: prepareUploadFile(resource)
        if (file == null || !file.exists()) {
            pendingDetailedSyncError = "Attachment file missing locally: ${resource.filename}"
            return null
        }
        if (file.length() <= 0L) {
            pendingDetailedSyncError = "Attachment file is empty: ${resource.filename}"
            return null
        }

        val uploaded = remoteRepository.createResource(
            filename = resource.filename,
            type = resource.mimeType?.toMediaTypeOrNull(),
            file = file,
            memoRemoteId = memoRemoteId,
            encryptionScope = encryptionScope,
            thumbnail = buildUploadThumbnail(resource),
            onProgress = onProgress
        )

        val remoteResource = uploaded.getOrNull() ?: run {
            val uploadErrorDetail = when (uploaded) {
                is ApiResponse.Failure.Exception -> {
                    val throwable = uploaded.throwable
                    val reason = throwable.localizedMessage?.trim().orEmpty()
                    if (reason.isNotEmpty()) {
                        reason
                    } else {
                        throwable::class.simpleName ?: "Unknown exception"
                    }
                }
                is ApiResponse.Failure.Error -> {
                    val reason = uploaded.getErrorMessage().trim()
                    if (reason.isNotEmpty()) {
                        reason
                    } else {
                        "HTTP ${uploaded.statusCode}"
                    }
                }
                else -> "Unknown upload failure"
            }
            pendingDetailedSyncError =
                "Upload attachment failed (${resource.filename}): $uploadErrorDetail"
            return null
        }
        val cachedLocalUri = moveLocalFileToCache(resource, file)
        val synced = resource.copy(
            remoteId = remoteResourceId(remoteResource),
            uri = remoteResource.uri,
            localUri = cachedLocalUri ?: resource.localUri ?: resource.uri,
            mimeType = remoteResource.mimeType,
            encryptionMetadata = remoteResource.encryptionMetadata,
            thumbnailUri = remoteResource.thumbnailUri ?: resource.thumbnailUri
        )
        memoDao.insertResource(synced)
        return synced
    }

    private suspend fun applyRemoteMemo(
        remoteMemo: Memo,
        preferredLocalIdentifier: String? = null
    ) {
        val staleResources = mutableListOf<ResourceEntity>()
        database.withTransaction {
            applyRemoteMemoInTransaction(
                remoteMemo = remoteMemo,
                preferredLocalIdentifier = preferredLocalIdentifier,
                staleResources = staleResources,
            )
        }
        staleResources.forEach(::deleteLocalFile)
    }

    private suspend fun applyRemoteMemoInTransaction(
        remoteMemo: Memo,
        preferredLocalIdentifier: String?,
        staleResources: MutableList<ResourceEntity>,
    ) {
        val remoteId = remoteMemoId(remoteMemo)
        val current = memoDao.getMemoByRemoteId(remoteId, accountKey)
            ?: preferredLocalIdentifier?.let { memoDao.getMemoById(it, accountKey) }

        val localIdentifier = current?.identifier ?: UUID.randomUUID().toString()
        val remoteUpdatedAt = remoteMemo.updatedAt ?: remoteMemo.date
        if (current != null) {
            val unchanged = !current.needsSync &&
                !current.isDeleted &&
                current.remoteId == remoteId &&
                current.content == remoteMemo.content &&
                current.visibility == remoteMemo.visibility &&
                current.pinned == remoteMemo.pinned &&
                current.archived == remoteMemo.archived &&
                current.latitude == remoteMemo.latitude &&
                current.longitude == remoteMemo.longitude &&
                current.quoteSourceKind == remoteMemo.quoteSourceKind &&
                current.quoteSource == remoteMemo.quoteSource &&
                current.quoteStatus == remoteMemo.quoteStatus &&
                current.quoteContentPreview == remoteMemo.quoteContentPreview &&
                current.quoteDate == remoteMemo.quoteDate &&
                current.quoteHasAttachments == remoteMemo.quoteHasAttachments &&
                current.lastSyncedAt == remoteUpdatedAt &&
                memoEquivalent(current, remoteMemo)
            if (unchanged) {
                return
            }
        }

        memoDao.insertMemo(
            MemoEntity(
                identifier = localIdentifier,
                remoteId = remoteId,
                accountKey = accountKey,
                content = remoteMemo.content,
                date = remoteMemo.date,
                visibility = remoteMemo.visibility,
                pinned = remoteMemo.pinned,
                archived = remoteMemo.archived,
                latitude = remoteMemo.latitude ?: current?.latitude,
                longitude = remoteMemo.longitude ?: current?.longitude,
                quoteSourceKind = remoteMemo.quoteSourceKind,
                quoteSource = remoteMemo.quoteSource,
                quoteStatus = remoteMemo.quoteStatus,
                quoteContentPreview = remoteMemo.quoteContentPreview,
                quoteDate = remoteMemo.quoteDate,
                quoteHasAttachments = remoteMemo.quoteHasAttachments,
                needsSync = false,
                isDeleted = false,
                lastModified = remoteUpdatedAt,
                lastSyncedAt = remoteUpdatedAt
            )
        )
        memoDao.replaceMemoTags(
            memoId = localIdentifier,
            accountKey = accountKey,
            tags = remoteMemo.tags,
        )

        val currentResources = memoDao.getMemoResources(localIdentifier, accountKey)
        val remoteResourceIds = remoteMemo.resources.mapTo(hashSetOf()) { remoteResourceId(it) }
        currentResources.forEach { currentResource ->
            if (currentResource.remoteId !in remoteResourceIds) {
                staleResources += currentResource
                memoDao.deleteResource(currentResource)
            }
        }

        remoteMemo.resources.forEach { resource ->
            val remoteResourceId = remoteResourceId(resource)
            val existing = currentResources.firstOrNull { it.remoteId == remoteResourceId }
            val localResourceIdentifier = existing?.identifier ?: UUID.randomUUID().toString()
            val preferredLocalUri = when {
                existing?.localUri != null && File(existing.localUri.toUri().path ?: "").exists() -> existing.localUri
                existing != null && existing.uri.toUri().scheme == "file" && File(existing.uri.toUri().path ?: "").exists() -> existing.uri
                else -> null
            }
            val preferredThumbnailLocalUri = existing?.thumbnailLocalUri
                ?.takeIf { local ->
                    val localUri = local.toUri()
                    localUri.scheme == "file" && File(localUri.path ?: "").exists()
                }
            memoDao.insertResource(
                ResourceEntity(
                    identifier = localResourceIdentifier,
                    remoteId = remoteResourceId,
                    accountKey = accountKey,
                    date = resource.date,
                    filename = resource.filename,
                    uri = resource.uri,
                    localUri = preferredLocalUri,
                    mimeType = resource.mimeType,
                    encryptionMetadata = resource.encryptionMetadata,
                    thumbnailUri = resource.thumbnailUri,
                    thumbnailLocalUri = preferredThumbnailLocalUri,
                    memoId = localIdentifier
                )
            )
        }
    }

    private suspend fun markSynced(local: MemoEntity, remoteMemo: Memo) {
        database.withTransaction {
            markSyncedInTransaction(local, remoteMemo)
        }
    }

    private suspend fun markSyncedInTransaction(local: MemoEntity, remoteMemo: Memo) {
        val resolvedRemoteUpdatedAt = remoteMemo.updatedAt ?: remoteMemo.date
        if (
            !local.needsSync &&
            !local.isDeleted &&
            local.remoteId == remoteMemoId(remoteMemo) &&
            local.archived == remoteMemo.archived &&
            local.quoteSourceKind == remoteMemo.quoteSourceKind &&
            local.quoteSource == remoteMemo.quoteSource &&
            local.quoteStatus == remoteMemo.quoteStatus &&
            local.quoteContentPreview == remoteMemo.quoteContentPreview &&
            local.quoteDate == remoteMemo.quoteDate &&
            local.quoteHasAttachments == remoteMemo.quoteHasAttachments &&
            local.lastSyncedAt == resolvedRemoteUpdatedAt
        ) {
            return
        }
        memoDao.insertMemo(
            local.copy(
                remoteId = remoteMemoId(remoteMemo),
                needsSync = false,
                isDeleted = false,
                archived = remoteMemo.archived,
                quoteSourceKind = remoteMemo.quoteSourceKind,
                quoteSource = remoteMemo.quoteSource,
                quoteStatus = remoteMemo.quoteStatus,
                quoteContentPreview = remoteMemo.quoteContentPreview,
                quoteDate = remoteMemo.quoteDate,
                quoteHasAttachments = remoteMemo.quoteHasAttachments,
                lastSyncedAt = resolvedRemoteUpdatedAt
            )
        )
    }

    private suspend fun memoEquivalent(local: MemoEntity, remote: Memo): Boolean {
        if (local.content != remote.content) {
            return false
        }
        if (local.pinned != remote.pinned) {
            return false
        }
        if (local.visibility != remote.visibility) {
            return false
        }
        if (local.archived != remote.archived) {
            return false
        }
        val localTags = memoDao.getMemoTags(local.identifier, accountKey)
        if (tagSignature(localTags) != tagSignature(remote.tags)) {
            return false
        }

        val localResources = memoDao.getMemoResources(local.identifier, accountKey)
        return resourceEntitySignature(localResources) == resourceModelSignature(remote.resources)
    }

    private fun hasRemoteChanged(local: MemoEntity, remote: Memo): Boolean {
        val remoteUpdatedAt = remote.updatedAt ?: remote.date
        val lastSyncedAt = local.lastSyncedAt ?: return true
        return remoteUpdatedAt != lastSyncedAt
    }

    private fun resourceEntitySignature(resources: List<ResourceEntity>): List<String> {
        return resources.map { resource ->
            resource.remoteId ?: "local:${resource.localUri ?: resource.uri}"
        }.sorted()
    }

    private fun resourceModelSignature(resources: List<Resource>): List<String> {
        return resources.map { resource ->
            resource.remoteId
        }.sorted()
    }

    private fun tagSignature(tags: List<String>): List<String> {
        return tags
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .sorted()
            .toList()
    }

    private fun memoResourceEncryptionScope(tags: List<String>): ResourceEncryptionScope {
        val collaboratorIds = extractCollaboratorIds(tags)
            .map { collaboratorId -> collaboratorId.trim() }
            .filter { collaboratorId -> collaboratorId.isNotEmpty() }
            .distinct()
        return if (collaboratorIds.isEmpty()) {
            ResourceEncryptionScope.Account
        } else {
            ResourceEncryptionScope.Collaborators(collaboratorIds)
        }
    }

    private suspend fun pushLocalResource(identifier: String): Boolean {
        pendingDetailedSyncError = null
        val local = memoDao.getResourceById(identifier, accountKey) ?: return true
        val localFile = prepareUploadFile(local)
        val totalBytes = localFile?.length()?.takeIf { it > 0L } ?: 0L
        setUploadProgressTotals(totalBytes = totalBytes, totalFiles = 1)
        val ensured = ensureUploadedResource(
            resource = local,
            memoRemoteId = null,
            encryptionScope = ResourceEncryptionScope.Account,
            localFile = localFile
        ) { uploadedBytes, _ ->
            val bounded = if (totalBytes > 0L) {
                uploadedBytes.coerceIn(0L, totalBytes)
            } else {
                0L
            }
            updateUploadProgress(
                uploadedBytes = bounded,
                uploadedFiles = 0
            )
        }
            ?: run {
                if (pendingDetailedSyncError == null) {
                    pendingDetailedSyncError = ATTACHMENT_UPLOAD_FAILED_MESSAGE
                }
                updateUploadProgress(
                    uploadedBytes = totalBytes,
                    uploadedFiles = 1
                )
                return false
            }
        updateUploadProgress(
            uploadedBytes = totalBytes,
            uploadedFiles = 1
        )
        return ensured.remoteId != null
    }

    private fun enqueuePushMemo(identifier: String, forceCreate: Boolean = false) {
        enqueueOperation("Failed to sync memo") {
            pushLocalMemo(identifier, forceCreate)
        }
    }

    private fun enqueuePushResource(identifier: String) {
        enqueueOperation("Failed to sync resource") {
            pushLocalResource(identifier)
        }
    }

    private fun enqueueDeleteRemoteResource(remoteId: String) {
        enqueueOperation("Failed to delete resource on server") {
            remoteRepository.deleteResource(remoteId) is ApiResponse.Success
        }
    }

    private fun enqueueOperation(
        defaultErrorMessage: String,
        block: suspend () -> Boolean
    ) {
        operationScope.launch {
            operationMutex.withLock {
                setSyncing(true)
                try {
                    val success = block()
                    if (success) {
                        setSyncError(null)
                    } else {
                        setSyncError(consumeDetailedSyncError() ?: defaultErrorMessage)
                    }
                } catch (e: Throwable) {
                    setSyncError(e.localizedMessage ?: defaultErrorMessage)
                } finally {
                    refreshUnsyncedCount()
                    setSyncing(false)
                }
            }
        }
    }

    private suspend fun refreshUnsyncedCount() {
        val count = memoDao.countUnsyncedMemos(accountKey)
        _syncStatus.update { it.copy(unsyncedCount = count) }
    }

    private fun setSyncing(syncing: Boolean) {
        _syncStatus.update {
            it.copy(
                syncing = syncing,
                uploadedBytes = 0L,
                totalBytes = 0L,
                uploadedFiles = 0,
                totalFiles = 0
            )
        }
    }

    private fun setUploadProgressTotals(totalBytes: Long, totalFiles: Int) {
        _syncStatus.update {
            it.copy(
                uploadedBytes = 0L,
                totalBytes = totalBytes.coerceAtLeast(0L),
                uploadedFiles = 0,
                totalFiles = totalFiles.coerceAtLeast(0)
            )
        }
    }

    private fun updateUploadProgress(uploadedBytes: Long, uploadedFiles: Int) {
        _syncStatus.update { status ->
            val safeUploadedBytes = if (status.totalBytes > 0L) {
                uploadedBytes.coerceIn(0L, status.totalBytes)
            } else {
                0L
            }
            val safeUploadedFiles = if (status.totalFiles > 0) {
                uploadedFiles.coerceIn(0, status.totalFiles)
            } else {
                0
            }
            status.copy(
                uploadedBytes = safeUploadedBytes,
                uploadedFiles = safeUploadedFiles
            )
        }
    }

    private fun setSyncError(message: String?) {
        _syncStatus.update { it.copy(errorMessage = message) }
    }

    private fun consumeDetailedSyncError(): String? {
        val message = pendingDetailedSyncError
        pendingDetailedSyncError = null
        return message
    }

    private suspend fun withResources(memo: MemoEntity): MemoEntity {
        val resources = memoDao.getMemoResources(memo.identifier, accountKey)
        val tags = memoDao.getMemoTags(memo.identifier, accountKey)
        return memo.copy().also {
            it.resources = resources
            it.tags = tags
        }
    }

    private fun toMemoEntity(item: site.lcyk.keer.data.local.entity.MemoWithResources): MemoEntity {
        return item.memo.copy().also { memo ->
            memo.resources = item.resources
            memo.tags = item.tags.map { tag -> tag.name }
        }
    }

    private suspend fun permanentlyDeleteMemo(identifier: String) {
        permanentlyDeleteMemosBatch(listOf(identifier))
    }

    private suspend fun permanentlyDeleteMemosBatch(identifiers: Collection<String>) {
        if (identifiers.isEmpty()) {
            return
        }
        val staleResources = mutableListOf<ResourceEntity>()
        database.withTransaction {
            identifiers.forEach { identifier ->
                permanentlyDeleteMemoInTransaction(identifier, staleResources)
            }
            memoDao.pruneUnusedTags(accountKey)
        }
        staleResources.forEach(::deleteLocalFile)
    }

    private suspend fun permanentlyDeleteMemoInTransaction(
        identifier: String,
        staleResources: MutableList<ResourceEntity>,
    ) {
        val memo = memoDao.getMemoById(identifier, accountKey) ?: return
        staleResources += memoDao.getMemoResources(identifier, accountKey)
        memoDao.deleteMemo(memo)
    }

    private fun resolveLocalFile(resource: ResourceEntity): File? {
        val uri = (resource.localUri ?: resource.uri).toUri()
        if (uri.scheme != "file") {
            return null
        }
        val path = uri.path ?: return null
        val file = File(path)
        return if (file.exists()) file else null
    }

    private suspend fun prepareUploadFile(resource: ResourceEntity): File? {
        val existing = resolveLocalFile(resource)
        if (existing != null && existing.length() > 0L) {
            return existing
        }

        val sourceContentUri = sequenceOf(resource.localUri, resource.uri)
            .filterNotNull()
            .map { it.toUri() }
            .firstOrNull { it.scheme == "content" }
            ?: return existing?.takeIf { it.length() > 0L }

        return try {
            val persistedUri = fileStorage.savePersistentFileFromUri(
                accountKey = accountKey,
                sourceUri = sourceContentUri,
                filename = "${resource.identifier}_${resource.filename}"
            )
            val persistedPath = persistedUri.path
            val persistedFile = persistedPath?.let(::File)
                ?.takeIf { it.exists() && it.length() > 0L }
                ?: return null

            val updatedUri = if (resource.remoteId == null && resource.uri.toUri().scheme == "content") {
                persistedUri.toString()
            } else {
                resource.uri
            }
            memoDao.insertResource(
                resource.copy(
                    uri = updatedUri,
                    localUri = persistedUri.toString()
                )
            )
            persistedFile
        } catch (e: Exception) {
            Timber.w(e, "Failed to persist resource file for: %s", resource.filename)
            existing?.takeIf { it.length() > 0L }
        }
    }

    private fun existingLocalUri(resource: ResourceEntity): Uri? {
        val local = resource.localUri ?: return null
        val uri = local.toUri()
        return if (uri.scheme == "file" && File(uri.path ?: "").exists()) uri else null
    }

    private fun existingThumbnailLocalUri(resource: ResourceEntity): Uri? {
        val local = resource.thumbnailLocalUri ?: return null
        val uri = local.toUri()
        val file = if (uri.scheme == "file") File(uri.path ?: "") else null
        return if (file != null && file.exists() && !isLegacyVideoNamedThumbnail(file)) uri else null
    }

    private fun buildCachedThumbnailFilename(resource: ResourceEntity): String {
        val sanitizedIdentifier = resource.identifier.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return "thumb_${sanitizedIdentifier}.jpg"
    }

    private fun isLegacyVideoNamedThumbnail(file: File): Boolean {
        return file.extension.lowercase() in setOf("mp4", "mov", "m4v", "webm", "mkv", "avi", "3gp", "mpeg", "mpg")
    }

    private suspend fun notifyMemoRelationsChanged(memo: MemoEntity) {
        memoDao.insertMemo(memo.copy())
    }

    private fun buildUploadThumbnail(resource: ResourceEntity): ResourceUploadThumbnail? {
        val mime = resource.mimeType?.lowercase().orEmpty()
        if (!mime.startsWith("video/") && !mime.startsWith("image/")) {
            return null
        }
        val thumbnailFile = existingThumbnailLocalUri(resource)?.path?.let(::File) ?: return null
        val thumbnailBytes = try {
            thumbnailFile.readBytes()
        } catch (e: Exception) {
            Timber.w(e, "Failed to read thumbnail file: %s", thumbnailFile.path)
            return null
        }
        if (thumbnailBytes.isEmpty() || thumbnailBytes.size > MAX_UPLOADED_THUMBNAIL_BYTES) {
            return null
        }
        val thumbnailFilenamePrefix = if (mime.startsWith("video/")) "video_thumb_" else "image_thumb_"
        return ResourceUploadThumbnail(
            filename = "${thumbnailFilenamePrefix}${resource.identifier}.jpg",
            type = "image/jpeg",
            content = Base64.getEncoder().encodeToString(thumbnailBytes)
        )
    }

    private fun logThumbnailUploadTrace(
        resourceIdentifier: String,
        resource: ResourceEntity? = null,
        stage: String,
        detail: String? = null,
    ) {
        val resolvedResourceId = resource?.identifier ?: resourceIdentifier
        val resolvedRemoteId = resource?.remoteId?.trim().orEmpty().ifEmpty { "-" }
        val suffix = detail?.trim()?.takeIf { it.isNotEmpty() }?.let { " $it" }.orEmpty()
        Timber.tag(THUMBNAIL_UPLOAD_LOG_TAG).d(
            "resource=%s remote=%s stage=%s%s",
            resolvedResourceId,
            resolvedRemoteId,
            stage,
            suffix,
        )
    }

    private fun enqueueResourceThumbnailUpload(resourceIdentifier: String) {
        if (!thumbnailUploadScheduler.enqueue(resourceIdentifier)) {
            logThumbnailUploadTrace(
                resourceIdentifier = resourceIdentifier,
                stage = "queue_deduplicated",
            )
            return
        }
        logThumbnailUploadTrace(
            resourceIdentifier = resourceIdentifier,
            stage = "queue_enqueued",
        )
        operationScope.launch {
            processResourceThumbnailUploads(resourceIdentifier)
        }
    }

    private suspend fun processResourceThumbnailUploads(resourceIdentifier: String) {
        logThumbnailUploadTrace(
            resourceIdentifier = resourceIdentifier,
            stage = "worker_start",
        )
        while (thumbnailUploadScheduler.takePending(resourceIdentifier)) {
            uploadResourceThumbnailWithRetry(resourceIdentifier)
        }
        if (thumbnailUploadScheduler.finishAndShouldRestart(resourceIdentifier)) {
            logThumbnailUploadTrace(
                resourceIdentifier = resourceIdentifier,
                stage = "worker_restart",
            )
            operationScope.launch {
                processResourceThumbnailUploads(resourceIdentifier)
            }
        } else {
            logThumbnailUploadTrace(
                resourceIdentifier = resourceIdentifier,
                stage = "worker_finish",
            )
        }
    }

    private suspend fun uploadResourceThumbnailWithRetry(resourceIdentifier: String) {
        var attempt = 1
        while (attempt <= THUMBNAIL_UPLOAD_MAX_RETRY_COUNT) {
            logThumbnailUploadTrace(
                resourceIdentifier = resourceIdentifier,
                stage = "retry_attempt",
                detail = "attempt=$attempt",
            )
            if (uploadResourceThumbnailOnce(resourceIdentifier)) {
                logThumbnailUploadTrace(
                    resourceIdentifier = resourceIdentifier,
                    stage = "retry_complete",
                    detail = "attempt=$attempt",
                )
                return
            }
            if (attempt < THUMBNAIL_UPLOAD_MAX_RETRY_COUNT) {
                val delayMillis = thumbnailUploadRetryDelayMillis(attempt)
                logThumbnailUploadTrace(
                    resourceIdentifier = resourceIdentifier,
                    stage = "retry_backoff",
                    detail = "attempt=$attempt delay_ms=$delayMillis",
                )
                delay(delayMillis)
            }
            attempt += 1
        }
        logThumbnailUploadTrace(
            resourceIdentifier = resourceIdentifier,
            stage = "retry_exhausted",
            detail = "max=$THUMBNAIL_UPLOAD_MAX_RETRY_COUNT",
        )
    }

    private suspend fun uploadResourceThumbnailOnce(resourceIdentifier: String): Boolean {
        val resource = memoDao.getResourceById(resourceIdentifier, accountKey) ?: run {
            logThumbnailUploadTrace(
                resourceIdentifier = resourceIdentifier,
                stage = "skip:no_resource",
            )
            return true
        }
        val remoteId = resource.remoteId?.trim().orEmpty()
        if (remoteId.isEmpty()) {
            logThumbnailUploadTrace(
                resourceIdentifier = resourceIdentifier,
                resource = resource,
                stage = "skip:no_remote_id",
            )
            return true
        }
        val thumbnailLocalUri = existingThumbnailLocalUri(resource) ?: run {
            logThumbnailUploadTrace(
                resourceIdentifier = resourceIdentifier,
                resource = resource,
                stage = "skip:no_local_thumb",
            )
            return true
        }
        val thumbnailFilePath = thumbnailLocalUri.path?.trim().orEmpty()
        if (thumbnailFilePath.isEmpty()) {
            logThumbnailUploadTrace(
                resourceIdentifier = resourceIdentifier,
                resource = resource,
                stage = "skip:no_local_thumb",
            )
            return true
        }
        val thumbnailFile = File(thumbnailFilePath)
        if (!thumbnailFile.exists() || !thumbnailFile.isFile || thumbnailFile.length() <= 0L) {
            logThumbnailUploadTrace(
                resourceIdentifier = resourceIdentifier,
                resource = resource,
                stage = "skip:thumb_file_missing",
                detail = "path=$thumbnailFilePath",
            )
            return true
        }

        logThumbnailUploadTrace(
            resourceIdentifier = resourceIdentifier,
            resource = resource,
            stage = "request:upload_start",
            detail = "bytes=${thumbnailFile.length()}",
        )

        return when (val response = remoteRepository.updateResourceThumbnail(
            remoteId = remoteId,
            thumbnailFile = thumbnailFile,
            encryptionMetadata = resource.encryptionMetadata,
        )) {
            is ApiResponse.Success -> {
                val remoteResource = response.data
                memoDao.insertResource(
                    resource.copy(
                        remoteId = remoteResource.remoteId.takeIf { it.isNotBlank() } ?: resource.remoteId,
                        encryptionMetadata = remoteResource.encryptionMetadata ?: resource.encryptionMetadata,
                        thumbnailUri = remoteResource.thumbnailUri ?: resource.thumbnailUri,
                        thumbnailLocalUri = resource.thumbnailLocalUri,
                    )
                )
                logThumbnailUploadTrace(
                    resourceIdentifier = resourceIdentifier,
                    resource = resource,
                    stage = "request:upload_success",
                )
                true
            }
            is ApiResponse.Failure.Error -> {
                logThumbnailUploadTrace(
                    resourceIdentifier = resourceIdentifier,
                    resource = resource,
                    stage = "request:upload_failed_http",
                    detail = "code=${response.statusCode.code}",
                )
                Timber.w(
                    "Thumbnail upload failed for resource %s: HTTP %s",
                    resource.identifier,
                    response.statusCode.code
                )
                false
            }
            is ApiResponse.Failure.Exception -> {
                logThumbnailUploadTrace(
                    resourceIdentifier = resourceIdentifier,
                    resource = resource,
                    stage = "request:upload_failed_exception",
                    detail = "error=${response.throwable.message ?: response.throwable::class.simpleName ?: "unknown"}",
                )
                Timber.w(
                    response.throwable,
                    "Thumbnail upload failed for resource %s",
                    resource.identifier
                )
                false
            }
        }
    }

    private fun thumbnailUploadRetryDelayMillis(attempt: Int): Long {
        val safeAttempt = attempt.coerceAtLeast(1)
        val factor = 1L shl (safeAttempt - 1).coerceAtMost(6)
        return THUMBNAIL_UPLOAD_BASE_RETRY_DELAY_MILLIS * factor
    }

    private fun moveLocalFileToCache(resource: ResourceEntity, sourceFile: File): String? {
        if (!sourceFile.exists()) {
            return null
        }
        return try {
            val localPath = resource.localUri ?: resource.uri
            val localUri = localPath.toUri()
            val cachedUri = fileStorage.copyFileToCache(
                accountKey = accountKey,
                sourceFile = sourceFile,
                filename = "${resource.identifier}_${resource.filename}"
            )
            if (localUri.scheme == "file" && localUri.path != cachedUri.path) {
                fileStorage.deleteFile(localUri)
            }
            cachedUri.toString()
        } catch (e: Exception) {
            Timber.w(e, "Failed to move local file to cache for resource: %s", resource.filename)
            null
        }
    }

    private fun deleteLocalFile(resource: ResourceEntity) {
        val uri = resource.localUri?.toUri()
            ?: resource.uri.toUri().takeIf { it.scheme == "file" }
        if (uri != null) {
            fileStorage.deleteFile(uri)
        }
        val thumbnailUri = resource.thumbnailLocalUri?.toUri()
        if (thumbnailUri?.scheme == "file") {
            fileStorage.deleteFile(thumbnailUri)
        }
    }

    private fun remoteMemoId(memo: Memo): String {
        return memo.remoteId.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("RemoteRepository must return memos with non-empty remoteId")
    }

    private fun normalizeDeletedMemoRemoteID(rawIdentifier: String): String {
        return rawIdentifier
            .trim()
            .substringBefore('|')
            .substringAfterLast('/')
            .trim()
    }

    private fun remoteResourceId(resource: Resource): String {
        return resource.remoteId.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("RemoteRepository must return resources with non-empty remoteId")
    }

    private fun <T> ApiResponse<T>.mapFailureToUnit(): ApiResponse<Unit> {
        return when (this) {
            is ApiResponse.Success -> ApiResponse.Success(Unit)
            is ApiResponse.Failure.Error -> ApiResponse.Failure.Error(this.payload)
            is ApiResponse.Failure.Exception -> ApiResponse.Failure.Exception(this.throwable)
        }
    }

    override fun close() {
        operationScope.cancel()
    }

    companion object {
        private const val ATTACHMENT_UPLOAD_FAILED_MESSAGE =
            "Failed to upload one or more attachments during sync"
        private const val MAX_UPLOADED_THUMBNAIL_BYTES = 2 * 1024 * 1024
        private const val CURRENT_USER_REFRESH_INTERVAL_MILLIS = 5 * 60 * 1000L
        private const val PULL_SYNC_MEMO_PAGE_SIZE = 120
        private const val MAX_PULL_SYNC_PAGES_PER_SESSION = 20
        private const val SYNC_APPLY_CHUNK_SIZE = 24
        private const val THUMBNAIL_UPLOAD_MAX_RETRY_COUNT = 3
        private const val THUMBNAIL_UPLOAD_BASE_RETRY_DELAY_MILLIS = 800L
        private const val THUMBNAIL_UPLOAD_LOG_TAG = "ThumbnailUpload"
    }

}

private fun MediaType?.isImageMimeType(): Boolean {
    return this?.type.equals("image", ignoreCase = true)
}

private fun MediaType?.isVideoMimeType(): Boolean {
    return this?.type.equals("video", ignoreCase = true)
}

private fun renameTagWithPrefix(tag: String, oldPrefix: String, newPrefix: String): String {
    return when {
        tag == oldPrefix -> newPrefix
        tag.startsWith("$oldPrefix/") -> "$newPrefix/${tag.removePrefix("$oldPrefix/")}"
        else -> tag
    }
}

private fun matchesTagOrDescendant(tag: String, rootTag: String): Boolean {
    return tag == rootTag || tag.startsWith("$rootTag/")
}

internal class ThumbnailUploadTaskScheduler {
    private val lock = Any()
    private val pending = mutableSetOf<String>()
    private val running = mutableSetOf<String>()

    fun enqueue(resourceIdentifier: String): Boolean = synchronized(lock) {
        pending += resourceIdentifier
        if (resourceIdentifier in running) {
            false
        } else {
            running += resourceIdentifier
            true
        }
    }

    fun takePending(resourceIdentifier: String): Boolean = synchronized(lock) {
        pending.remove(resourceIdentifier)
    }

    fun finishAndShouldRestart(resourceIdentifier: String): Boolean = synchronized(lock) {
        running -= resourceIdentifier
        if (resourceIdentifier in pending) {
            running += resourceIdentifier
            true
        } else {
            false
        }
    }
}
