package site.lcyk.keer.viewmodel

import site.lcyk.keer.data.local.entity.MemoEntity
import site.lcyk.keer.data.model.DailyUsageStat
import site.lcyk.keer.data.model.GroupIdAlias
import site.lcyk.keer.data.model.HeatmapTimeline
import site.lcyk.keer.data.model.Memo
import site.lcyk.keer.data.model.MemoColumnConfig
import site.lcyk.keer.data.model.MemoGroup
import site.lcyk.keer.data.model.buildHeatmapTimeline
import site.lcyk.keer.util.ResolvedMemoQuote

data class FeedUiState(
    val memos: List<MemoEntity> = emptyList(),
    val tags: List<String> = emptyList(),
    val matrix: List<DailyUsageStat> = DailyUsageStat.initialMatrix,
    val homeMemos: List<HomeMemoItem> = emptyList(),
    val resolvedQuoteByMemoId: Map<String, ResolvedMemoQuote> = emptyMap(),
)

data class DrawerUiState(
    val tags: List<String> = emptyList(),
    val matrix: List<DailyUsageStat> = DailyUsageStat.initialMatrix,
    val heatmapTimeline: HeatmapTimeline = buildHeatmapTimeline(DailyUsageStat.initialMatrix),
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
