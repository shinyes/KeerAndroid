package site.lcyk.keer.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.skydoves.sandwich.ApiResponse
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import site.lcyk.keer.R
import site.lcyk.keer.data.model.Memo
import site.lcyk.keer.data.model.SyncDomain
import site.lcyk.keer.data.service.AccountService
import site.lcyk.keer.data.service.MemoService
import site.lcyk.keer.data.service.OfflineGroupStore
import site.lcyk.keer.data.service.SyncTrigger
import site.lcyk.keer.ext.getErrorMessage
import site.lcyk.keer.ext.string
import site.lcyk.keer.util.normalizeCollaboratorId
import site.lcyk.keer.util.normalizeTagList
import site.lcyk.keer.util.ResolvedMemoQuoteProjector

data class ExploreMemoItem(
    val memo: Memo,
    val groupId: String? = null,
)

@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class ExploreViewModel @Inject constructor(
    private val accountService: AccountService,
    private val memoService: MemoService,
    private val offlineGroupStore: OfflineGroupStore,
    private val uiInteractionGate: UiInteractionGate,
) : ViewModel() {
    private val snapshotStore = InteractionSnapshotStore(
        scope = viewModelScope,
        initialState = ExploreUiState(),
        idleCommitDelayMillis = SNAPSHOT_IDLE_COMMIT_DELAY_MILLIS,
    )
    private val _mutationErrorMessage = MutableStateFlow<String?>(null)
    val mutationErrorMessage: StateFlow<String?> = _mutationErrorMessage.asStateFlow()

    val groups = accountService.currentAccount
        .flatMapLatest { account ->
            val accountKey = account?.accountKey().orEmpty()
            if (accountKey.isBlank()) {
                emptyFlow()
            } else {
                offlineGroupStore.observeGroups(accountKey)
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val projectionMemos = memoService.memos
        .debounce(EXPLORE_PROJECTION_MEMO_DEBOUNCE_MILLIS)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val liveItems = accountService.currentAccount
        .flatMapLatest { account ->
            val accountKey = account?.accountKey().orEmpty()
            if (accountKey.isBlank()) {
                return@flatMapLatest flowOf(emptyList())
            }
            val projector = ExploreMemoListProjector()
            combine(
                projectionMemos,
                offlineGroupStore.observeAllCachedGroupMemos(accountKey),
                offlineGroupStore.observePinnedGroupMemoKeys(accountKey),
            ) { localMemos, cachedGroupMemos, pinnedGroupMemoKeys ->
                projector.project(
                    currentUserId = (account as? site.lcyk.keer.data.model.Account.KeerV2)
                        ?.info
                        ?.id
                        ?.toString()
                        ?.let(::normalizeCollaboratorId),
                    localMemos = localMemos,
                    cachedGroupMemos = cachedGroupMemos,
                    pinnedGroupMemoKeys = pinnedGroupMemoKeys,
                )
            }.flowOn(Dispatchers.Default)
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val liveResolvedQuotes = accountService.currentAccount
        .flatMapLatest { account ->
            val accountKey = account?.accountKey().orEmpty()
            if (accountKey.isBlank()) {
                return@flatMapLatest flowOf(emptyMap())
            }
            val memoProjector = ExploreQuoteMemoProjector()
            val resolvedQuoteProjector = ResolvedMemoQuoteProjector()
            liveItems
                .mapLatest { items ->
                    withContext(Dispatchers.Default) {
                        val quoteCandidates = memoProjector.project(
                            accountKey = accountKey,
                            items = items,
                        )
                        resolvedQuoteProjector.project(quoteCandidates)
                    }
                }
                .flowOn(Dispatchers.Default)
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    val visibleItems: StateFlow<List<ExploreMemoItem>> =
        snapshotStore.visibleState
            .map { it.items }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val visibleResolvedQuotes: StateFlow<Map<String, site.lcyk.keer.util.ResolvedMemoQuote>> =
        snapshotStore.visibleState
            .map { it.resolvedQuoteByMemoId }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    init {
        viewModelScope.launch {
            combine(
                liveItems,
                liveResolvedQuotes,
            ) { items, resolvedQuotes ->
                ExploreUiState(
                    items = items,
                    resolvedQuoteByMemoId = resolvedQuotes,
                )
            }.collectLatest { uiState ->
                snapshotStore.updateLiveState(uiState)
            }
        }

        SyncFreezeController(
            scope = viewModelScope,
            interactionFrozen = uiInteractionGate.observeScopeFrozen(MemoUiScope.EXPLORE),
            onFrozenChanged = snapshotStore::setFrozen,
        )
    }

    suspend fun updateExploreMemo(
        item: ExploreMemoItem,
        content: String,
        tags: List<String>,
    ): Boolean = withContext(Dispatchers.IO) {
        val remoteRepository = accountService.getRemoteRepository() ?: run {
            _mutationErrorMessage.value = R.string.current_account_no_remote_memo_operations.string
            return@withContext false
        }
        val normalizedContent = content.trim()
        if (normalizedContent.isEmpty()) {
            return@withContext false
        }
        val normalizedTags = normalizeTagList(tags)
        val response = if (item.groupId.isNullOrBlank()) {
            remoteRepository.updateMemo(
                remoteId = item.memo.remoteId,
                content = normalizedContent,
                tags = normalizedTags,
            )
        } else {
            remoteRepository.updateGroupMessage(
                groupId = item.groupId,
                messageRemoteId = item.memo.remoteId,
                content = normalizedContent,
                tags = normalizedTags,
            )
        }
        return@withContext when (response) {
            is ApiResponse.Success -> {
                _mutationErrorMessage.value = null
                enqueueExploreRefresh(item.groupId)
                true
            }
            else -> {
                _mutationErrorMessage.value = response.getErrorMessage()
                false
            }
        }
    }

    suspend fun deleteExploreMemo(item: ExploreMemoItem): Boolean = withContext(Dispatchers.IO) {
        val remoteRepository = accountService.getRemoteRepository() ?: run {
            _mutationErrorMessage.value = R.string.current_account_no_remote_memo_operations.string
            return@withContext false
        }
        val response = if (item.groupId.isNullOrBlank()) {
            remoteRepository.deleteMemo(item.memo.remoteId)
        } else {
            remoteRepository.deleteGroupMessage(item.groupId, item.memo.remoteId)
        }
        return@withContext when (response) {
            is ApiResponse.Success -> {
                _mutationErrorMessage.value = null
                enqueueExploreRefresh(item.groupId)
                true
            }
            else -> {
                _mutationErrorMessage.value = response.getErrorMessage()
                false
            }
        }
    }

    fun clearMutationError() {
        _mutationErrorMessage.value = null
    }

    suspend fun refreshExploreMemos() = withContext(Dispatchers.IO) {
        val refreshResponse = memoService.sync(
            force = true,
            trigger = SyncTrigger.MANUAL,
            domains = setOf(SyncDomain.MEMOS, SyncDomain.GROUPS),
        )
        if (refreshResponse !is ApiResponse.Success) {
            _mutationErrorMessage.value = refreshResponse.getErrorMessage()
        }
    }

    private fun enqueueExploreRefresh(groupId: String?) {
        viewModelScope.launch {
            if (groupId.isNullOrBlank()) {
                memoService.requestSync(
                    trigger = SyncTrigger.MANUAL,
                    force = true,
                    domains = setOf(SyncDomain.MEMOS),
                )
            } else {
                memoService.requestSync(
                    trigger = SyncTrigger.MANUAL,
                    force = true,
                    domains = setOf(SyncDomain.GROUPS),
                    groupId = groupId,
                )
            }
        }
    }

    private companion object {
        private const val EXPLORE_PROJECTION_MEMO_DEBOUNCE_MILLIS = 64L
        private const val SNAPSHOT_IDLE_COMMIT_DELAY_MILLIS = 300L
    }
}
