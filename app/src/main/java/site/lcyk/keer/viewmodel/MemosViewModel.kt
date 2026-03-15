package site.lcyk.keer.viewmodel

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.skydoves.sandwich.ApiResponse
import com.skydoves.sandwich.suspendOnSuccess
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import site.lcyk.keer.R
import site.lcyk.keer.data.local.entity.MemoEntity
import site.lcyk.keer.data.local.entity.ResourceEntity
import site.lcyk.keer.data.model.Account
import site.lcyk.keer.data.model.toMemo
import site.lcyk.keer.data.model.DailyUsageStat
import site.lcyk.keer.data.model.MemoVisibility
import site.lcyk.keer.data.model.SyncDomain
import site.lcyk.keer.data.model.SyncStatus
import site.lcyk.keer.data.service.AccountService
import site.lcyk.keer.data.service.MemoService
import site.lcyk.keer.data.service.OfflineGroupStore
import site.lcyk.keer.data.service.SyncTrigger
import site.lcyk.keer.data.repository.JoinedGroupRepository
import site.lcyk.keer.ext.getErrorMessage
import site.lcyk.keer.ext.string
import site.lcyk.keer.util.normalizeCollaboratorId
import site.lcyk.keer.util.toMemoEntityForCard
import site.lcyk.keer.widget.WidgetUpdater
import java.time.LocalDate
import java.time.OffsetDateTime
import javax.inject.Inject

data class HomeMemoItem(
    val memo: MemoEntity,
    val groupId: String? = null,
)

@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class MemosViewModel @Inject constructor(
    private val memoService: MemoService,
    private val accountService: AccountService,
    private val joinedGroupRepository: JoinedGroupRepository,
    private val offlineGroupStore: OfflineGroupStore,
    @param:ApplicationContext private val appContext: Context
) : ViewModel() {

    var memos: List<MemoEntity> by mutableStateOf(emptyList())
        private set
    private val transientDetailMemos = linkedMapOf<String, MemoEntity>()
    var tags: List<String> by mutableStateOf(emptyList())
        private set
    var errorMessage: String? by mutableStateOf(null)
        private set
    var matrix: List<DailyUsageStat> by mutableStateOf(DailyUsageStat.initialMatrix)
        private set
    private val interactionGate = UiInteractionGate()
    private val snapshotStore = MemosUiSnapshotStore()
    private val _homeMemos = MutableStateFlow(emptyList<HomeMemoItem>())
    private val _drawerGroups = MutableStateFlow(emptyList<site.lcyk.keer.data.model.MemoGroup>())

    val host: StateFlow<String?> =
        accountService.currentAccount
            .map { it?.getAccountInfo()?.host }
            .distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val syncStatus: StateFlow<SyncStatus> =
        memoService.syncStatus.stateIn(viewModelScope, SharingStarted.Eagerly, SyncStatus())

    private val liveHomeMemos: StateFlow<List<HomeMemoItem>> =
        accountService.currentAccount
            .flatMapLatest { account ->
                val accountKey = account?.accountKey().orEmpty()
                if (accountKey.isBlank()) {
                    flowOf(emptyList())
                } else {
                    combine(
                        memoService.memos,
                        offlineGroupStore.observeAllCachedGroupMemos(accountKey),
                        offlineGroupStore.observePinnedGroupMemoKeys(accountKey),
                    ) { localMemos, cachedGroupMemos, pinnedGroupMemoKeys ->
                        buildHomeMemoItems(
                            account = account,
                            accountKey = accountKey,
                            localMemos = localMemos,
                            cachedGroupMemos = cachedGroupMemos,
                            pinnedGroupMemoKeys = pinnedGroupMemoKeys,
                        )
                    }.flowOn(Dispatchers.Default)
                }
            }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val homeMemos: StateFlow<List<HomeMemoItem>> = _homeMemos.asStateFlow()
    val drawerGroups: StateFlow<List<site.lcyk.keer.data.model.MemoGroup>> = _drawerGroups.asStateFlow()

    init {
        val liveMatrix = memoService.memos
            .mapLatest { latestMemos ->
                withContext(Dispatchers.Default) {
                    calculateMatrix(latestMemos)
                }
            }

        viewModelScope.launch {
            combine(
                memoService.memos,
                memoService.tags,
                liveMatrix,
                liveHomeMemos,
                joinedGroupRepository.observeJoinedGroups(),
            ) { latestMemos, latestTags, latestMatrix, latestHomeMemos, latestDrawerGroups ->
                MemoFeedUiState(
                    memos = latestMemos,
                    tags = latestTags,
                    matrix = latestMatrix,
                    homeMemos = latestHomeMemos,
                    drawerGroups = latestDrawerGroups,
                )
            }.collectLatest { latestState ->
                snapshotStore.updateLiveState(latestState)
            }
        }

        viewModelScope.launch {
            interactionGate.activeInteractions.collectLatest { activeInteractions ->
                snapshotStore.setFrozen(activeInteractions.isNotEmpty())
            }
        }

        viewModelScope.launch {
            snapshotStore.visibleState.collectLatest { visibleState ->
                applyMemos(visibleState.memos)
                applyTags(visibleState.tags)
                if (matrix != visibleState.matrix) {
                    matrix = visibleState.matrix
                }
                if (_homeMemos.value != visibleState.homeMemos) {
                    _homeMemos.value = visibleState.homeMemos
                }
                if (_drawerGroups.value != visibleState.drawerGroups) {
                    _drawerGroups.value = visibleState.drawerGroups
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
        if (memos.contentDeepEquals(latestMemos)) {
            errorMessage = null
            return
        }
        memos = latestMemos
        errorMessage = null
    }

    private fun applyTags(latestTags: List<String>) {
        if (tags == latestTags) {
            return
        }
        tags = latestTags
    }

    suspend fun loadMemos(
        syncAfterLoad: Boolean = true,
        trigger: SyncTrigger = SyncTrigger.AUTO
    ) = withContext(viewModelScope.coroutineContext) {
        if (syncAfterLoad) {
            val domains = when (trigger) {
                SyncTrigger.APP_START,
                SyncTrigger.APP_FOREGROUND -> site.lcyk.keer.data.service.SyncCoordinator.FULL_DOMAINS
                else -> setOf(SyncDomain.MEMOS)
            }
            memoService.requestSync(trigger = trigger, force = false, domains = domains)
        }
    }

    suspend fun refreshMemos(): ManualSyncResult = withContext(viewModelScope.coroutineContext) {
        performManualSync(domains = setOf(SyncDomain.MEMOS))
    }

    suspend fun refreshHomeFeed(): ManualSyncResult = withContext(viewModelScope.coroutineContext) {
        performManualSync(
            domains = setOf(
                SyncDomain.MEMOS,
                SyncDomain.GROUPS
            )
        )
    }

    suspend fun refreshExploreFeed(): ManualSyncResult = withContext(viewModelScope.coroutineContext) {
        performManualSync(
            domains = setOf(
                SyncDomain.MEMOS,
                SyncDomain.GROUPS
            )
        )
    }

    fun loadTags() = viewModelScope.launch {
        memoService.getRepository().listTags().suspendOnSuccess {
            applyTags(data)
        }
    }

    suspend fun renameTag(oldTag: String, newTag: String): ApiResponse<Unit> = withContext(viewModelScope.coroutineContext) {
        val response = memoService.getRepository().renameTag(oldTag, newTag)
        if (response is ApiResponse.Success) {
            loadMemosSnapshot()
            memoService.getRepository().listTags().suspendOnSuccess {
                applyTags(data)
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
                applyTags(data)
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
        memoService.getRepository().updateMemo(
            identifier = memoIdentifier,
            content = content,
            resources = resourceList,
            visibility = visibility
        ).suspendOnSuccess {
            updateMemo(data)
            // Update widgets after editing a memo
            WidgetUpdater.updateWidgets(appContext)
            triggerSyncAfterMutation()
        }
    }

    suspend fun archiveMemo(memoIdentifier: String) = withContext(viewModelScope.coroutineContext) {
        memoService.getRepository().archiveMemo(memoIdentifier).suspendOnSuccess {
            memos = memos.filterNot { it.identifier == memoIdentifier }
            // Update widgets after archiving a memo
            WidgetUpdater.updateWidgets(appContext)
            triggerSyncAfterMutation()
        }
    }

    suspend fun deleteMemo(memoIdentifier: String) = withContext(viewModelScope.coroutineContext) {
        memoService.getRepository().deleteMemo(memoIdentifier).suspendOnSuccess {
            memos = memos.filterNot { it.identifier == memoIdentifier }
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
        memoService.getRepository().getResourceById(resourceIdentifier)
    }

    fun observeResource(resourceIdentifier: String) =
        memoService.observeResource(resourceIdentifier)

    fun setInteractionActive(type: UiInteractionType, active: Boolean) {
        interactionGate.setActive(type, active)
    }

    private fun updateMemo(memo: MemoEntity) {
        if (memos.none { it.identifier == memo.identifier }) {
            return
        }
        memos = memos.map { existing ->
            if (existing.identifier == memo.identifier) memo else existing
        }
    }

    fun cacheMemoForDetail(memo: MemoEntity) {
        transientDetailMemos[memo.identifier] = memo
        if (transientDetailMemos.size > MAX_TRANSIENT_DETAIL_MEMO_COUNT) {
            val eldestKey = transientDetailMemos.keys.firstOrNull() ?: return
            transientDetailMemos.remove(eldestKey)
        }
    }

    fun getMemoForDetail(memoIdentifier: String): MemoEntity? {
        return memos.firstOrNull { it.identifier == memoIdentifier }
            ?: transientDetailMemos[memoIdentifier]
    }

    private suspend fun triggerSyncAfterMutation() {
        memoService.requestSync(
            trigger = SyncTrigger.MUTATION,
            force = false,
            domains = setOf(SyncDomain.MEMOS)
        )
    }

    private suspend fun performManualSync(
        domains: Set<SyncDomain>
    ): ManualSyncResult {
        val syncResult = memoService.sync(
            force = true,
            trigger = SyncTrigger.MANUAL,
            domains = domains
        )
        if (syncResult is ApiResponse.Success) {
            WidgetUpdater.updateWidgets(appContext)
            return ManualSyncResult.Completed
        }
        val message = syncResult.getErrorMessage()
        errorMessage = message
        return ManualSyncResult.Failed(message)
    }

    private fun calculateMatrix(sourceMemos: List<MemoEntity>): List<DailyUsageStat> {
        val countMap = HashMap<LocalDate, Int>()

        for (memo in sourceMemos) {
            val date = memo.date.atZone(OffsetDateTime.now().offset).toLocalDate()
            countMap[date] = (countMap[date] ?: 0) + 1
        }

        return DailyUsageStat.initialMatrix.map {
            it.copy(count = countMap[it.date] ?: 0)
        }
    }

    private companion object {
        private const val MAX_TRANSIENT_DETAIL_MEMO_COUNT = 200
    }

    private fun List<MemoEntity>.contentDeepEquals(other: List<MemoEntity>): Boolean {
        if (size != other.size) {
            return false
        }
        return indices.all { index ->
            this[index].sameUiContent(other[index])
        }
    }

    private fun MemoEntity.sameUiContent(other: MemoEntity): Boolean {
        return identifier == other.identifier &&
            remoteId == other.remoteId &&
            accountKey == other.accountKey &&
            content == other.content &&
            date == other.date &&
            visibility == other.visibility &&
            pinned == other.pinned &&
            archived == other.archived &&
            latitude == other.latitude &&
            longitude == other.longitude &&
            quoteSourceKind == other.quoteSourceKind &&
            quoteSource == other.quoteSource &&
            quoteStatus == other.quoteStatus &&
            quoteContentPreview == other.quoteContentPreview &&
            quoteDate == other.quoteDate &&
            quoteHasAttachments == other.quoteHasAttachments &&
            needsSync == other.needsSync &&
            isDeleted == other.isDeleted &&
            lastModified == other.lastModified &&
            lastSyncedAt == other.lastSyncedAt &&
            tags == other.tags &&
            resources == other.resources
    }

    private fun buildHomeMemoItems(
        account: Account?,
        accountKey: String,
        localMemos: List<MemoEntity>,
        cachedGroupMemos: List<Pair<site.lcyk.keer.data.model.CachedMemoItem, String>>,
        pinnedGroupMemoKeys: Set<String>,
    ): List<HomeMemoItem> {
        val currentUserId = (account as? Account.KeerV2)
            ?.info
            ?.id
            ?.toString()
            ?.let(::normalizeCollaboratorId)
        val personalItems = localMemos.map { memo -> HomeMemoItem(memo = memo) }
        val groupItems = if (currentUserId.isNullOrBlank()) {
            emptyList()
        } else {
            cachedGroupMemos.mapNotNull { (cachedMemo, groupId) ->
                val creatorId = normalizeCollaboratorId(cachedMemo.creatorId.orEmpty())
                if (creatorId != currentUserId) {
                    return@mapNotNull null
                }
                val resolvedMemo = cachedMemo.toMemo().let { memo ->
                    val pinned = groupMemoKey(groupId, memo.remoteId) in pinnedGroupMemoKeys
                    if (memo.pinned == pinned) memo else memo.copy(pinned = pinned)
                }
                val syncedAt = resolvedMemo.updatedAt ?: resolvedMemo.date
                HomeMemoItem(
                    memo = resolvedMemo.toMemoEntityForCard(
                        identifier = "group:$groupId:${resolvedMemo.remoteId}",
                        accountKey = accountKey,
                        needsSync = false,
                        lastModified = syncedAt,
                        lastSyncedAt = syncedAt,
                    ),
                    groupId = groupId,
                )
            }
        }
        return (personalItems + groupItems)
            .distinctBy { item -> item.memo.identifier }
            .sortedWith(
                compareByDescending<HomeMemoItem> { it.memo.pinned }
                    .thenByDescending { it.memo.date }
            )
    }

    private fun groupMemoKey(groupId: String, memoRemoteId: String): String {
        return "$groupId|$memoRemoteId"
    }
}

val LocalMemos =
    compositionLocalOf<MemosViewModel> { error(site.lcyk.keer.R.string.memos_view_model_not_found.string) }

sealed class ManualSyncResult {
    object Completed : ManualSyncResult()
    data class Blocked(val message: String) : ManualSyncResult()
    data class Failed(val message: String) : ManualSyncResult()
}
