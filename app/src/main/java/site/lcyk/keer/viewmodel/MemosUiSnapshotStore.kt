package site.lcyk.keer.viewmodel

import site.lcyk.keer.data.local.entity.MemoEntity
import site.lcyk.keer.data.local.entity.ResourceEntity
import site.lcyk.keer.data.model.DailyUsageStat
import site.lcyk.keer.data.model.GroupIdAlias
import site.lcyk.keer.data.model.HeatmapTimeline
import site.lcyk.keer.data.model.Memo
import site.lcyk.keer.data.model.MemoColumnConfig
import site.lcyk.keer.data.model.MemoGroup
import site.lcyk.keer.data.model.MemoGroupType
import site.lcyk.keer.data.model.buildHeatmapTimeline
import site.lcyk.keer.util.normalizeTagName
import site.lcyk.keer.util.ResolvedMemoQuote

data class FeedUiState(
    val memos: List<MemoEntity> = emptyList(),
    val tags: List<String> = emptyList(),
    val matrix: List<DailyUsageStat> = DailyUsageStat.initialMatrix,
    val homeMemos: List<HomeMemoItem> = emptyList(),
    val homeMemoCards: List<HomeMemoCardUiModel> = emptyList(),
    val resolvedQuoteByMemoId: Map<String, ResolvedMemoQuote> = emptyMap(),
    val memoCards: List<MemoCardUiModel> = emptyList(),
)

data class DrawerTagTreeNode(
    val segment: String,
    val fullPath: String,
    val isRealTag: Boolean = false,
    val children: List<DrawerTagTreeNode> = emptyList(),
)

data class DrawerVisibleTagEntryUiModel(
    val fullPath: String,
    val displayName: String,
    val depth: Int,
    val selectable: Boolean,
    val expandable: Boolean,
    val expanded: Boolean,
)

data class DrawerTagEntryUiModel(
    val fullPath: String,
    val displayName: String,
    val depth: Int,
    val selectable: Boolean,
    val expandable: Boolean,
    val ancestorPaths: List<String> = emptyList(),
)

data class DrawerStatsUiModel(
    val memoCount: Int = 0,
    val tagCount: Int = 0,
    val activeDayCount: Long = 0L,
)

data class DrawerGroupUiModel(
    val id: String,
    val name: String,
    val type: MemoGroupType,
    val hasUnreadMessages: Boolean,
)

data class DrawerColumnUiModel(
    val id: String,
    val name: String,
)

data class DrawerUiState(
    val tags: List<String> = emptyList(),
    val visibleOrderedTags: List<String> = emptyList(),
    val tagTree: List<DrawerTagTreeNode> = emptyList(),
    val tagEntries: List<DrawerTagEntryUiModel> = emptyList(),
    val matrix: List<DailyUsageStat> = DailyUsageStat.initialMatrix,
    val activeDayCount: Long = 0L,
    val stats: DrawerStatsUiModel = DrawerStatsUiModel(),
    val heatmapTimeline: HeatmapTimeline = buildHeatmapTimeline(DailyUsageStat.initialMatrix),
    val drawerGroups: List<MemoGroup> = emptyList(),
    val groupItems: List<DrawerGroupUiModel> = emptyList(),
    val visibleColumns: List<MemoColumnConfig> = emptyList(),
    val columnItems: List<DrawerColumnUiModel> = emptyList(),
    val groupIdAliases: List<GroupIdAlias> = emptyList(),
)

data class ExploreUiState(
    val items: List<ExploreMemoItem> = emptyList(),
    val resolvedQuoteByMemoId: Map<String, ResolvedMemoQuote> = emptyMap(),
    val cardItems: List<ExploreCardUiModel> = emptyList(),
)

data class GroupChatUiState(
    val memos: List<Memo> = emptyList(),
    val resolvedQuoteByMemoId: Map<String, ResolvedMemoQuote> = emptyMap(),
    val cardItems: List<GroupChatCardUiModel> = emptyList(),
)

data class ResourceListUiState(
    val resources: List<ResourceEntity> = emptyList(),
    val imageResources: List<ResourceEntity> = emptyList(),
    val otherResources: List<ResourceEntity> = emptyList(),
)

internal fun buildDrawerTagTree(tags: List<String>): List<DrawerTagTreeNode> {
    data class MutableTagTreeNode(
        val segment: String,
        val fullPath: String,
        var isRealTag: Boolean = false,
        val children: LinkedHashMap<String, MutableTagTreeNode> = linkedMapOf(),
    )

    fun MutableTagTreeNode.freeze(): DrawerTagTreeNode = DrawerTagTreeNode(
        segment = segment,
        fullPath = fullPath,
        isRealTag = isRealTag,
        children = children.values.map(MutableTagTreeNode::freeze),
    )

    val roots = linkedMapOf<String, MutableTagTreeNode>()

    tags.forEach { rawTag ->
        val normalizedTag = normalizeTagName(rawTag)
        if (normalizedTag.isEmpty()) {
            return@forEach
        }
        val segments = normalizedTag
            .split("/")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (segments.isEmpty()) {
            return@forEach
        }

        var currentMap = roots
        var currentPath = ""
        var lastNode: MutableTagTreeNode? = null

        segments.forEach { segment ->
            currentPath = if (currentPath.isEmpty()) segment else "$currentPath/$segment"
            val node = currentMap.getOrPut(segment) {
                MutableTagTreeNode(
                    segment = segment,
                    fullPath = currentPath,
                )
            }
            currentMap = node.children
            lastNode = node
        }
        lastNode?.isRealTag = true
    }

    return roots.values.map(MutableTagTreeNode::freeze)
}

internal fun buildVisibleDrawerTagEntries(
    entries: List<DrawerTagEntryUiModel>,
    expandedState: Map<String, Boolean>,
): List<DrawerVisibleTagEntryUiModel> {
    return entries.mapNotNull { entry ->
        val allAncestorsExpanded = entry.ancestorPaths.all { ancestor ->
            expandedState[ancestor] ?: true
        }
        if (!allAncestorsExpanded) {
            return@mapNotNull null
        }
        DrawerVisibleTagEntryUiModel(
            fullPath = entry.fullPath,
            displayName = entry.displayName,
            depth = entry.depth,
            selectable = entry.selectable,
            expandable = entry.expandable,
            expanded = if (entry.expandable) {
                expandedState[entry.fullPath] ?: true
            } else {
                false
            },
        )
    }
}

internal fun buildDrawerExpandedAncestorPaths(
    tag: String,
): List<String> {
    val normalizedTag = normalizeTagName(tag)
    if (normalizedTag.isEmpty()) {
        return emptyList()
    }

    val segments = normalizedTag.split("/").filter { it.isNotEmpty() }
    val paths = mutableListOf<String>()
    var currentPath = ""
    segments.dropLast(1).forEach { segment ->
        currentPath = if (currentPath.isEmpty()) segment else "$currentPath/$segment"
        paths += currentPath
    }
    return paths
}

internal fun buildDrawerTagEntries(
    roots: List<DrawerTagTreeNode>,
): List<DrawerTagEntryUiModel> {
    val result = mutableListOf<DrawerTagEntryUiModel>()

    fun visit(
        node: DrawerTagTreeNode,
        depth: Int,
        ancestorPaths: List<String>,
    ) {
        result += DrawerTagEntryUiModel(
            fullPath = node.fullPath,
            displayName = node.segment,
            depth = depth,
            selectable = node.isRealTag,
            expandable = node.children.isNotEmpty(),
            ancestorPaths = ancestorPaths,
        )
        val nextAncestors = ancestorPaths + node.fullPath
        node.children.forEach { child ->
            visit(
                node = child,
                depth = depth + 1,
                ancestorPaths = nextAncestors,
            )
        }
    }

    roots.forEach { root ->
        visit(
            node = root,
            depth = 0,
            ancestorPaths = emptyList(),
        )
    }
    return result
}

internal fun buildDrawerStatsUiModel(
    matrix: List<DailyUsageStat>,
    tags: List<String>,
): DrawerStatsUiModel {
    return DrawerStatsUiModel(
        memoCount = matrix.sumOf { it.count },
        tagCount = tags.size,
        activeDayCount = matrix.count { stat -> stat.count > 0 }.toLong(),
    )
}

internal fun buildDrawerGroupUiModels(
    groups: List<MemoGroup>,
): List<DrawerGroupUiModel> {
    return groups.map { group ->
        DrawerGroupUiModel(
            id = group.id,
            name = group.name,
            type = group.type,
            hasUnreadMessages = group.hasUnreadMessages,
        )
    }
}

internal fun buildDrawerColumnUiModels(
    columns: List<MemoColumnConfig>,
): List<DrawerColumnUiModel> {
    return columns.map { column ->
        DrawerColumnUiModel(
            id = column.id,
            name = column.name,
        )
    }
}
