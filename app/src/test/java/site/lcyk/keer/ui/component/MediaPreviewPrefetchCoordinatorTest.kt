package site.lcyk.keer.ui.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import site.lcyk.keer.data.local.entity.MemoEntity
import site.lcyk.keer.data.local.entity.ResourceEntity
import site.lcyk.keer.data.model.MemoVisibility
import java.time.Instant

class MediaPreviewPrefetchCoordinatorTest {
    @Test
    fun collectWindowResourcesForTest_appliesAheadBehindRangeAndFiltersMediaOnly() {
        val memos = (0 until 8).map { index ->
            memoWithResources(
                index = index,
                resources = listOf(
                    resource(
                        memoIndex = index,
                        resourceIndex = 1,
                        filename = "img-$index.jpg",
                        mimeType = "image/jpeg",
                    ),
                    resource(
                        memoIndex = index,
                        resourceIndex = 2,
                        filename = "doc-$index.pdf",
                        mimeType = "application/pdf",
                    ),
                ),
            )
        }

        val collected = MediaPreviewPrefetchCoordinator.collectWindowResourcesForTest(
            memos = memos,
            visibleIndices = listOf(3, 4),
            windowAhead = 2,
            windowBehind = 1,
        )

        val expectedMemoIndices = (2..6).toSet()
        val actualMemoIndices = collected
            .mapNotNull { resource -> resource.memoId?.substringAfter("memo-")?.toIntOrNull() }
            .toSet()

        assertEquals(expectedMemoIndices, actualMemoIndices)
        assertTrue(collected.all { resource -> resource.mimeType?.startsWith("image/") == true })
    }

    @Test
    fun collectWindowResourcesForTest_clampsAtListEdges() {
        val memos = (0 until 12).map { index ->
            memoWithResources(
                index = index,
                resources = listOf(
                    resource(
                        memoIndex = index,
                        resourceIndex = 1,
                        filename = "img-$index.png",
                        mimeType = "image/png",
                    )
                ),
            )
        }

        val collected = MediaPreviewPrefetchCoordinator.collectWindowResourcesForTest(
            memos = memos,
            visibleIndices = listOf(0),
        )

        // Default extreme profile: behind=4, ahead=10, clamped to [0, 10].
        assertEquals(11, collected.size)
        assertEquals("res-0-1", collected.first().identifier)
        assertEquals("res-10-1", collected.last().identifier)
    }

    @Test
    fun collectWindowResourcesForTest_returnsEmptyWhenVisibleIndicesEmpty() {
        val memos = listOf(
            memoWithResources(
                index = 0,
                resources = listOf(
                    resource(
                        memoIndex = 0,
                        resourceIndex = 1,
                        filename = "img.jpg",
                        mimeType = "image/jpeg",
                    )
                ),
            )
        )

        val collected = MediaPreviewPrefetchCoordinator.collectWindowResourcesForTest(
            memos = memos,
            visibleIndices = emptyList(),
        )

        assertTrue(collected.isEmpty())
    }

    private fun memoWithResources(index: Int, resources: List<ResourceEntity>): MemoEntity {
        val memo = MemoEntity(
            identifier = "memo-$index",
            remoteId = "remote-$index",
            accountKey = "acc-1",
            content = "memo-$index",
            date = Instant.EPOCH,
            visibility = MemoVisibility.PRIVATE,
            pinned = false,
            archived = false,
            needsSync = false,
        )
        memo.resources = resources
        return memo
    }

    private fun resource(
        memoIndex: Int,
        resourceIndex: Int,
        filename: String,
        mimeType: String,
    ): ResourceEntity {
        return ResourceEntity(
            identifier = "res-$memoIndex-$resourceIndex",
            remoteId = "remote-res-$memoIndex-$resourceIndex",
            accountKey = "acc-1",
            date = Instant.EPOCH,
            filename = filename,
            uri = "https://example.com/$memoIndex/$resourceIndex",
            mimeType = mimeType,
            memoId = "memo-$memoIndex",
        )
    }
}
