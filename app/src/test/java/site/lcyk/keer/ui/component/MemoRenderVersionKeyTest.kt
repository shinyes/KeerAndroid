package site.lcyk.keer.ui.component

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import site.lcyk.keer.data.local.entity.MemoEntity
import site.lcyk.keer.data.local.entity.ResourceEntity
import site.lcyk.keer.data.model.MemoVisibility

class MemoRenderVersionKeyTest {

    @Test
    fun buildMemoRenderVersionKey_sameMemoVersionProducesSameKey() {
        val memo = createMemo()
        val first = buildMemoRenderVersionKey(memo)
        val second = buildMemoRenderVersionKey(memo)

        assertEquals(first, second)
        assertEquals(first.stableKey, second.stableKey)
    }

    @Test
    fun buildMemoRenderVersionKey_changesWhenContentOrResourcesChange() {
        val baseMemo = createMemo()
        val changedContent = baseMemo.copy(content = "updated content")
        changedContent.resources = baseMemo.resources

        val changedResourceMemo = createMemo()
        changedResourceMemo.resources = listOf(
            createResource(
                identifier = "resource-2",
                localUri = "file:///tmp/2.jpg",
            )
        )

        val base = buildMemoRenderVersionKey(baseMemo)
        val contentUpdated = buildMemoRenderVersionKey(changedContent)
        val resourceUpdated = buildMemoRenderVersionKey(changedResourceMemo)

        assertNotEquals(base.stableKey, contentUpdated.stableKey)
        assertNotEquals(base.stableKey, resourceUpdated.stableKey)
    }

    private fun createMemo(): MemoEntity {
        val memo = MemoEntity(
            identifier = "memo-1",
            remoteId = "remote-1",
            accountKey = "account",
            content = "hello",
            date = Instant.parse("2026-01-01T00:00:00Z"),
            visibility = MemoVisibility.PRIVATE,
            pinned = false,
            archived = false,
            needsSync = false,
            lastModified = Instant.parse("2026-01-02T00:00:00Z"),
            lastSyncedAt = Instant.parse("2026-01-02T00:00:00Z"),
        )
        memo.resources = listOf(createResource())
        return memo
    }

    private fun createResource(
        identifier: String = "resource-1",
        localUri: String? = "file:///tmp/1.jpg",
    ): ResourceEntity {
        return ResourceEntity(
            identifier = identifier,
            remoteId = identifier,
            accountKey = "account",
            date = Instant.parse("2026-01-01T00:00:00Z"),
            filename = "$identifier.jpg",
            uri = "https://example.com/$identifier.jpg",
            localUri = localUri,
            mimeType = "image/jpeg",
            encryptionMetadata = null,
            thumbnailUri = "https://example.com/$identifier-thumb.jpg",
            thumbnailLocalUri = null,
            memoId = "memo-1",
        )
    }
}
