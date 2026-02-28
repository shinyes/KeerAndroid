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
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import site.lcyk.keer.R
import site.lcyk.keer.data.model.Account
import site.lcyk.keer.data.model.CachedMemoItem
import site.lcyk.keer.data.model.Memo
import site.lcyk.keer.data.model.MemoVisibility
import site.lcyk.keer.data.model.PendingGroupMemo
import site.lcyk.keer.data.model.PendingGroupOperation
import site.lcyk.keer.data.model.PendingGroupOperationType
import site.lcyk.keer.data.model.User
import site.lcyk.keer.data.model.toCachedMemoItem
import site.lcyk.keer.data.model.toMemo
import site.lcyk.keer.data.service.AccountService
import site.lcyk.keer.data.service.OfflineSyncTask
import site.lcyk.keer.data.service.OfflineSyncTaskScheduler
import site.lcyk.keer.ext.getErrorMessage
import site.lcyk.keer.ext.settingsDataStore
import site.lcyk.keer.ext.string
import site.lcyk.keer.util.normalizeTagList

@HiltViewModel
class GroupChatViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val accountService: AccountService,
    private val offlineSyncTaskScheduler: OfflineSyncTaskScheduler
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
            when (val syncResponse = offlineSyncTaskScheduler.dispatch(
                setOf(OfflineSyncTask.GROUP_OPERATIONS, OfflineSyncTask.GROUP_TAGS)
            )) {
                is ApiResponse.Success -> {
                    when (val groupMessageSync = offlineSyncTaskScheduler.dispatchGroupMessages(groupId)) {
                        is ApiResponse.Success -> Unit
                        else -> {
                            _errorMessage.value = groupMessageSync.getErrorMessage()
                        }
                    }
                }
                else -> {
                    _errorMessage.value = syncResponse.getErrorMessage()
                }
            }

            val remoteRepository = accountService.getRemoteRepository() ?: return@withContext
            val loadedRemote = mutableListOf<Memo>()
            var pageToken: String? = null
            do {
                when (
                    val response = remoteRepository.listGroupMessages(
                        groupId = groupId,
                        pageSize = 100,
                        pageToken = pageToken
                    )
                ) {
                    is ApiResponse.Success -> {
                        loadedRemote += response.data.first
                        pageToken = response.data.second
                    }
                    else -> {
                        _errorMessage.value = response.getErrorMessage()
                        pageToken = null
                    }
                }
            } while (!pageToken.isNullOrBlank())

            if (loadedRemote.isNotEmpty()) {
                persistCachedGroupMemos(groupId, loadedRemote)
            }

            val latestLocalState = readLocalState(groupId)
            val latestCachedRemote = latestLocalState.cachedMemos.map(CachedMemoItem::toMemo)
            _memos.value = mergeGroupMemos(
                groupId = groupId,
                remote = if (loadedRemote.isEmpty()) latestCachedRemote else loadedRemote,
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

        val remoteRepository = accountService.getRemoteRepository()
        if (remoteRepository == null) {
            return@withContext
        }
        if (!shouldFetchGroupTags(groupId, forceSync, localState.pendingTagOperationCount > 0)) {
            return@withContext
        }
        lastGroupTagFetchAtMillis[groupId] = System.currentTimeMillis()

        when (
            val syncResponse = offlineSyncTaskScheduler.dispatch(
                setOf(OfflineSyncTask.GROUP_OPERATIONS, OfflineSyncTask.GROUP_TAGS)
            )
        ) {
            is ApiResponse.Success -> Unit
            else -> {
                _errorMessage.value = syncResponse.getErrorMessage()
            }
        }

        when (val response = remoteRepository.listGroupTags(groupId)) {
            is ApiResponse.Success -> {
                _groupTags.value = response.data
                persistCachedGroupTags(groupId, response.data)
            }
            else -> {
                _errorMessage.value = response.getErrorMessage()
            }
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
        tags: List<String> = emptyList()
    ): Boolean = withContext(viewModelScope.coroutineContext) {
        val text = content.trim()
        if (text.isEmpty()) {
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
            createdAtEpochMillis = System.currentTimeMillis()
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
        when (val response = offlineSyncTaskScheduler.dispatchGroupMessages(groupId)) {
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

    suspend fun setGroupMemoPinned(
        groupId: String,
        memoRemoteId: String,
        pinned: Boolean
    ): Boolean = withContext(viewModelScope.coroutineContext) {
        if (memoRemoteId.isBlank()) {
            return@withContext false
        }

        val key = groupMemoKey(groupId, memoRemoteId)
        context.settingsDataStore.updateData { existing ->
            val userIndex = existing.usersList.indexOfFirst { it.accountKey == existing.currentUser }
            if (userIndex == -1) {
                return@updateData existing
            }
            val users = existing.usersList.toMutableList()
            val target = users[userIndex]
            val pinnedKeys = target.settings.pinnedGroupMemoKeys.toMutableSet()
            if (pinned) {
                pinnedKeys += key
            } else {
                pinnedKeys -= key
            }
            users[userIndex] = target.copy(
                settings = target.settings.copy(
                    pinnedGroupMemoKeys = pinnedKeys.toList()
                )
            )
            existing.copy(usersList = users)
        }

        _memos.value = _memos.value
            .map { memo ->
                if (memo.remoteId == memoRemoteId) memo.copy(pinned = pinned) else memo
            }
            .sortedByDescending { it.date }
        true
    }

    private suspend fun enqueueGroupTagOperation(groupId: String, tag: String) {
        context.settingsDataStore.updateData { existing ->
            val userIndex = existing.usersList.indexOfFirst { it.accountKey == existing.currentUser }
            if (userIndex == -1) {
                return@updateData existing
            }
            val users = existing.usersList.toMutableList()
            val target = users[userIndex]
            val operation = PendingGroupOperation(
                operationId = UUID.randomUUID().toString(),
                type = PendingGroupOperationType.ADD_TAG,
                groupId = groupId,
                tag = tag
            )
            users[userIndex] = target.copy(
                settings = target.settings.copy(
                    pendingGroupOperations = target.settings.pendingGroupOperations + operation
                )
            )
            existing.copy(usersList = users)
        }
    }

    private suspend fun persistCachedGroupTags(groupId: String, tags: List<String>) {
        context.settingsDataStore.updateData { existing ->
            val userIndex = existing.usersList.indexOfFirst { it.accountKey == existing.currentUser }
            if (userIndex == -1) {
                return@updateData existing
            }
            val users = existing.usersList.toMutableList()
            val target = users[userIndex]
            val updatedTags = target.settings.cachedGroupTags
                .filterNot { it.groupId == groupId } + site.lcyk.keer.data.model.CachedGroupTagSet(
                groupId = groupId,
                tags = tags
            )
            users[userIndex] = target.copy(
                settings = target.settings.copy(
                    cachedGroupTags = updatedTags
                )
            )
            existing.copy(usersList = users)
        }
    }

    private suspend fun persistCachedGroupMemos(groupId: String, memos: List<Memo>) {
        context.settingsDataStore.updateData { existing ->
            val userIndex = existing.usersList.indexOfFirst { it.accountKey == existing.currentUser }
            if (userIndex == -1) {
                return@updateData existing
            }
            val users = existing.usersList.toMutableList()
            val target = users[userIndex]
            val incoming = memos.map { it.toCachedMemoItem(groupId = groupId) }
            val updated = target.settings.cachedGroupMemos
                .filterNot { it.groupId == groupId } + incoming
            users[userIndex] = target.copy(
                settings = target.settings.copy(
                    cachedGroupMemos = updated
                )
            )
            existing.copy(usersList = users)
        }
    }

    private suspend fun appendPendingMemo(pending: PendingGroupMemo) {
        context.settingsDataStore.updateData { existing ->
            val userIndex = existing.usersList.indexOfFirst { it.accountKey == existing.currentUser }
            if (userIndex == -1) {
                return@updateData existing
            }
            val users = existing.usersList.toMutableList()
            val target = users[userIndex]
            users[userIndex] = target.copy(
                settings = target.settings.copy(
                    pendingGroupMemos = target.settings.pendingGroupMemos + pending
                )
            )
            existing.copy(usersList = users)
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
        val settings = context.settingsDataStore.data.first()
        val userSettings = settings.usersList
            .firstOrNull { it.accountKey == settings.currentUser }
            ?.settings
        if (userSettings == null) {
            return GroupLocalState(
                pending = emptyList(),
                pinnedKeys = emptySet(),
                cachedMemos = emptyList(),
                cachedTags = emptyList(),
                pendingTagOperationCount = 0
            )
        }
        return GroupLocalState(
            pending = userSettings.pendingGroupMemos.filter { it.groupId == groupId },
            pinnedKeys = userSettings.pinnedGroupMemoKeys.toSet(),
            cachedMemos = userSettings.cachedGroupMemos.filter { it.groupId == groupId },
            cachedTags = userSettings.cachedGroupTags
                .firstOrNull { it.groupId == groupId }
                ?.tags
                .orEmpty(),
            pendingTagOperationCount = userSettings.pendingGroupOperations.count { operation ->
                operation.type == PendingGroupOperationType.ADD_TAG && operation.groupId == groupId
            }
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
            .sortedByDescending { it.date }
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
        val settings = context.settingsDataStore.data.first()
        val localAvatarUri = settings.usersList
            .firstOrNull { it.accountKey == settings.currentUser }
            ?.settings
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

    private fun groupMemoKey(groupId: String, memoRemoteId: String): String {
        return "$groupId|$memoRemoteId"
    }

    private fun localMemoRemoteId(localId: String): String {
        return "local:$localId"
    }

    private fun resolveAvatarUrl(host: String, avatarUrl: String): String? {
        if (avatarUrl.isBlank()) {
            return null
        }
        if (avatarUrl.toHttpUrlOrNull() != null || "://" in avatarUrl) {
            return avatarUrl
        }
        val baseUrl = host.toHttpUrlOrNull() ?: return avatarUrl
        return runCatching {
            baseUrl.toUrl().toURI().resolve(avatarUrl).toString()
        }.getOrDefault(avatarUrl)
    }

    companion object {
        private const val GROUP_AUTO_SYNC_INTERVAL_MILLIS = 120_000L
        private const val GROUP_PENDING_SYNC_INTERVAL_MILLIS = 20_000L
        private const val GROUP_TAG_FETCH_INTERVAL_MILLIS = 120_000L
    }
}
