package site.lcyk.keer.ui.component

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoPreviewWarmupTest {

    @After
    fun tearDown() {
        clearMemoPreviewCacheForTest()
    }

    @Test
    fun resolveMemoPreviewSnapshot_reusesCachedValue() {
        clearMemoPreviewCacheForTest()
        val markdown = "# title\n\ncontent line"

        val first = resolveMemoPreviewSnapshot(markdown)
        val second = resolveMemoPreviewSnapshot(markdown)

        assertEquals(first, second)
        assertEquals(1, memoPreviewCacheSizeForTest())
    }

    @Test
    fun warmupMemoPreviews_onlyWarmsConfiguredLeadingItems() {
        clearMemoPreviewCacheForTest()
        val contents = (1..20).map { index -> "memo-$index content" }

        warmupMemoPreviews(
            memoContents = contents.asSequence(),
            warmupCount = 12,
        )

        assertEquals(12, memoPreviewCacheSizeForTest())
        val warmed = resolveMemoPreviewSnapshot("memo-1 content")
        val tail = resolveMemoPreviewSnapshot("memo-20 content")
        assertTrue(warmed.text.isNotEmpty())
        assertTrue(tail.text.isNotEmpty())
        assertEquals(13, memoPreviewCacheSizeForTest())
    }
}
