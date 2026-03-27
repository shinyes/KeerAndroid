package site.lcyk.keer.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UserGeneralSettingsTagDrawerTest {
    @Test
    fun tagDrawerVisibility_defaultsToVisible() {
        val settings = UserGeneralSettings()

        assertTrue(settings.isTagVisibleInDrawer("project/android"))
    }

    @Test
    fun withTagDrawerVisibility_updatesTargetTag() {
        val hidden = UserGeneralSettings()
            .withTagDrawerVisibility(tag = "project/android", visibleInDrawer = false)
        val restored = hidden.withTagDrawerVisibility(tag = "project/android", visibleInDrawer = true)

        assertFalse(hidden.isTagVisibleInDrawer("project/android"))
        assertTrue(restored.isTagVisibleInDrawer("project/android"))
    }

    @Test
    fun withRenamedTagDrawerEntries_renamesRootAndDescendantsOnly() {
        val settings = UserGeneralSettings(
            exploreDrawerEntries = listOf(
                ExploreDrawerEntryConfig(entryId = "drawer_tag:project", visibleInExplore = false),
                ExploreDrawerEntryConfig(entryId = "drawer_tag:project/android", visibleInExplore = false),
                ExploreDrawerEntryConfig(entryId = "group:abc", visibleInExplore = true),
            )
        )

        val renamed = settings.withRenamedTagDrawerEntries(
            oldTag = "project",
            newTag = "work",
        )

        assertFalse(renamed.isTagVisibleInDrawer("work"))
        assertFalse(renamed.isTagVisibleInDrawer("work/android"))
        assertTrue(renamed.isTagVisibleInDrawer("project"))
        assertEquals(
            true,
            renamed.exploreDrawerEntries.first { it.entryId == "group:abc" }.visibleInExplore
        )
    }

    @Test
    fun withoutTagDrawerEntries_removesRootAndDescendantsOnly() {
        val settings = UserGeneralSettings(
            exploreDrawerEntries = listOf(
                ExploreDrawerEntryConfig(entryId = "drawer_tag:project", visibleInExplore = false),
                ExploreDrawerEntryConfig(entryId = "drawer_tag:project/android", visibleInExplore = false),
                ExploreDrawerEntryConfig(entryId = "drawer_tag:other", visibleInExplore = false),
                ExploreDrawerEntryConfig(entryId = "group:abc", visibleInExplore = false),
            )
        )

        val removed = settings.withoutTagDrawerEntries("project")

        assertTrue(removed.isTagVisibleInDrawer("project"))
        assertTrue(removed.isTagVisibleInDrawer("project/android"))
        assertFalse(removed.isTagVisibleInDrawer("other"))
        assertEquals(2, removed.exploreDrawerEntries.size)
        assertTrue(removed.exploreDrawerEntries.any { it.entryId == "group:abc" })
    }

    @Test
    fun orderTagsForDrawer_respectsConfiguredOrderAndAppendsNewTags() {
        val settings = UserGeneralSettings(
            exploreDrawerEntries = listOf(
                ExploreDrawerEntryConfig(entryId = "group:abc", visibleInExplore = true),
                ExploreDrawerEntryConfig(entryId = "drawer_tag:beta", visibleInExplore = false),
                ExploreDrawerEntryConfig(entryId = "drawer_tag:alpha", visibleInExplore = true),
            )
        )

        val ordered = settings.orderTagsForDrawer(
            listOf("alpha/one", "gamma/root", "beta/two", "beta")
        )

        assertEquals(listOf("beta/two", "beta", "alpha/one", "gamma/root"), ordered)
    }

    @Test
    fun orderTopLevelTagsForDrawer_onlyConsidersTopLevelTags() {
        val settings = UserGeneralSettings(
            exploreDrawerEntries = listOf(
                ExploreDrawerEntryConfig(entryId = "drawer_tag:beta/child", visibleInExplore = true),
                ExploreDrawerEntryConfig(entryId = "drawer_tag:alpha", visibleInExplore = true),
            )
        )

        val orderedTopLevels = settings.orderTopLevelTagsForDrawer(
            listOf("alpha/one", "gamma/root", "beta/two", "beta/three")
        )

        assertEquals(listOf("beta", "alpha", "gamma"), orderedTopLevels)
    }

    @Test
    fun withReorderedTagDrawerEntries_reordersTopLevelBlocksAndPreservesVisibility() {
        val settings = UserGeneralSettings(
            exploreDrawerEntries = listOf(
                ExploreDrawerEntryConfig(entryId = "group:abc", visibleInExplore = true),
                ExploreDrawerEntryConfig(entryId = "drawer_tag:alpha/one", visibleInExplore = false),
                ExploreDrawerEntryConfig(entryId = "drawer_tag:beta/two", visibleInExplore = true),
            )
        )

        val reordered = settings.withReorderedTagDrawerEntries(
            listOf("beta", "alpha", "gamma")
        )

        assertEquals(
            listOf(
                "group:abc",
                "drawer_tag:beta",
                "drawer_tag:beta/two",
                "drawer_tag:alpha",
                "drawer_tag:alpha/one",
                "drawer_tag:gamma",
            ),
            reordered.exploreDrawerEntries.map { it.entryId }
        )
        assertTrue(reordered.isTagVisibleInDrawer("beta/two"))
        assertFalse(reordered.isTagVisibleInDrawer("alpha/one"))
        assertTrue(reordered.isTagVisibleInDrawer("gamma"))
    }

    @Test
    fun renamedAndRemovedTagEntries_keepOrderingSemanticsIntact() {
        val settings = UserGeneralSettings(
            exploreDrawerEntries = listOf(
                ExploreDrawerEntryConfig(entryId = "drawer_tag:alpha", visibleInExplore = false),
                ExploreDrawerEntryConfig(entryId = "drawer_tag:alpha/child", visibleInExplore = false),
                ExploreDrawerEntryConfig(entryId = "drawer_tag:beta", visibleInExplore = true),
                ExploreDrawerEntryConfig(entryId = "memo", visibleInExplore = true),
            )
        )

        val renamed = settings.withRenamedTagDrawerEntries("alpha", "work")
        val removed = renamed.withoutTagDrawerEntries("beta")

        assertEquals(
            listOf(
                "drawer_tag:work",
                "drawer_tag:work/child",
                "memo",
            ),
            removed.exploreDrawerEntries.map { it.entryId }
        )
        assertFalse(removed.isTagVisibleInDrawer("work"))
        assertFalse(removed.isTagVisibleInDrawer("work/child"))
        assertTrue(removed.isTagVisibleInDrawer("beta"))
    }
}
