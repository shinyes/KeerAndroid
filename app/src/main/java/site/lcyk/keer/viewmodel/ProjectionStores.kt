package site.lcyk.keer.viewmodel

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

class FeedProjectionStore(
    scope: CoroutineScope,
    idleCommitDelayMillis: Long = 180L,
    liveCommitDebounceMillis: Long = 64L,
) {
    private val store = InteractionSnapshotStore(
        scope = scope,
        initialState = FeedUiState(),
        idleCommitDelayMillis = idleCommitDelayMillis,
        liveCommitDebounceMillis = liveCommitDebounceMillis,
    )

    val visibleState: StateFlow<FeedUiState> = store.visibleState

    fun updateLiveState(state: FeedUiState) {
        store.updateLiveState(state)
    }

    fun setFrozen(active: Boolean) {
        store.setFrozen(active)
    }

    fun restoreVisibleState(state: FeedUiState) {
        store.restoreSnapshot(state)
    }
}

class DrawerProjectionStore(
    scope: CoroutineScope,
    idleCommitDelayMillis: Long = 0L,
    liveCommitDebounceMillis: Long = 16L,
) {
    private val store = InteractionSnapshotStore(
        scope = scope,
        initialState = DrawerUiState(),
        idleCommitDelayMillis = idleCommitDelayMillis,
        liveCommitDebounceMillis = liveCommitDebounceMillis,
    )

    val visibleState: StateFlow<DrawerUiState> = store.visibleState

    fun updateLiveState(state: DrawerUiState) {
        store.updateLiveState(state)
    }

    fun setFrozen(active: Boolean) {
        store.setFrozen(active)
    }

    fun restoreVisibleState(state: DrawerUiState) {
        store.restoreSnapshot(state)
    }
}

