package site.lcyk.keer.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.skydoves.sandwich.suspendOnSuccess
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import site.lcyk.keer.data.local.entity.ResourceEntity
import site.lcyk.keer.data.service.AccountService
import site.lcyk.keer.data.service.MemoService
import site.lcyk.keer.util.partitionResourcesForDisplay
import javax.inject.Inject

@HiltViewModel
class ResourceListViewModel @Inject constructor(
    private val memoService: MemoService,
    private val accountService: AccountService,
    private val uiProjectionEngine: UiProjectionEngine,
) : ViewModel() {
    private var remoteSnapshot: List<ResourceEntity> = emptyList()
    private var localResources: List<ResourceEntity> = emptyList()
    private var currentAccountKey: String = ""
    private var placeholderGuardUntilMillis: Long = 0L
    private val _hydrationState = MutableStateFlow(UiHydrationState())
    val hydrationState: StateFlow<UiHydrationState> = _hydrationState.asStateFlow()
    private val _visibleListState = MutableStateFlow(ResourceListUiState())
    val visibleListState: StateFlow<ResourceListUiState> = _visibleListState.asStateFlow()

    init {
        viewModelScope.launch {
            accountService.currentAccount
                .map { account -> account?.accountKey().orEmpty() }
                .distinctUntilChanged()
                .collectLatest { accountKey ->
                    currentAccountKey = accountKey
                    restoreWarmSnapshot(accountKey)
                }
        }

        viewModelScope.launch {
            memoService.resources.collectLatest { latestResources ->
                localResources = latestResources
                publishMergedResources()
            }
        }
    }

    fun loadResources() = viewModelScope.launch {
        memoService.getRepository().listResources().suspendOnSuccess {
            remoteSnapshot = data.sortedByDescending { it.date }
            publishMergedResources()
        }
    }

    private fun publishMergedResources() {
        val merged = mergeRemoteAndLocalResources(
            remoteSnapshot = remoteSnapshot,
            localResources = localResources,
        )
        if (shouldSkipPlaceholderCommit(merged)) {
            return
        }
        viewModelScope.launch {
            val nextState = withContext(Dispatchers.Default) {
                buildResourceListUiState(merged)
            }
            _visibleListState.value = nextState
            if (currentAccountKey.isNotBlank()) {
                uiProjectionEngine.saveResourceListSnapshot(currentAccountKey, nextState)
            }
            _hydrationState.value = buildHydrationState(
                hasWarmSnapshot = true,
                isHydrating = false,
            )
        }
    }

    private fun mergeRemoteAndLocalResources(
        remoteSnapshot: List<ResourceEntity>,
        localResources: List<ResourceEntity>,
    ): List<ResourceEntity> {
        if (remoteSnapshot.isEmpty()) {
            return localResources.sortedByDescending { it.date }
        }
        val localByIdentifier = localResources.associateBy { it.identifier }
        val localByRemoteId = localResources
            .mapNotNull { resource -> resource.remoteId?.let { remoteId -> remoteId to resource } }
            .toMap()
        val mergedRemote = remoteSnapshot.map { resource ->
            val local = localByIdentifier[resource.identifier]
                ?: resource.remoteId?.let(localByRemoteId::get)
            if (local == null) {
                resource
            } else {
                resource.copy(
                    localUri = local.localUri ?: resource.localUri,
                    thumbnailLocalUri = local.thumbnailLocalUri ?: resource.thumbnailLocalUri,
                    memoId = local.memoId ?: resource.memoId,
                )
            }
        }
        val localDrafts = localResources.filter { it.remoteId == null }
        return (mergedRemote + localDrafts)
            .distinctBy { it.identifier }
            .sortedByDescending { it.date }
    }

    private suspend fun restoreWarmSnapshot(accountKey: String) {
        placeholderGuardUntilMillis = 0L
        remoteSnapshot = emptyList()
        if (accountKey.isBlank()) {
            _visibleListState.value = ResourceListUiState()
            _hydrationState.value = UiHydrationState(isHydrating = false)
            return
        }

        val snapshot = uiProjectionEngine.readResourceListSnapshot(accountKey)
        val now = System.currentTimeMillis()
        if (snapshot != null) {
            _visibleListState.value = snapshot.state
            placeholderGuardUntilMillis = now + WARM_PLACEHOLDER_GUARD_MILLIS
            _hydrationState.value = buildHydrationState(
                snapshotAgeMillis = now - snapshot.updatedAtEpochMillis,
                hasWarmSnapshot = true,
                isHydrating = true,
            )
        } else {
            _visibleListState.value = ResourceListUiState()
            _hydrationState.value = UiHydrationState(isHydrating = true)
        }
    }

    private fun shouldSkipPlaceholderCommit(merged: List<ResourceEntity>): Boolean {
        return placeholderGuardUntilMillis > System.currentTimeMillis() && merged.isEmpty()
    }

    private fun buildHydrationState(
        snapshotAgeMillis: Long? = null,
        hasWarmSnapshot: Boolean,
        isHydrating: Boolean,
    ): UiHydrationState {
        return UiHydrationState(
            snapshotAgeMillis = snapshotAgeMillis,
            isHydrating = isHydrating,
            isStale = snapshotAgeMillis?.let { it > STALE_WARM_SNAPSHOT_MILLIS } == true,
            hasWarmSnapshot = hasWarmSnapshot,
        )
    }

    private companion object {
        private const val WARM_PLACEHOLDER_GUARD_MILLIS = 1_200L
        private const val STALE_WARM_SNAPSHOT_MILLIS = 120_000L
    }
}

internal fun buildResourceListUiState(
    resources: List<ResourceEntity>,
): ResourceListUiState {
    val partitioned = partitionResourcesForDisplay(resources)
    return ResourceListUiState(
        resources = resources,
        imageResources = partitioned.mediaResources,
        otherResources = partitioned.otherResources,
    )
}
