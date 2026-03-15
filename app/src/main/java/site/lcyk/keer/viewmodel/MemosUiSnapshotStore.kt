package site.lcyk.keer.viewmodel

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import site.lcyk.keer.data.local.entity.MemoEntity
import site.lcyk.keer.data.model.DailyUsageStat
import site.lcyk.keer.data.model.MemoGroup

data class MemoFeedUiState(
    val memos: List<MemoEntity> = emptyList(),
    val tags: List<String> = emptyList(),
    val matrix: List<DailyUsageStat> = DailyUsageStat.initialMatrix,
    val homeMemos: List<HomeMemoItem> = emptyList(),
    val drawerGroups: List<MemoGroup> = emptyList(),
)

class MemosUiSnapshotStore {
    private var latestLiveState = MemoFeedUiState()
    private var frozen = false

    private val _visibleState = MutableStateFlow(MemoFeedUiState())
    val visibleState: StateFlow<MemoFeedUiState> = _visibleState.asStateFlow()

    fun updateLiveState(state: MemoFeedUiState) {
        latestLiveState = state
        if (!frozen) {
            _visibleState.value = state
        }
    }

    fun setFrozen(active: Boolean) {
        if (frozen == active) {
            return
        }
        frozen = active
        if (!frozen) {
            _visibleState.value = latestLiveState
        }
    }
}
