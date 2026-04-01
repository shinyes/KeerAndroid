package site.lcyk.keer.viewmodel

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

class FeedProjectionStore(
    scope: CoroutineScope,
    idleCommitDelayMillis: Long = 180L,
) {
    private val store = InteractionSnapshotStore(
        scope = scope,
        initialState = FeedUiState(),
        idleCommitDelayMillis = idleCommitDelayMillis,
    )

    val visibleState: StateFlow<FeedUiState> = store.visibleState

    fun updateLiveState(state: FeedUiState) {
        store.updateLiveState(state)
    }

    fun setFrozen(active: Boolean) {
        store.setFrozen(active)
    }
}

class DrawerProjectionStore(
    scope: CoroutineScope,
    idleCommitDelayMillis: Long = 180L,
) {
    private val store = InteractionSnapshotStore(
        scope = scope,
        initialState = DrawerUiState(),
        idleCommitDelayMillis = idleCommitDelayMillis,
    )

    val visibleState: StateFlow<DrawerUiState> = store.visibleState

    fun updateLiveState(state: DrawerUiState) {
        store.updateLiveState(state)
    }

    fun preloadVisibleState() {
        store.preloadLatestVisibleState()
    }

    fun setFrozen(active: Boolean) {
        store.setFrozen(active)
    }
}

