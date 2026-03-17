package site.lcyk.keer.viewmodel

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class InteractionSnapshotStore<T>(
    private val scope: CoroutineScope,
    initialState: T,
    private val idleCommitDelayMillis: Long = 180L,
    private val liveCommitDebounceMillis: Long = 64L,
) {
    private var latestLiveState: T = initialState
    private var frozen = false
    private var pendingUnfreezeCommitJob: Job? = null
    private var pendingLiveCommitJob: Job? = null

    private val _visibleState = MutableStateFlow(initialState)
    val visibleState: StateFlow<T> = _visibleState.asStateFlow()

    fun updateLiveState(state: T) {
        latestLiveState = state
        if (!frozen) {
            scheduleLiveCommit()
        }
    }

    fun setFrozen(active: Boolean) {
        if (frozen == active) {
            return
        }
        frozen = active
        pendingUnfreezeCommitJob?.cancel()
        pendingUnfreezeCommitJob = null
        pendingLiveCommitJob?.cancel()
        pendingLiveCommitJob = null
        if (!active) {
            if (idleCommitDelayMillis <= 0L) {
                commitLatestVisibleState()
                return
            }
            pendingUnfreezeCommitJob = scope.launch {
                delay(idleCommitDelayMillis)
                commitLatestVisibleState()
            }
        }
    }

    private fun scheduleLiveCommit() {
        if (liveCommitDebounceMillis <= 0L) {
            commitLatestVisibleState()
            return
        }
        pendingLiveCommitJob?.cancel()
        pendingLiveCommitJob = scope.launch {
            delay(liveCommitDebounceMillis)
            commitLatestVisibleState()
        }
    }

    private fun commitLatestVisibleState() {
        if (!frozen && _visibleState.value != latestLiveState) {
            _visibleState.value = latestLiveState
        }
    }
}
