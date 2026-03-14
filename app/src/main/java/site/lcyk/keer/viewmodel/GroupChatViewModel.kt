package site.lcyk.keer.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.skydoves.sandwich.ApiResponse
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import site.lcyk.keer.R
import site.lcyk.keer.data.model.Account
import site.lcyk.keer.data.model.CachedMemoItem
import site.lcyk.keer.data.model.Memo
import site.lcyk.keer.data.model.MemoVisibility
import site.lcyk.keer.data.model.PendingGroupMemo
import site.lcyk.keer.data.model.PendingGroupOperation
import site.lcyk.keer.data.model.PendingGroupOperationType
import site.lcyk.keer.data.model.SyncDomain
import site.lcyk.keer.data.model.User
import site.lcyk.keer.data.repository.ResourceEncryptionScope
import site.lcyk.keer.data.model.toCachedMemoItem
import site.lcyk.keer.data.model.toMemo
import site.lcyk.keer.data.service.AccountLocalSettingsStore
import site.lcyk.keer.data.service.AccountService
import site.lcyk.keer.data.service.MemoService
import site.lcyk.keer.data.service.OfflineGroupStore
import site.lcyk.keer.data.service.SyncTrigger
import site.lcyk.keer.ext.getErrorMessage
import site.lcyk.keer.ext.string
import site.lcyk.keer.util.extractCollaboratorIds
import site.lcyk.keer.util.normalizeCollaboratorId
import site.lcyk.keer.util.normalizeTagList
import site.lcyk.keer.util.resolveAvatarUrl

@HiltViewModel
class GroupChatViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val accountService: AccountService,
    private val accountLocalSettingsStore: AccountLocalSettingsStore,
    private val offlineGroupStore: OfflineGroupStore,
    private val memoService: MemoService,
) : ViewModel() {
    private val lastGroupSyncAtMillis = mutableMapOf<String, Long>()
    private val lastGroupTagFetchAtMillis = mutableMapOf<String, Long>()

    private val _memos = MutableStateFlow<List<Memo>>(emptyList())
    val memos: StateFlow<List<Memo>> = _memos.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _groupTags = MutableStateFlow<List<String>>(emptyList())
    val groupTags: StateFlow<List<String>> = _groupTags.asStateFlow()

    suspend fun loadGroupMemos(groupId: String, forceSync: Boolean = false) = withContext(viewModelScope.coroutineContext) {
        val localState = readLocalState(groupId)
        val initialCachedRemote = localState.cachedMemos.map(CachedMemoItem::toMemo)
        _memos.value = mergeGroupMemos(
            groupId = groupId,
            remote = initialCachedRemote,
            pending = localState.pending,
            pinnedKeys = localState.pinnedKeys
        )
        if (!shouldRunGroupSync(groupId, forceSync, localState.pending.isNotEmpty())) {
            _loading.value = false
            return@withContext
        }

        _loading.value = true
        _errorMessage.value = null
        try {
            lastGroupSyncAtMillis[groupId] = System.currentTimeMillis()
            when (val syncResponse = memoService.sync(
                force = true,
                trigger = SyncTrigger.MANUAL,
                domains = setOf(SyncDomain.GROUPS),
                groupId = groupId,
            )) {
                is ApiResponse.Success -> {
                    _errorMessage.value = null
                }
                else -> {
                    _errorMessage.value = syncResponse.getErrorMessage()
                }
            }

            val latestLocalState = readLocalState(groupId)
            val latestCachedRemote = latestLocalState.cachedMemos.map(CachedMemoItem::toMemo)
            _memos.value = mergeGroupMemos(
                groupId = groupId,
                remote = latestCachedRemote,
                pending = latestLocalState.pending,
                pinnedKeys = latestLocalState.pinnedKeys
            )
        } finally {
            _loading.value = false
        }
    }

    suspend fun loadGroupTags(groupId: String, forceSync: Boolean = false) = withContext(viewModelScope.coroutineContext) {
        val localState = readLocalState(groupId)
        val cachedTags = localState.cachedTags
        if (cachedTags.isNotEmpty()) {
            _groupTags.value = cachedTags
        }

        if (accountService.getRemoteRepository() == null) {
            return@withContext
        }
        if (!shouldFetchGroupTags(groupId, forceSync, localState.pendingTagOperationCount > 0)) {
            return@withContext
        }
        lastGroupTagFetchAtMillis[groupId] = System.currentTimeMillis()

        when (val syncResponse = memoService.sync(
            force = true,
            trigger = SyncTrigger.MANUAL,
            domains = setOf(SyncDomain.GROUPS),
            groupId = groupId,
        )) {
            is ApiResponse.Success -> Unit
            else -> {
                _errorMessage.value = syncResponse.getErrorMessage()
            }
        }
        val refreshedTags = readLocalState(groupId).cachedTags
        if (refreshedTags.isNotEmpty()) {
            _groupTags.value = refreshedTags
        }
    }

    suspend fun addGroupTag(groupId: String, tag: String): Boolean = withContext(viewModelScope.coroutineContext) {
        val normalized = tag.trim()
        if (normalized.isEmpty()) {
            return@withContext false
        }

        val localTags = (_groupTags.value + normalized)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        _groupTags.value = localTags
        persistCachedGroupTags(groupId, localTags)
        enqueueGroupTagOperation(groupId, normalized)
        loadGroupTags(groupId, forceSync = true)
        true
    }

    suspend fun sendGroupMemo(
        groupId: String,
        content: String,
        tags: List<String> = emptyList(),
        resourceIdentifiers: List<String> = emptyList()
    ): Boolean = withContext(viewModelScope.coroutineContext) {
        val text = content.trim()
        if (text.isEmpty() && resourceIdentifiers.isEmpty()) {
            return@withContext false
        }

        val creator = resolveCreator() ?: run {
            _errorMessage.value = R.string.group_error_account_not_support_group_memos.string
            return@withContext false
        }

        val pending = PendingGroupMemo(
            localId = UUID.randomUUID().toString(),
            groupId = groupId,
            content = text,
            tags = normalizeTagList(tags),
            creatorId = creator.identifier,
            creatorName = creator.name,
            creatorAvatarUrl = creator.avatarUrl,
            createdAtEpochMillis = System.currentTimeMillis(),
            resourceIdentifiers = resourceIdentifiers.distinct()
        )
        appendPendingMemo(pending)
        syncPendingGroupMemos(groupId)
        loadGroupMemos(groupId, forceSync = false)
        true
    }

    suspend fun syncPendingGroupMemos(groupId: String): Boolean = withContext(viewModelScope.coroutineContext) {
        if (groupId.isBlank()) {
            return@withContext false
        }
        lastGroupSyncAtMillis[groupId] = System.currentTimeMillis()
        when (val response = memoService.sync(
            force = true,
            trigger = SyncTrigger.MUTATION,
            domains = setOf(SyncDomain.GROUPS),
            groupId = groupId,
        )) {
            is ApiResponse.Success -> {
                _errorMessage.value = null
                true
            }
            else -> {
                _errorMessage.value = response.getErrorMessage()
                false
            }
        }
    }

    suspend fun findGroupMemo(groupId: String, memoRemoteId: String): Memo? = withContext(viewModelScope.coroutineContext) {
        val normalizedRemoteID = memoRemoteId.trim()
        if (normalizedRemoteID.isEmpty()) {
            return@withContext null
        }
        _memos.value.firstOrNull { memo -> memo.remoteId == normalizedRemoteID }?.let { memo ->
            return@withContext memo
        }
        val localState = readLocalState(groupId)
        return@withContext mergeGroupMemos(
            groupId = groupId,
            remote = localState.cachedMemos.map(CachedMemoItem::toMemo),
            pending = localState.pending,
            pinnedKeys = localState.pinnedKeys
        ).firstOrNull { memo -> memo.remoteId == normalizedRemoteID }
    }

    fun canManageGroupMemo(memo: Memo, currentUserId: String): Boolean {
        val normalizedCurrentUserID = normalizeCollaboratorId(currentUserId)
        if (normalizedCurrentUserID.isEmpty()) {
            return false
        }
        val creatorID = normalizeCollaboratorId(memo.creator?.identifier.orEmpty())
        if (creatorID == normalizedCurrentUserID) {
            return true
        }
        return extractCollaboratorIds(memo.tags).any { collaboratorID ->
            normalizeCollaboratorId(collaboratorID) == normalizedCurrentUserID
        }
    }

    suspend fun updateGroupMemo(
        groupId: String,
        memoRemoteId: String,
        content: String,
        tags: List<String>,
        resourceIdentifiers: List<String>? = null
    ): Boolean = withContext(viewModelScope.coroutineContext) {
        val normalizedRemoteID = memoRemoteId.trim()
        val normalizedContent = content.trim()
        val normalizedTags = normalizeTagList(tags)
        if (normalizedRemoteID.isEmpty() || (normalizedContent.isEmpty() && resourceIdentifiers.isNullOrEmpty())) {
            return@withContext false
        }

        if (normalizedRemoteID.startsWith("local:")) {
            val localID = normalizedRemoteID.removePrefix("local:").trim()
            if (localID.isEmpty()) {
                return@withContext false
            }
            val updated = updatePendingGroupMemo(
                groupId = groupId,
                localId = localID,
                content = normalizedContent,
                tags = normalizedTags,
                resourceIdentifiers = resourceIdentifiers
            )
            if (!updated) {
                return@withContext false
            }
            _memos.value = _memos.value
                .map { memo ->
                    if (memo.remoteId == normalizedRemoteID) {
                        memo.copy(
                            content = normalizedContent,
                            tags = normalizedTags,
                            updatedAt = Instant.now()
                        )
                    } else {
                        memo
                    }
                }
                .sortedWith(compareByDescending<Memo> { it.pinned }.thenByDescending { it.date })
            _errorMessage.value = null
            return@withContext true
        }

        val remoteRepository = accountService.getRemoteRepository() ?: run {
            _errorMessage.value = R.string.group_error_account_not_support_group_memos.string
            return@withContext false
        }
        val resolvedResourceRemoteIds = when {
            resourceIdentifiers == null -> null
            resourceIdentifiers.isEmpty() -> emptyList()
            else -> {
                when (
                    val uploadResponse = accountService.getRepository().resolveRemoteResourceIds(
                        resourceIdentifiers = resourceIdentifiers,
                        encryptionScope = ResourceEncryptionScope.Group(groupId),
                    )
                ) {
                    is ApiResponse.Success -> uploadResponse.data
                    else -> {
                        _errorMessage.value = uploadResponse.getErrorMessage()
                        return@withContext false
                    }
                }
            }
        }

        return@withContext when (
            val response = remoteRepository.updateGroupMessage(
                groupId = groupId,
                messageRemoteId = normalizedRemoteID,
                content = normalizedContent,
                tags = normalizedTags,
                resourceRemoteIds = resolvedResourceRemoteIds
            )
        ) {
            is ApiResponse.Success -> {
                val updatedMemo = response.data
                upsertCachedGroupMemo(groupId, updatedMemo)
                _memos.value = _memos.value
                    .map { memo ->
                        if (memo.remoteId == normalizedRemoteID) {
                            val pinned = memo.pinned
                            updatedMemo.copy(pinned = pinned)
                        } else {
                            memo
                        }
                    }
                    .let { current ->
                        if (current.none { memo -> memo.remoteId == updatedMemo.remoteId }) {
                            current + updatedMemo
                        } else {
                            current
                        }
                    }
                    .sortedWith(compareByDescending<Memo> { it.pinned }.thenByDescending { it.date })
                _errorMessage.value = null
                true
            }
            else -> {
                _errorMessage.value = response.getErrorMessage()
                false
            }
        }
    }

    suspend fun deleteGroupMemo(groupId: String, memoRemoteId: String): Boolean = withContext(viewModelScope.coroutineContext) {
        val normalizedRemoteID = memoRemoteId.trim()
        if (normalizedRemoteID.isEmpty()) {
            return@withContext false
        }

        if (normalizedRemoteID.startsWith("local:")) {
            val localID = normalizedRemoteID.removePrefix("local:").trim()
            if (localID.isEmpty()) {
                return@withContext false
            }
            val removed = removePendingGroupMemo(groupId, localID, normalizedRemoteID)
            if (!removed) {
                return@withContext false
            }
            _memos.value = _memos.value
                .filterNot { memo -> memo.remoteId == normalizedRemoteID }
                .sortedWith(compareByDescending<Memo> { it.pinned }.thenByDescending { it.date })
            _errorMessage.value = null
            return@withContext true
        }

        val remoteRepository = accountService.getRemoteRepository() ?: run {
            _errorMessage.value = R.string.group_error_account_not_support_group_memos.string
            return@withContext false
        }

        return@withContext when (val response = remoteRepository.deleteGroupMessage(groupId, normalizedRemoteID)) {
            is ApiResponse.Success -> {
                removeCachedGroupMemo(groupId, normalizedRemoteID)
                _memos.value = _memos.value
                    .filterNot { memo -> memo.remoteId == normalizedRemoteID }
                    .sortedWith(compareByDescending<Memo> { it.pinned }.thenByDescending { it.date })
                _errorMessage.value = null
                true
            }
            else -> {
                _errorMessage.value = response.getErrorMessage()
                false
            }
        }
    }

    suspend fun setGroupMemoPinned(
        groupId: String,
        memoRemoteId: String,
        pinned: Boolean
    ): Boolean = withContext(viewModelScope.coroutineContext) {
        if (memoRemoteId.isBlank()) {
            return@withContext false
        }

        readCurrentAccountKey()?.let { accountKey ->
            offlineGroupStore.setPinnedGroupMemo(accountKey, groupId, memoRemoteId, pinned)
        }

        _memos.value = _memos.value
            .map { memo ->
                if (memo.remoteId == memoRemoteId) memo.copy(pinned = pinned) else memo
            }
            .sortedWith(compareByDescending<Memo> { it.pinned }.thenByDescending { it.date })
        true
    }

    suspend fun markGroupRead(groupId: String): Boolean = withContext(viewModelScope.coroutineContext) {
        if (groupId.isBlank()) {
            return@withContext false
        }
        val accountKey = readCurrentAccountKey() ?: return@withContext false
        val remoteRepository = accountService.getRemoteRepository()
        if (remoteRepository == null) {
            offlineGroupStore.markGroupRead(accountKey, groupId)
            _errorMessage.value = null
            return@withContext true
        }
        val lastIncomingMessageRemoteId = resolveLatestIncomingMessageRemoteId()
        if (lastIncomingMessageRemoteId == null) {
            _errorMessage.value = R.string.group_error_missing_read_target.string
            return@withContext false
        }
        return@withContext when (val response = remoteRepository.markGroupRead(groupId, lastIncomingMessageRemoteId)) {
            is ApiResponse.Success -> {
                offlineGroupStore.markGroupRead(accountKey, groupId)
                _errorMessage.value = null
                true
            }
            else -> {
                _errorMessage.value = response.getErrorMessage()
                false
            }
        }
    }

    fun clearErrorMessage() {
        _errorMessage.value = null
    }

    private suspend fun enqueueGroupTagOperation(groupId: String, tag: String) {
        readCurrentAccountKey()?.let { accountKey ->
            offlineGroupStore.enqueuePendingGroupOperation(
                accountKey,
                PendingGroupOperation(
                    operationId = UUID.randomUUID().toString(),
                    type = PendingGroupOperationType.ADD_TAG,
                    groupId = groupId,
                    tag = tag
                )
            )
        }
    }

    private suspend fun persistCachedGroupTags(groupId: String, tags: List<String>) {
        readCurrentAccountKey()?.let { accountKey ->
            offlineGroupStore.upsertCachedGroupTags(accountKey, groupId, tags)
        }
    }

    private suspend fun persistCachedGroupMemos(groupId: String, memos: List<Memo>) {
        readCurrentAccountKey()?.let { accountKey ->
            offlineGroupStore.replaceCachedGroupMemos(
                accountKey = accountKey,
                groupId = groupId,
                memos = memos.map { memo -> memo.toCachedMemoItem(groupId = groupId) }
            )
        }
    }

    private suspend fun appendPendingMemo(pending: PendingGroupMemo) {
        readCurrentAccountKey()?.let { accountKey ->
            offlineGroupStore.upsertPendingGroupMemo(accountKey, pending)
        }
    }

    private suspend fun updatePendingGroupMemo(
        groupId: String,
        localId: String,
        content: String,
        tags: List<String>,
        resourceIdentifiers: List<String>? = null
    ): Boolean {
        val accountKey = readCurrentAccountKey() ?: return false
        var updated = false
        val pendingMemos = offlineGroupStore.getPendingGroupMemos(accountKey).map { pendingMemo ->
            if (pendingMemo.groupId == groupId && pendingMemo.localId == localId) {
                updated = true
                pendingMemo.copy(
                    content = content,
                    tags = tags,
                    resourceIdentifiers = resourceIdentifiers ?: pendingMemo.resourceIdentifiers
                )
            } else {
                pendingMemo
            }
        }
        if (!updated) {
            return false
        }
        pendingMemos.forEach { pendingMemo ->
            offlineGroupStore.upsertPendingGroupMemo(accountKey, pendingMemo)
        }
        return true
    }

    private suspend fun removePendingGroupMemo(groupId: String, localId: String, remoteId: String): Boolean {
        val accountKey = readCurrentAccountKey() ?: return false
        val exists = offlineGroupStore.getPendingGroupMemos(accountKey, groupId)
            .any { pendingMemo -> pendingMemo.localId == localId }
        if (!exists) {
            return false
        }
        offlineGroupStore.removePendingGroupMemo(accountKey, groupId, localId)
        offlineGroupStore.setPinnedGroupMemo(accountKey, groupId, remoteId, pinned = false)
        return true
    }

    private suspend fun upsertCachedGroupMemo(groupId: String, memo: Memo) {
        readCurrentAccountKey()?.let { accountKey ->
            offlineGroupStore.upsertCachedGroupMemo(accountKey, groupId, memo.toCachedMemoItem(groupId = groupId))
        }
    }

    private suspend fun removeCachedGroupMemo(groupId: String, remoteId: String) {
        readCurrentAccountKey()?.let { accountKey ->
            offlineGroupStore.removeCachedGroupMemo(accountKey, groupId, remoteId)
            offlineGroupStore.setPinnedGroupMemo(accountKey, groupId, remoteId, pinned = false)
        }
    }

    private data class GroupLocalState(
        val pending: List<PendingGroupMemo>,
        val pinnedKeys: Set<String>,
        val cachedMemos: List<CachedMemoItem>,
        val cachedTags: List<String>,
        val pendingTagOperationCount: Int,
    )

    private suspend fun readLocalState(groupId: String): GroupLocalState {
        val accountKey = readCurrentAccountKey()
        if (accountKey == null) {
            return GroupLocalState(
                pending = emptyList(),
                pinnedKeys = emptySet(),
                cachedMemos = emptyList(),
                cachedTags = emptyList(),
                pendingTagOperationCount = 0
            )
        }
        val pending = offlineGroupStore.getPendingGroupMemos(accountKey, groupId)
        val pinnedKeys = offlineGroupStore.getPinnedGroupMemoKeys(accountKey)
        val cachedMemos = offlineGroupStore.getCachedGroupMemos(accountKey, groupId)
        val cachedTags = offlineGroupStore.getCachedGroupTags(accountKey, groupId)
        val pendingTagOperationCount = offlineGroupStore.getPendingGroupOperations(accountKey).count { operation ->
            operation.type == PendingGroupOperationType.ADD_TAG && operation.groupId == groupId
        }
        return GroupLocalState(
            pending = pending,
            pinnedKeys = pinnedKeys,
            cachedMemos = cachedMemos,
            cachedTags = cachedTags,
            pendingTagOperationCount = pendingTagOperationCount
        )
    }

    private fun shouldRunGroupSync(groupId: String, forceSync: Boolean, hasPendingMessages: Boolean): Boolean {
        if (forceSync) {
            return true
        }
        val now = System.currentTimeMillis()
        val lastSyncAt = lastGroupSyncAtMillis[groupId] ?: 0L
        val interval = if (hasPendingMessages) {
            GROUP_PENDING_SYNC_INTERVAL_MILLIS
        } else {
            GROUP_AUTO_SYNC_INTERVAL_MILLIS
        }
        return now - lastSyncAt >= interval
    }

    private fun shouldFetchGroupTags(groupId: String, forceSync: Boolean, hasPendingTagOps: Boolean): Boolean {
        if (forceSync) {
            return true
        }
        val now = System.currentTimeMillis()
        val lastFetchAt = lastGroupTagFetchAtMillis[groupId] ?: 0L
        val interval = if (hasPendingTagOps) {
            GROUP_PENDING_SYNC_INTERVAL_MILLIS
        } else {
            GROUP_TAG_FETCH_INTERVAL_MILLIS
        }
        return now - lastFetchAt >= interval
    }

    private fun mergeGroupMemos(
        groupId: String,
        remote: List<Memo>,
        pending: List<PendingGroupMemo>,
        pinnedKeys: Set<String>
    ): List<Memo> {
        val pendingMemos = pending.map { it.toMemo() }
        return (remote + pendingMemos)
            .distinctBy { it.remoteId }
            .map { memo ->
                val pinned = groupMemoKey(groupId, memo.remoteId) in pinnedKeys
                if (memo.pinned == pinned) memo else memo.copy(pinned = pinned)
            }
            .sortedWith(compareByDescending<Memo> { it.pinned }.thenByDescending { it.date })
    }

    private fun PendingGroupMemo.toMemo(): Memo {
        val timestamp = Instant.ofEpochMilli(createdAtEpochMillis)
        return Memo(
            remoteId = localMemoRemoteId(localId),
            content = content,
            date = timestamp,
            pinned = false,
            visibility = MemoVisibility.PROTECTED,
            resources = emptyList(),
            tags = tags,
            creator = User(
                identifier = creatorId,
                name = creatorName,
                startDate = timestamp,
                avatarUrl = creatorAvatarUrl
            ),
            archived = false,
            updatedAt = timestamp
        )
    }

    private suspend fun resolveCreator(): User? {
        val localAvatarUri = accountLocalSettingsStore.currentUserSettings()
            ?.avatarUri
            .orEmpty()
        return when (val account = accountService.currentAccount.first()) {
            is Account.KeerV2 -> User(
                identifier = account.info.id.toString(),
                name = account.info.name.ifBlank { account.info.id.toString() },
                startDate = Instant.now(),
                avatarUrl = if (localAvatarUri.isNotBlank()) {
                    localAvatarUri
                } else {
                    resolveAvatarUrl(account.info.host, account.info.avatarUrl)
                }
            )
            is Account.Local -> User(
                identifier = "local",
                name = "Local",
                startDate = Instant.now(),
                avatarUrl = null
            )
            null -> null
        }
    }

    private suspend fun resolveLatestIncomingMessageRemoteId(): String? {
        val currentUserId = normalizeCollaboratorId(
            when (val account = accountService.currentAccount.first()) {
                is Account.KeerV2 -> account.info.id.toString()
                is Account.Local -> "local"
                null -> ""
            }
        )
        return _memos.value
            .asSequence()
            .filter { memo ->
                !memo.remoteId.startsWith("local:") &&
                    normalizeCollaboratorId(memo.creator?.identifier.orEmpty()) != currentUserId
            }
            .maxByOrNull { memo -> parseRemoteNumericId(memo.remoteId) ?: memo.date.toEpochMilli() }
            ?.remoteId
    }

    private suspend fun readCurrentAccountKey(): String? {
        return accountLocalSettingsStore.observeCurrentAccountKey().first()
    }

    private fun parseRemoteNumericId(remoteId: String): Long? {
        return remoteId.trim()
            .substringAfterLast('/')
            .toLongOrNull()
    }

    private fun groupMemoKey(groupId: String, memoRemoteId: String): String {
        return "$groupId|$memoRemoteId"
    }

    private fun localMemoRemoteId(localId: String): String {
        return "local:$localId"
    }

    companion object {
        private const val GROUP_AUTO_SYNC_INTERVAL_MILLIS = 120_000L
        private const val GROUP_PENDING_SYNC_INTERVAL_MILLIS = 20_000L
        private const val GROUP_TAG_FETCH_INTERVAL_MILLIS = 120_000L
    }
}
