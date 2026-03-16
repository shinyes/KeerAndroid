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
) {
    private var latestLiveState: T = initialState
    private var frozen = false
    private var pendingCommitJob: Job? = null

    private val _visibleState = MutableStateFlow(initialState)
    val visibleState: StateFlow<T> = _visibleState.asStateFlow()

    fun updateLiveState(state: T) {
        latestLiveState = state
        if (!frozen && _visibleState.value != state) {
            _visibleState.value = state
        }
    }

    fun setFrozen(active: Boolean) {
        if (frozen == active) {
            return
        }
        frozen = active
        pendingCommitJob?.cancel()
        pendingCommitJob = null
        if (!active) {
            if (idleCommitDelayMillis <= 0L) {
                if (_visibleState.value != latestLiveState) {
                    _visibleState.value = latestLiveState
                }
                return
            }
            pendingCommitJob = scope.launch {
                delay(idleCommitDelayMillis)
                if (!frozen && _visibleState.value != latestLiveState) {
                    _visibleState.value = latestLiveState
                }
            }
        }
    }
}
