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
}
