package site.lcyk.keer.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import site.lcyk.keer.data.model.DailyUsageStat
import site.lcyk.keer.data.model.MemoColumnConfig
import site.lcyk.keer.data.model.MemoGroup
import site.lcyk.keer.data.model.MemoGroupType

class DrawerTagTreeProjectionTest {

    @Test
    fun buildDrawerTagTree_preservesTopLevelOrderAndNestedChildren() {
        val tree = buildDrawerTagTree(
            listOf(
                "work/projectA",
                "life",
                "work/projectB/subtask",
            )
        )

        assertEquals(listOf("work", "life"), tree.map { it.fullPath })
        assertEquals(listOf("work/projectA", "work/projectB"), tree.first().children.map { it.fullPath })
        assertEquals(listOf("work/projectB/subtask"), tree.first().children.last().children.map { it.fullPath })
        assertTrue(tree.first().isRealTag.not())
        assertTrue(tree.last().isRealTag)
    }

    @Test
    fun buildDrawerStatsUiModel_countsMemosTagsAndActiveDays() {
        val stats = buildDrawerStatsUiModel(
            matrix = listOf(
                DailyUsageStat.initialMatrix.first().copy(count = 2),
                DailyUsageStat.initialMatrix[1].copy(count = 0),
                DailyUsageStat.initialMatrix[2].copy(count = 5),
            ),
            tags = listOf("focus", "work/project"),
        )

        assertEquals(7, stats.memoCount)
        assertEquals(2, stats.tagCount)
        assertEquals(2L, stats.activeDayCount)
    }

    @Test
    fun buildDrawerGroupAndColumnUiModels_keepDisplayFields() {
        val groups = buildDrawerGroupUiModels(
            listOf(
                MemoGroup(
                    id = "g1",
                    name = "Friends",
                    creatorId = "u1",
                    creatorName = "Owner",
                    type = MemoGroupType.DIRECT,
                    hasUnreadMessages = true,
                )
            )
        )
        val columns = buildDrawerColumnUiModels(
            listOf(
                MemoColumnConfig(
                    id = "c1",
                    name = "Focus",
                )
            )
        )

        assertEquals(1, groups.size)
        assertEquals("Friends", groups.single().name)
        assertEquals(MemoGroupType.DIRECT, groups.single().type)
        assertTrue(groups.single().hasUnreadMessages)

        assertEquals(1, columns.size)
        assertEquals("c1", columns.single().id)
        assertEquals("Focus", columns.single().name)
    }

    @Test
    fun buildVisibleDrawerTagEntries_respectsExpansionState() {
        val tree = buildDrawerTagTree(
            listOf(
                "work/projectA",
                "work/projectB/subtask",
                "life",
            )
        )
        val entries = buildDrawerTagEntries(tree)

        val visibleEntries = buildVisibleDrawerTagEntries(
            entries = entries,
            expandedState = mapOf("work" to false),
        )

        assertEquals(listOf("work", "life"), visibleEntries.map { it.fullPath })
        assertTrue(visibleEntries.first().expandable)
        assertFalse(visibleEntries.first().expanded)
        assertEquals(0, visibleEntries.first().depth)
    }

    @Test
    fun buildDrawerTagEntries_flattensHierarchyWithAncestorPaths() {
        val tree = buildDrawerTagTree(
            listOf(
                "work/projectA",
                "work/projectB/subtask",
            )
        )

        val entries = buildDrawerTagEntries(tree)

        assertEquals(
            listOf("work", "work/projectA", "work/projectB", "work/projectB/subtask"),
            entries.map { it.fullPath }
        )
        assertEquals(emptyList<String>(), entries.first().ancestorPaths)
        assertEquals(listOf("work"), entries[1].ancestorPaths)
        assertEquals(listOf("work", "work/projectB"), entries.last().ancestorPaths)
    }

    @Test
    fun buildDrawerExpandedAncestorPaths_returnsOnlyParentPaths() {
        val ancestors = buildDrawerExpandedAncestorPaths("work/projectB/subtask")

        assertEquals(listOf("work", "work/projectB"), ancestors)
    }
}
