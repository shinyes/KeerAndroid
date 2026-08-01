package site.lcyk.keer.viewmodel

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.skydoves.sandwich.ApiResponse
import com.skydoves.sandwich.suspendOnSuccess
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import site.lcyk.keer.R
import site.lcyk.keer.data.local.entity.MemoEntity
import site.lcyk.keer.data.local.entity.ResourceEntity
import site.lcyk.keer.data.model.Account
import site.lcyk.keer.data.model.DailyUsageStat
import site.lcyk.keer.data.model.HeatmapTimeline
import site.lcyk.keer.data.model.MemoGroup
import site.lcyk.keer.data.model.MemoVisibility
import site.lcyk.keer.data.model.SyncDomain
import site.lcyk.keer.data.model.SyncStatus
import site.lcyk.keer.data.model.buildDailyUsageMatrixFromDates
import site.lcyk.keer.data.model.buildHeatmapTimeline
import site.lcyk.keer.data.model.isExploreEntryVisible
import site.lcyk.keer.data.repository.JoinedGroupRepository
import site.lcyk.keer.data.repository.UserGeneralSettingsRepository
import site.lcyk.keer.data.service.AccountService
import site.lcyk.keer.data.service.MemoService
import site.lcyk.keer.data.service.OfflineGroupStore
import site.lcyk.keer.data.service.SyncTrigger
import site.lcyk.keer.ext.getErrorMessage
import site.lcyk.keer.ext.string
import site.lcyk.keer.util.ResolvedMemoQuoteProjector
import site.lcyk.keer.util.normalizeCollaboratorId
import site.lcyk.keer.util.resolveMemoGroupExploreEntryId
import site.lcyk.keer.widget.WidgetUpdater
import java.time.LocalDate
import java.time.OffsetDateTime
import timber.log.Timber

data class HomeMemoItem(
    val memo: MemoEntity,
    val groupId: String? = null,
)

private data class MemoQuoteSignature(
    val identifier: String,
    val remoteId: String?,
    val contentHash: Int,
    val tagsHash: Int,
    val quoteSourceKind: String?,
    val quoteSource: String?,
    val quoteStatus: String?,
    val quoteContentPreviewHash: Int,
    val quoteDateEpochMillis: Long?,
    val quoteHasAttachments: Boolean,
)

@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class MemosViewModel @Inject constructor(
    private val memoService: MemoService,
    private val accountService: AccountService,
    private val joinedGroupRepository: JoinedGroupRepository,
    private val userGeneralSettingsRepository: UserGeneralSettingsRepository,
    private val offlineGroupStore: OfflineGroupStore,
    private val uiInteractionGate: UiInteractionGate,
    @param:ApplicationContext private val appContext: Context,
) : ViewModel() {

    private val transientDetailMemos = linkedMapOf<String, MemoEntity>()
    private val feedProjectionStore = FeedProjectionStore(
        scope = viewModelScope,
        idleCommitDelayMillis = SNAPSHOT_IDLE_COMMIT_DELAY_MILLIS,
    )
    private val resolvedQuoteProjector = ResolvedMemoQuoteProjector(
        transientMemoLookup = ::getMemoForDetail,
    )
    private val drawerProjectionStore = DrawerProjectionStore(
        scope = viewModelScope,
        idleCommitDelayMillis = SNAPSHOT_IDLE_COMMIT_DELAY_MILLIS,
    )

    var errorMessage: String? by mutableStateOf(null)
        private set

    private val visibleFeedState: StateFlow<FeedUiState> = feedProjectionStore.visibleState
    val visibleDrawerState: StateFlow<DrawerUiState> = drawerProjectionStore.visibleState

    val visibleMemos: StateFlow<List<MemoEntity>> =
        visibleFeedState
            .map { it.memos }
            .distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val visibleTags: StateFlow<List<String>> =
        visibleFeedState
            .map { it.tags }
            .distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val visibleMatrix: StateFlow<List<DailyUsageStat>> =
        visibleFeedState
            .map { it.matrix }
            .distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.Eagerly, DailyUsageStat.initialMatrix)

    val visibleHomeMemos: StateFlow<List<HomeMemoItem>> =
        visibleFeedState
            .map { it.homeMemos }
            .distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val visibleResolvedQuotes: StateFlow<Map<String, site.lcyk.keer.util.ResolvedMemoQuote>> =
        visibleFeedState
            .map { it.resolvedQuoteByMemoId }
            .distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    val drawerGroups: StateFlow<List<MemoGroup>> =
        visibleDrawerState
            .map { it.drawerGroups }
            .distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val homeMemos: StateFlow<List<HomeMemoItem>> = visibleHomeMemos

    val memos: List<MemoEntity>
        get() = visibleMemos.value

    val tags: List<String>
        get() = visibleTags.value

    val matrix: List<DailyUsageStat>
        get() = visibleMatrix.value

    val host: StateFlow<String?> =
        accountService.currentAccount
            .map { it?.getAccountInfo()?.host }
            .distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val syncStatus: StateFlow<SyncStatus> =
        memoService.syncStatus.stateIn(viewModelScope, SharingStarted.Eagerly, SyncStatus())

    val foregroundSyncUiBusy: StateFlow<Boolean> =
        uiInteractionGate.activeInteractions
            .map { activeByScope ->
                activeByScope.values.any { interactions ->
                    interactions.any { interaction ->
                        interaction in FOREGROUND_SYNC_BLOCKING_INTERACTIONS
                    }
                }
            }
            .distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val projectionMemos: StateFlow<List<MemoEntity>> =
        memoService.memos
            // 同步进行时拉长 debounce，降低初次全量同步期间"每页写入→全链重算"的频率。
            .debounce { _ ->
                if (syncStatus.value.syncing) {
                    SYNCING_PROJECTION_DEBOUNCE_MILLIS
                } else {
                    PROJECTION_MEMO_DEBOUNCE_MILLIS
                }
            }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val liveHomeMemos: StateFlow<List<HomeMemoItem>> =
        accountService.currentAccount
            .flatMapLatest { account ->
                val accountKey = account?.accountKey().orEmpty()
                if (accountKey.isBlank()) {
                    flowOf(emptyList())
                } else {
                    val projector = HomeMemoListProjector()
                    combine(
                        projectionMemos,
                        offlineGroupStore.observeAllCachedGroupMemos(accountKey),
                        offlineGroupStore.observePinnedGroupMemoKeys(accountKey),
                    ) { localMemos, cachedGroupMemos, pinnedGroupMemoKeys ->
                        projector.project(
                            currentUserId = (account as? Account.KeerV2)
                                ?.info
                                ?.id
                                ?.toString()
                                ?.let(::normalizeCollaboratorId),
                            accountKey = accountKey,
                            localMemos = localMemos,
                            cachedGroupMemos = cachedGroupMemos,
                            pinnedGroupMemoKeys = pinnedGroupMemoKeys,
                        )
                    }.flowOn(Dispatchers.Default)
                }
            }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init {
        val liveMemoProjection = projectionMemos
            .map { latestMemos ->
                Triple(
                    latestMemos,
                    latestMemos.map { memo -> memo.identifier to memo.date.toEpochMilli() },
                    latestMemos.map { memo ->
                        MemoQuoteSignature(
                            identifier = memo.identifier,
                            remoteId = memo.remoteId,
                            contentHash = memo.content.hashCode(),
                            tagsHash = memo.tags.hashCode(),
                            quoteSourceKind = memo.quoteSourceKind,
                            quoteSource = memo.quoteSource,
                            quoteStatus = memo.quoteStatus,
                            quoteContentPreviewHash = memo.quoteContentPreview.hashCode(),
                            quoteDateEpochMillis = memo.quoteDate?.toEpochMilli(),
                            quoteHasAttachments = memo.quoteHasAttachments,
                        )
                    },
                )
            }

        val liveHeatmapData = liveMemoProjection
            .map { (latestMemos, matrixSignature, _) -> latestMemos to matrixSignature }
            .distinctUntilChangedBy { (_, matrixSignature) -> matrixSignature }
            .mapLatest { (latestMemos, _) ->
                withContext(Dispatchers.Default) {
                    val matrix = calculateMatrix(latestMemos)
                    matrix to buildHeatmapTimeline(matrix)
                }
            }

        val liveMatrix = liveHeatmapData
            .map { (matrix, _) -> matrix }

        val liveResolvedQuotes = liveMemoProjection
            .map { (latestMemos, _, quoteSignature) -> latestMemos to quoteSignature }
            .distinctUntilChangedBy { (_, quoteSignature) -> quoteSignature }
            .mapLatest { (latestMemos, _) ->
                withContext(Dispatchers.Default) {
                    measureFeedSection("resolved_quotes", latestMemos.size) {
                        resolvedQuoteProjector.project(latestMemos)
                    }
                }
            }

        val liveGeneralSettings = userGeneralSettingsRepository.observeCurrentGeneralSettings()
        val currentUserIdentifier = accountService.currentAccount.map { account ->
            (account as? Account.KeerV2)?.info?.id?.toString()
        }
        val liveDrawerBaseState = combine(
            memoService.tags,
            liveHeatmapData,
            joinedGroupRepository.observeJoinedGroups(),
            liveGeneralSettings,
            joinedGroupRepository.observeGroupIdAliases(),
        ) { latestTags, latestHeatmapData, latestDrawerGroups, generalSettings, groupIdAliases ->
            DrawerBaseState(
                tags = latestTags,
                matrix = latestHeatmapData.first,
                heatmapTimeline = latestHeatmapData.second,
                groups = latestDrawerGroups,
                generalSettings = generalSettings,
                groupIdAliases = groupIdAliases,
            )
        }

        viewModelScope.launch {
            combine(
                projectionMemos,
                memoService.tags,
                liveMatrix,
                liveHomeMemos,
                liveResolvedQuotes,
            ) { latestMemos, latestTags, latestMatrix, latestHomeMemos, resolvedQuotes ->
                FeedUiState(
                    memos = latestMemos,
                    tags = latestTags,
                    matrix = latestMatrix,
                    homeMemos = latestHomeMemos,
                    resolvedQuoteByMemoId = resolvedQuotes,
                )
            }
                .flowOn(Dispatchers.Default)
                .conflate()
                .collectLatest { latestState ->
                measureFeedSection(
                    section = "feed_state_commit",
                    memoCount = latestState.memos.size,
                ) {
                    feedProjectionStore.updateLiveState(latestState)
                }
            }
        }

        viewModelScope.launch {
            combine(
                liveDrawerBaseState,
                currentUserIdentifier,
            ) { baseState, currentUserId ->
                val visibleDrawerGroups = baseState.groups.filter { group ->
                    val exploreEntryId = resolveMemoGroupExploreEntryId(
                        group = group,
                        currentUserIdentifier = currentUserId,
                    )
                    baseState.generalSettings.isExploreEntryVisible(exploreEntryId)
                }
                val visibleColumns = baseState.generalSettings.memoColumns.filter { column ->
                    column.visibleInDrawer
                }
                DrawerUiState(
                    tags = baseState.tags,
                    matrix = baseState.matrix,
                    heatmapTimeline = baseState.heatmapTimeline,
                    drawerGroups = visibleDrawerGroups,
                    visibleColumns = visibleColumns,
                    groupIdAliases = baseState.groupIdAliases,
                )
            }
                .flowOn(Dispatchers.Default)
                .conflate()
                .collectLatest { latestDrawerState ->
                    drawerProjectionStore.updateLiveState(latestDrawerState)
                }
        }

        SyncFreezeController(
            scope = viewModelScope,
            interactionFrozen = uiInteractionGate.observeScopeFrozen(MemoUiScope.FEED),
            onFrozenChanged = feedProjectionStore::setFrozen,
        )
        SyncFreezeController(
            scope = viewModelScope,
            interactionFrozen = uiInteractionGate.observeScopeFrozen(MemoUiScope.DRAWER),
            onFrozenChanged = drawerProjectionStore::setFrozen,
        )

    }

    suspend fun refreshLocalSnapshot() = Unit

    suspend fun loadMemos(
        syncAfterLoad: Boolean = true,
        trigger: SyncTrigger = SyncTrigger.AUTO,
        domains: Set<SyncDomain> = setOf(SyncDomain.MEMOS),
        bypassCoalesce: Boolean = false,
    ) = withContext(viewModelScope.coroutineContext) {
        if (syncAfterLoad) {
            memoService.requestSync(
                trigger = trigger,
                force = trigger == SyncTrigger.MANUAL,
                domains = domains,
                bypassCoalesce = bypassCoalesce,
            )
        }
    }

    suspend fun requestSync(
        trigger: SyncTrigger,
        domains: Set<SyncDomain>,
        force: Boolean = false,
        bypassCoalesce: Boolean = false,
    ) = withContext(viewModelScope.coroutineContext) {
        if (domains.isEmpty()) {
            return@withContext
        }
        memoService.requestSync(
            trigger = trigger,
            force = force,
            domains = domains,
            bypassCoalesce = bypassCoalesce,
        )
    }

    suspend fun refreshMemos(): ManualSyncResult = withContext(viewModelScope.coroutineContext) {
        performManualSync(domains = setOf(SyncDomain.MEMOS))
    }

    suspend fun refreshHomeFeed(): ManualSyncResult = withContext(viewModelScope.coroutineContext) {
        performManualSync(domains = setOf(SyncDomain.MEMOS))
    }

    suspend fun refreshExploreFeed(): ManualSyncResult = withContext(viewModelScope.coroutineContext) {
        performManualSync(domains = setOf(SyncDomain.MEMOS))
    }

    fun loadTags() = Unit

    suspend fun renameTag(oldTag: String, newTag: String): ApiResponse<Unit> =
        withContext(Dispatchers.IO) {
            val response = memoService.getRepository().renameTag(oldTag, newTag)
            if (response is ApiResponse.Success) {
                userGeneralSettingsRepository.renameTagDrawerEntries(
                    oldTag = oldTag,
                    newTag = newTag,
                )
                errorMessage = null
                WidgetUpdater.updateWidgets(appContext)
            } else {
                errorMessage = response.getErrorMessage()
            }
            response
        }

    suspend fun deleteTag(tag: String, deleteAssociatedMemos: Boolean): ApiResponse<Unit> =
        withContext(Dispatchers.IO) {
            val response = memoService.getRepository().deleteTag(tag, deleteAssociatedMemos)
            if (response is ApiResponse.Success) {
                userGeneralSettingsRepository.removeTagDrawerEntries(tag)
                errorMessage = null
                WidgetUpdater.updateWidgets(appContext)
            } else {
                errorMessage = response.getErrorMessage()
            }
            response
        }

    suspend fun updateMemoPinned(memoIdentifier: String, pinned: Boolean) =
        withContext(Dispatchers.IO) {
            memoService.getRepository().updateMemo(memoIdentifier, pinned = pinned).suspendOnSuccess {
                WidgetUpdater.updateWidgets(appContext)
            }
        }

    suspend fun editMemo(
        memoIdentifier: String,
        content: String,
        resourceList: List<ResourceEntity>?,
        visibility: MemoVisibility,
    ): ApiResponse<MemoEntity> = withContext(Dispatchers.IO) {
        memoService.getRepository().updateMemo(
            identifier = memoIdentifier,
            content = content,
            resources = resourceList,
            visibility = visibility,
        ).suspendOnSuccess {
            WidgetUpdater.updateWidgets(appContext)
        }
    }

    suspend fun archiveMemo(memoIdentifier: String) = withContext(Dispatchers.IO) {
        memoService.getRepository().archiveMemo(memoIdentifier).suspendOnSuccess {
            WidgetUpdater.updateWidgets(appContext)
        }
    }

    suspend fun deleteMemo(memoIdentifier: String) = withContext(Dispatchers.IO) {
        memoService.getRepository().deleteMemo(memoIdentifier).suspendOnSuccess {
            WidgetUpdater.updateWidgets(appContext)
        }
    }

    suspend fun cacheResourceFile(resourceIdentifier: String, downloadedUri: Uri): ApiResponse<Unit> =
        withContext(Dispatchers.IO) {
            memoService.getRepository().cacheResourceFile(resourceIdentifier, downloadedUri)
        }

    suspend fun cacheResourceThumbnail(resourceIdentifier: String, downloadedUri: Uri): ApiResponse<Unit> =
        withContext(Dispatchers.IO) {
            memoService.getRepository().cacheResourceThumbnail(resourceIdentifier, downloadedUri)
        }

        suspend fun updateResourceThumbnail(resourceIdentifier: String, thumbnailUri: String): ApiResponse<Unit> =
            withContext(Dispatchers.IO) {
                memoService.getRepository().updateResourceThumbnail(resourceIdentifier, thumbnailUri)
            }

    suspend fun getResourceById(resourceIdentifier: String): ResourceEntity? =
        withContext(Dispatchers.IO) {
            memoService.getRepository().getResourceById(resourceIdentifier)
        }

    fun observeResource(resourceIdentifier: String) =
        memoService.observeResource(resourceIdentifier)

    fun setInteractionActive(scope: MemoUiScope, type: UiInteractionType, active: Boolean) {
        uiInteractionGate.setActive(scope, type, active)
        }

    fun observeScopeFrozen(scope: MemoUiScope) = uiInteractionGate.observeScopeFrozen(scope)

    fun preloadDrawerState() {
        drawerProjectionStore.preloadVisibleState()
    }

    fun cacheMemoForDetail(memo: MemoEntity) {
        transientDetailMemos[memo.identifier] = memo
        if (transientDetailMemos.size > MAX_TRANSIENT_DETAIL_MEMO_COUNT) {
            val eldestKey = transientDetailMemos.keys.firstOrNull() ?: return
            transientDetailMemos.remove(eldestKey)
        }
    }

    fun getMemoForDetail(memoIdentifier: String): MemoEntity? {
        return visibleMemos.value.firstOrNull { it.identifier == memoIdentifier }
            ?: transientDetailMemos[memoIdentifier]
    }

    private suspend fun performManualSync(domains: Set<SyncDomain>): ManualSyncResult {
        val syncResult = memoService.sync(
            force = true,
            trigger = SyncTrigger.MANUAL,
            domains = domains,
        )
        if (syncResult is ApiResponse.Success) {
            WidgetUpdater.updateWidgets(appContext)
            errorMessage = null
            return ManualSyncResult.Completed
        }
        val message = syncResult.getErrorMessage()
        errorMessage = message
        return ManualSyncResult.Failed(message)
    }

    private fun calculateMatrix(sourceMemos: List<MemoEntity>): List<DailyUsageStat> {
        val zoneOffset = OffsetDateTime.now().offset
        val dates = sourceMemos.map { memo ->
            memo.date.atZone(zoneOffset).toLocalDate()
        }
        return buildDailyUsageMatrixFromDates(
            dates = dates,
            today = LocalDate.now(),
            emptyFallback = DailyUsageStat.initialMatrix,
        )
    }

    private inline fun <T> measureFeedSection(
        section: String,
        memoCount: Int,
        block: () -> T,
    ): T {
        val startNanos = SystemClock.elapsedRealtimeNanos()
        val result = block()
        val elapsedMillis = (SystemClock.elapsedRealtimeNanos() - startNanos) / 1_000_000L
        if (elapsedMillis >= FEED_PROFILE_LOG_THRESHOLD_MILLIS) {
            Timber.tag(FEED_PROFILE_TAG).d(
                "section=%s elapsedMs=%d memos=%d",
                section,
                elapsedMillis,
                memoCount,
            )
        }
        return result
    }

    private companion object {
        private const val MAX_TRANSIENT_DETAIL_MEMO_COUNT = 200
        private const val FEED_PROFILE_LOG_THRESHOLD_MILLIS = 12L
        private const val PROJECTION_MEMO_DEBOUNCE_MILLIS = 64L
        private const val SYNCING_PROJECTION_DEBOUNCE_MILLIS = 256L
        private const val SNAPSHOT_IDLE_COMMIT_DELAY_MILLIS = 300L
        private const val FEED_PROFILE_TAG = "FeedProfile"
        private val FOREGROUND_SYNC_BLOCKING_INTERACTIONS = setOf(
            UiInteractionType.LIST_SCROLL,
            UiInteractionType.PULL_REFRESH,
            UiInteractionType.DRAWER_TRANSITION,
        )
    }
}

private data class DrawerBaseState(
    val tags: List<String>,
    val matrix: List<DailyUsageStat>,
    val heatmapTimeline: HeatmapTimeline,
    val groups: List<MemoGroup>,
    val generalSettings: site.lcyk.keer.data.model.UserGeneralSettings,
    val groupIdAliases: List<site.lcyk.keer.data.model.GroupIdAlias>,
)

val LocalMemos =
    compositionLocalOf<MemosViewModel> { error(site.lcyk.keer.R.string.memos_view_model_not_found.string) }

sealed class ManualSyncResult {
    object Completed : ManualSyncResult()
    data class Blocked(val message: String) : ManualSyncResult()
    data class Failed(val message: String) : ManualSyncResult()
}
