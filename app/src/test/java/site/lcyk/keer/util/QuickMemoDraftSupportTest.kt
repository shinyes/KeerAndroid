package site.lcyk.keer.util

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test
import site.lcyk.keer.data.local.entity.ResourceEntity
import site.lcyk.keer.data.model.QuickMemoDraftState

class QuickMemoDraftSupportTest {
    @Test
    fun buildQuickMemoDraftState_omitsForcedTagsAndNormalizesFields() {
        val draft = buildQuickMemoDraftState(
            text = "hello",
            selectedTags = listOf("column/a", "custom", " custom "),
            forcedTags = listOf("column/a"),
            selectedCollaborators = listOf(" Alice ", "alice", "Bob"),
            resources = listOf(
                resource(identifier = "local-1"),
                resource(identifier = "local-2", remoteId = "remote-2"),
            ),
        )

        assertEquals("hello", draft.text)
        assertEquals(listOf("custom"), draft.selectedTags)
        assertEquals(listOf("Alice", "alice", "Bob"), draft.selectedCollaborators)
        assertEquals(listOf("local-1", "remote-2"), draft.resourceIdentifiers)
    }

    @Test
    fun mergeQuickMemoDraftTags_addsForcedTagsWithoutDuplication() {
        val merged = mergeQuickMemoDraftTags(
            draft = QuickMemoDraftState(selectedTags = listOf("project/alpha", "custom")),
            forcedTags = listOf("project/alpha", "column/main"),
        )

        assertEquals(listOf("project/alpha", "column/main", "custom"), merged)
    }

    @Test
    fun resolveQuickMemoDraftResources_prunesMissingIdentifiersAndPreservesOrder() {
        val first = resource(identifier = "local-1")
        val second = resource(identifier = "local-2", remoteId = "remote-2")

        val restored = resolveQuickMemoDraftResources(
            resourceIdentifiers = listOf("missing", "remote-2", "local-1", "remote-2"),
            resources = listOf(second, first),
        )

        assertEquals(listOf(second, first), restored.resources)
        assertEquals(listOf("remote-2", "local-1"), restored.resourceIdentifiers)
    }

    private fun resource(
        identifier: String,
        remoteId: String? = null,
    ) = ResourceEntity(
        identifier = identifier,
        remoteId = remoteId,
        accountKey = "account",
        date = Instant.EPOCH,
        filename = "$identifier.bin",
        uri = "file:///tmp/$identifier.bin",
        localUri = "file:///tmp/$identifier.bin",
        mimeType = "application/octet-stream",
        memoId = null,
    )
}
