package site.lcyk.keer.ui.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import site.lcyk.keer.data.local.entity.MemoEntity
import site.lcyk.keer.data.local.entity.ResourceEntity
import site.lcyk.keer.data.model.MemoVisibility
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
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

    @Test
    fun resolveResourceLifecycleKeyForTest_prioritizesRemoteIdThenLocalUriThenUri() {
        val withRemoteId = resource(
            memoIndex = 1,
            resourceIndex = 1,
            filename = "img.jpg",
            mimeType = "image/jpeg",
        ).copy(
            remoteId = "remote-attachment-1",
            localUri = "file:///tmp/local-1.jpg",
            uri = "https://example.com/blob-1",
        )
        val withLocalUri = resource(
            memoIndex = 1,
            resourceIndex = 2,
            filename = "img.jpg",
            mimeType = "image/jpeg",
        ).copy(
            remoteId = null,
            localUri = "file:///tmp/local-2.jpg",
            uri = "https://example.com/blob-2",
        )
        val withOnlyUri = resource(
            memoIndex = 1,
            resourceIndex = 3,
            filename = "img.jpg",
            mimeType = "image/jpeg",
        ).copy(
            remoteId = null,
            localUri = null,
            uri = "https://example.com/blob-3",
        )

        assertEquals(
            "remote:remote-attachment-1",
            MediaPreviewPrefetchCoordinator.resolveResourceLifecycleKeyForTest(withRemoteId),
        )
        assertEquals(
            "local:file:///tmp/local-2.jpg",
            MediaPreviewPrefetchCoordinator.resolveResourceLifecycleKeyForTest(withLocalUri),
        )
        assertEquals(
            "uri:https://example.com/blob-3",
            MediaPreviewPrefetchCoordinator.resolveResourceLifecycleKeyForTest(withOnlyUri),
        )
    }

    @Test
    fun lifecycleDecision_changesFromAllowedToSkippedOnceAndCooldown() = runTest {
        val resource = resource(
            memoIndex = 2,
            resourceIndex = 1,
            filename = "img.jpg",
            mimeType = "image/jpeg",
        ).copy(localUri = "content://local/preview")
        // SKIPPED_ONCE 要求 mainFetchedOnce 且仍有可用本地预览；给一个非 file 的 localUri，
        // 使 hasUsableLocalPreview 成立，与实现逻辑一致。
        MediaPreviewPrefetchCoordinator.clearPrefetchStateForTest()

        assertEquals(
            "ALLOWED",
            MediaPreviewPrefetchCoordinator.resolveLifecycleDecisionForTest(resource),
        )

        MediaPreviewPrefetchCoordinator.markMainFallbackFetchedForTest(resource)

        assertEquals(
            "SKIPPED_ONCE",
            MediaPreviewPrefetchCoordinator.resolveLifecycleDecisionForTest(resource),
        )

        val anotherResource = resource(
            memoIndex = 3,
            resourceIndex = 1,
            filename = "img.jpg",
            mimeType = "image/jpeg",
        ).copy(remoteId = null)
        val nowMillis = System.currentTimeMillis()
        MediaPreviewPrefetchCoordinator.markLifecycleCooldownForTest(
            resource = anotherResource,
            nowMillis = nowMillis,
            cooldownMillis = 60_000L,
        )
        assertEquals(
            "COOLDOWN",
            MediaPreviewPrefetchCoordinator.resolveLifecycleDecisionForTest(anotherResource),
        )
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
