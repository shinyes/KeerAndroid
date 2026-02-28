package site.lcyk.keer.viewmodel

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.skydoves.sandwich.ApiResponse
import com.skydoves.sandwich.retrofit.statusCode
import com.skydoves.sandwich.suspendOnSuccess
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import site.lcyk.keer.R
import site.lcyk.keer.data.model.CachedMemoItem
import site.lcyk.keer.data.local.entity.MemoEntity
import site.lcyk.keer.data.local.entity.ResourceEntity
import site.lcyk.keer.data.model.DailyUsageStat
import site.lcyk.keer.data.model.Memo
import site.lcyk.keer.data.model.MemoGroup
import site.lcyk.keer.data.model.MemoVisibility
import site.lcyk.keer.data.model.SyncStatus
import site.lcyk.keer.data.model.toCachedMemoItem
import site.lcyk.keer.data.model.toMemo
import site.lcyk.keer.data.service.AccountService
import site.lcyk.keer.data.service.MemoService
import site.lcyk.keer.data.service.SyncTrigger
import site.lcyk.keer.ext.getErrorMessage
import site.lcyk.keer.ext.settingsDataStore
import site.lcyk.keer.ext.string
import site.lcyk.keer.widget.WidgetUpdater
import java.time.LocalDate
import java.time.OffsetDateTime
import javax.inject.Inject

@HiltViewModel
class MemosViewModel @Inject constructor(
    private val memoService: MemoService,
    private val accountService: AccountService,
    @param:ApplicationContext private val appContext: Context
) : ViewModel() {

    var memos = mutableStateListOf<MemoEntity>()
        private set
    var tags = mutableStateListOf<String>()
        private set
    var errorMessage: String? by mutableStateOf(null)
        private set
    var matrix by mutableStateOf(DailyUsageStat.initialMatrix)
        private set
    var createdGroupMemos by mutableStateOf<List<Memo>>(emptyList())
        private set
    var createdGroupMemosLoading by mutableStateOf(false)
        private set
    var createdGroupMemosErrorMessage: String? by mutableStateOf(null)
        private set

    val host: StateFlow<String?> =
        accountService.currentAccount
            .map { it?.getAccountInfo()?.host }
            .distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val syncStatus: StateFlow<SyncStatus> =
        memoService.syncStatus.stateIn(viewModelScope, SharingStarted.Eagerly, SyncStatus())

    init {
        snapshotFlow { memos.toList() }
            .onEach { matrix = calculateMatrix() }
            .launchIn(viewModelScope)

        viewModelScope.launch {
            loadMemosSnapshot()

            memoService.syncStatus
                .map { it.syncing }
                .distinctUntilChanged()
                .collectLatest { syncing ->
                    if (syncing) {
                        return@collectLatest
                    }
                    memoService.memos.collectLatest { latestMemos ->
                        applyMemos(latestMemos)
                    }
                }
        }
    }

    private suspend fun loadMemosSnapshot() {
        when (val response = memoService.getRepository().listMemos()) {
            is ApiResponse.Success -> {
                applyMemos(response.data)
            }
            else -> {
                errorMessage = response.getErrorMessage()
            }
        }
    }

    suspend fun refreshLocalSnapshot() = withContext(viewModelScope.coroutineContext) {
        loadMemosSnapshot()
    }

    private fun applyMemos(latestMemos: List<MemoEntity>) {
        memos.clear()
        memos.addAll(latestMemos)
        errorMessage = null
    }

    suspend fun loadMemos(
        syncAfterLoad: Boolean = true,
        trigger: SyncTrigger = SyncTrigger.AUTO
    ) = withContext(viewModelScope.coroutineContext) {
        if (syncAfterLoad) {
            memoService.requestSync(trigger = trigger, force = false)
        }
    }

    suspend fun refreshMemos(): ManualSyncResult = withContext(viewModelScope.coroutineContext) {
        when (val compatibility = accountService.checkCurrentAccountSyncCompatibility(isAutomatic = false)) {
            is AccountService.SyncCompatibility.Blocked -> {
                return@withContext ManualSyncResult.Blocked(
                    compatibility.message ?: R.string.memos_supported_versions.string
                )
            }
            is AccountService.SyncCompatibility.Unavailable -> {
                return@withContext ManualSyncResult.Failed(
                    compatibility.message ?: R.string.sync_server_unreachable.string
                )
            }
            AccountService.SyncCompatibility.Allowed -> Unit
        }

        val syncResult = memoService.sync(
            force = true,
            trigger = SyncTrigger.MANUAL
        )
        if (syncResult is ApiResponse.Success) {
            WidgetUpdater.updateWidgets(appContext)
        } else {
            val message = syncResult.getErrorMessage()
            errorMessage = message
            return@withContext ManualSyncResult.Failed(message)
        }
        ManualSyncResult.Completed
    }

    suspend fun loadCreatedGroupMemos(groups: List<MemoGroup>, creatorId: String?) = withContext(viewModelScope.coroutineContext) {
        val normalizedCreatorId = creatorId?.trim().orEmpty()
        if (normalizedCreatorId.isEmpty()) {
            createdGroupMemos = emptyList()
            createdGroupMemosErrorMessage = null
            createdGroupMemosLoading = false
            return@withContext
        }

        val remoteRepository = accountService.getRemoteRepository()
        if (remoteRepository == null) {
            createdGroupMemos = emptyList()
            createdGroupMemosErrorMessage = "Current account does not support group memos"
            createdGroupMemosLoading = false
            return@withContext
        }

        val targetGroups = groups.filter { group ->
            group.creatorId.trim() == normalizedCreatorId
        }
        if (targetGroups.isEmpty()) {
            createdGroupMemos = emptyList()
            createdGroupMemosErrorMessage = null
            createdGroupMemosLoading = false
            return@withContext
        }

        val cached = readCachedGroupMemosByCreator(
            groupIds = targetGroups.map { it.id }.toSet(),
            creatorId = normalizedCreatorId
        )
        if (cached.isNotEmpty()) {
            createdGroupMemos = cached
        }

        createdGroupMemosLoading = true
        createdGroupMemosErrorMessage = null
        try {
            val loaded = mutableListOf<Memo>()
            val cachedItems = mutableListOf<CachedMemoItem>()
            for (group in targetGroups) {
                var pageToken: String? = null
                do {
                    when (val response = remoteRepository.listGroupMessages(group.id, pageSize = 100, pageToken = pageToken)) {
                        is ApiResponse.Success -> {
                            loaded += response.data.first
                            cachedItems += response.data.first.map { memo ->
                                memo.toCachedMemoItem(groupId = group.id)
                            }
                            pageToken = response.data.second
                        }
                        is ApiResponse.Failure.Error -> {
                            createdGroupMemosErrorMessage = "Load failed: HTTP ${response.statusCode}"
                            pageToken = null
                        }
                        is ApiResponse.Failure.Exception -> {
                            createdGroupMemosErrorMessage = response.throwable.message ?: "Load failed"
                            pageToken = null
                        }
                    }
                } while (!pageToken.isNullOrBlank())
            }
            val merged = loaded
                .distinctBy { memo -> memo.remoteId }
                .sortedByDescending { memo -> memo.date }
            if (merged.isNotEmpty()) {
                createdGroupMemos = merged
                persistCachedGroupMemos(cachedItems)
            } else if (cached.isNotEmpty()) {
                createdGroupMemos = cached
            }
        } finally {
            createdGroupMemosLoading = false
        }
    }

    private suspend fun readCachedGroupMemosByCreator(
        groupIds: Set<String>,
        creatorId: String
    ): List<Memo> {
        val settings = appContext.settingsDataStore.data.first()
        val userSettings = settings.usersList
            .firstOrNull { it.accountKey == settings.currentUser }
            ?.settings
            ?: return emptyList()
        return userSettings.cachedGroupMemos
            .asSequence()
            .filter { item -> item.groupId != null && item.groupId in groupIds }
            .map { item -> item.toMemo() }
            .filter { memo -> memo.creator?.identifier?.trim() == creatorId }
            .distinctBy { memo -> memo.remoteId }
            .sortedByDescending { memo -> memo.date }
            .toList()
    }

    private suspend fun persistCachedGroupMemos(
        cachedItems: List<CachedMemoItem>
    ) {
        if (cachedItems.isEmpty()) {
            return
        }
        appContext.settingsDataStore.updateData { existing ->
            val index = existing.usersList.indexOfFirst { it.accountKey == existing.currentUser }
            if (index == -1) {
                return@updateData existing
            }
            val users = existing.usersList.toMutableList()
            val target = users[index]
            val touchedGroupIds = cachedItems.mapNotNull { it.groupId }.toSet()
            val base = target.settings.cachedGroupMemos.filterNot { item ->
                item.groupId != null && item.groupId in touchedGroupIds
            }
            users[index] = target.copy(
                settings = target.settings.copy(
                    cachedGroupMemos = base + cachedItems
                )
            )
            existing.copy(usersList = users)
        }
    }

    fun loadTags() = viewModelScope.launch {
        memoService.getRepository().listTags().suspendOnSuccess {
            tags.clear()
            tags.addAll(data)
        }
    }

    suspend fun renameTag(oldTag: String, newTag: String): ApiResponse<Unit> = withContext(viewModelScope.coroutineContext) {
        val response = memoService.getRepository().renameTag(oldTag, newTag)
        if (response is ApiResponse.Success) {
            loadMemosSnapshot()
            memoService.getRepository().listTags().suspendOnSuccess {
                tags.clear()
                tags.addAll(data)
            }
            WidgetUpdater.updateWidgets(appContext)
            triggerSyncAfterMutation()
        }
        response
    }

    suspend fun deleteTag(tag: String, deleteAssociatedMemos: Boolean): ApiResponse<Unit> = withContext(viewModelScope.coroutineContext) {
        val response = memoService.getRepository().deleteTag(tag, deleteAssociatedMemos)
        if (response is ApiResponse.Success) {
            loadMemosSnapshot()
            memoService.getRepository().listTags().suspendOnSuccess {
                tags.clear()
                tags.addAll(data)
            }
            WidgetUpdater.updateWidgets(appContext)
            triggerSyncAfterMutation()
        }
        response
    }

    suspend fun updateMemoPinned(memoIdentifier: String, pinned: Boolean) = withContext(viewModelScope.coroutineContext) {
        memoService.getRepository().updateMemo(memoIdentifier, pinned = pinned).suspendOnSuccess {
            updateMemo(data)
            // Update widgets after pinning/unpinning a memo
            WidgetUpdater.updateWidgets(appContext)
            triggerSyncAfterMutation()
        }
    }

    suspend fun editMemo(memoIdentifier: String, content: String, resourceList: List<ResourceEntity>?, visibility: MemoVisibility): ApiResponse<MemoEntity> = withContext(viewModelScope.coroutineContext) {
        memoService.getRepository().updateMemo(memoIdentifier, content, resourceList, visibility).suspendOnSuccess {
            updateMemo(data)
            // Update widgets after editing a memo
            WidgetUpdater.updateWidgets(appContext)
            triggerSyncAfterMutation()
        }
    }

    suspend fun archiveMemo(memoIdentifier: String) = withContext(viewModelScope.coroutineContext) {
        memoService.getRepository().archiveMemo(memoIdentifier).suspendOnSuccess {
            memos.removeIf { it.identifier == memoIdentifier }
            // Update widgets after archiving a memo
            WidgetUpdater.updateWidgets(appContext)
            triggerSyncAfterMutation()
        }
    }

    suspend fun deleteMemo(memoIdentifier: String) = withContext(viewModelScope.coroutineContext) {
        memoService.getRepository().deleteMemo(memoIdentifier).suspendOnSuccess {
            memos.removeIf { it.identifier == memoIdentifier }
            // Update widgets after deleting a memo
            WidgetUpdater.updateWidgets(appContext)
            triggerSyncAfterMutation()
        }
    }

    suspend fun cacheResourceFile(resourceIdentifier: String, downloadedUri: Uri): ApiResponse<Unit> = withContext(viewModelScope.coroutineContext) {
        memoService.getRepository().cacheResourceFile(resourceIdentifier, downloadedUri)
    }

    suspend fun cacheResourceThumbnail(resourceIdentifier: String, downloadedUri: Uri): ApiResponse<Unit> = withContext(viewModelScope.coroutineContext) {
        memoService.getRepository().cacheResourceThumbnail(resourceIdentifier, downloadedUri)
    }

    suspend fun getResourceById(resourceIdentifier: String): ResourceEntity? = withContext(viewModelScope.coroutineContext) {
        when (val response = memoService.getRepository().listResources()) {
            is ApiResponse.Success -> response.data.firstOrNull { it.identifier == resourceIdentifier }
            else -> null
        }
    }

    private fun updateMemo(memo: MemoEntity) {
        val index = memos.indexOfFirst { it.identifier == memo.identifier }
        if (index != -1) {
            memos[index] = memo
        }
    }

    private suspend fun triggerSyncAfterMutation() {
        memoService.requestSync(trigger = SyncTrigger.MUTATION, force = false)
    }

    private fun calculateMatrix(): List<DailyUsageStat> {
        val countMap = HashMap<LocalDate, Int>()

        for (memo in memos) {
            val date = memo.date.atZone(OffsetDateTime.now().offset).toLocalDate()
            countMap[date] = (countMap[date] ?: 0) + 1
        }

        return DailyUsageStat.initialMatrix.map {
            it.copy(count = countMap[it.date] ?: 0)
        }
    }
}

val LocalMemos =
    compositionLocalOf<MemosViewModel> { error(site.lcyk.keer.R.string.memos_view_model_not_found.string) }

sealed class ManualSyncResult {
    object Completed : ManualSyncResult()
    data class Blocked(val message: String) : ManualSyncResult()
    data class Failed(val message: String) : ManualSyncResult()
}
