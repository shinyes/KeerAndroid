package site.lcyk.keer.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import site.lcyk.keer.data.local.entity.MemoEntity
import site.lcyk.keer.data.local.entity.MemoWithResources
import site.lcyk.keer.data.local.entity.ResourceEntity
import site.lcyk.keer.data.local.entity.TagEntity
import site.lcyk.keer.data.model.MemoVisibility
import site.lcyk.keer.util.ProjectedList
import java.time.Instant

class MemoListProjectorTest {
    @Test
    fun `project reuses previous list and memo instances when content is unchanged`() {
        val projector = MemoListProjector()

        val first = projector.project(
            listOf(
                buildMemoWithResources("memo-1", tags = listOf("alpha")),
                buildMemoWithResources("memo-2", tags = listOf("beta")),
            )
        )
        val second = projector.project(
            listOf(
                buildMemoWithResources("memo-1", tags = listOf("alpha")),
                buildMemoWithResources("memo-2", tags = listOf("beta")),
            )
        )

        assertTrue(first is ProjectedList<MemoEntity>)
        assertSame(first, second)
        assertSame(first[0], second[0])
        assertSame(first[1], second[1])
    }

    @Test
    fun `project rebuilds only affected memo when tags change`() {
        val projector = MemoListProjector()

        val first = projector.project(
            listOf(
                buildMemoWithResources("memo-1", tags = listOf("alpha")),
                buildMemoWithResources("memo-2", tags = listOf("beta")),
            )
        )
        val second = projector.project(
            listOf(
                buildMemoWithResources("memo-1", tags = listOf("alpha")),
                buildMemoWithResources("memo-2", tags = listOf("beta", "gamma")),
            )
        )

        assertNotSame(first, second)
        assertSame(first[0], second[0])
        assertNotSame(first[1], second[1])
        assertEquals(listOf("beta", "gamma"), second[1].tags)
    }

    @Test
    fun `project rebuilds only affected memo when resources change`() {
        val projector = MemoListProjector()

        val first = projector.project(
            listOf(
                buildMemoWithResources("memo-1", resourceSuffix = "v1"),
                buildMemoWithResources("memo-2", resourceSuffix = "stable"),
            )
        )
        val second = projector.project(
            listOf(
                buildMemoWithResources("memo-1", resourceSuffix = "v2"),
                buildMemoWithResources("memo-2", resourceSuffix = "stable"),
            )
        )

        assertNotSame(first, second)
        assertNotSame(first[0], second[0])
        assertSame(first[1], second[1])
        assertEquals("resource-v2", second[0].resources.single().identifier)
    }

    private fun buildMemoWithResources(
        identifier: String,
        tags: List<String> = listOf("tag"),
        resourceSuffix: String = "default",
    ): MemoWithResources {
        val timestamp = Instant.parse("2026-04-02T00:00:00Z")
        val memo = MemoEntity(
            identifier = identifier,
            remoteId = "remote-$identifier",
            accountKey = "account",
            content = "content-$identifier",
            date = timestamp,
            visibility = MemoVisibility.PRIVATE,
            pinned = false,
            archived = false,
            needsSync = false,
            isDeleted = false,
            lastModified = timestamp,
            lastSyncedAt = timestamp,
        )
        val resource = ResourceEntity(
            identifier = "resource-$resourceSuffix",
            remoteId = "remote-resource-$resourceSuffix",
            accountKey = "account",
            date = timestamp,
            filename = "file-$resourceSuffix.jpg",
            uri = "file:///tmp/$resourceSuffix.jpg",
            localUri = "file:///tmp/$resourceSuffix.jpg",
            mimeType = "image/jpeg",
            memoId = identifier,
        )
        return MemoWithResources(
            memo = memo,
            resources = listOf(resource),
            tags = tags.map { tag ->
                TagEntity(
                    accountKey = "account",
                    name = tag,
                    createdAt = timestamp,
                    updatedAt = timestamp,
                )
            },
        )
    }
}
