package site.lcyk.keer.viewmodel

import site.lcyk.keer.data.local.entity.MemoEntity
import site.lcyk.keer.data.model.GroupIdAlias
import site.lcyk.keer.data.model.HeatmapTimeline
import site.lcyk.keer.data.model.Memo
import site.lcyk.keer.data.model.MemoColumnConfig
import site.lcyk.keer.data.model.MemoGroup
import site.lcyk.keer.util.ResolvedMemoQuote
import java.time.LocalDate

data class FeedUiState(
    val memos: List<MemoEntity> = emptyList(),
    val tags: List<String> = emptyList(),
    val matrix: Map<LocalDate, Int> = emptyMap(),
    val homeMemos: List<HomeMemoItem> = emptyList(),
    val resolvedQuoteByMemoId: Map<String, ResolvedMemoQuote> = emptyMap(),
)

data class DrawerUiState(
    val tags: List<String> = emptyList(),
    val matrix: Map<LocalDate, Int> = emptyMap(),
    val heatmapTimeline: HeatmapTimeline = HeatmapTimeline.EMPTY,
    val drawerGroups: List<MemoGroup> = emptyList(),
    val visibleColumns: List<MemoColumnConfig> = emptyList(),
    val groupIdAliases: List<GroupIdAlias> = emptyList(),
)

data class ExploreUiState(
    val items: List<ExploreMemoItem> = emptyList(),
    val resolvedQuoteByMemoId: Map<String, ResolvedMemoQuote> = emptyMap(),
)

data class GroupChatUiState(
    val memos: List<Memo> = emptyList(),
    val resolvedQuoteByMemoId: Map<String, ResolvedMemoQuote> = emptyMap(),
)
