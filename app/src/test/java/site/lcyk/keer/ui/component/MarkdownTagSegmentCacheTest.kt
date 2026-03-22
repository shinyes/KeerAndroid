package site.lcyk.keer.ui.component

import org.junit.Assert.assertEquals
import org.junit.Test

class MarkdownTagSegmentCacheTest {

    @Test
    fun resolveMarkdownTagSegmentsForTest_cachesSegmentsBySource() {
        clearMarkdownTagSegmentCacheForTest()
        assertEquals(0, markdownTagSegmentCacheSizeForTest())

        val source = "memo #alpha and #beta"
        val first = resolveMarkdownTagSegmentsForTest(source)
        val sizeAfterFirst = markdownTagSegmentCacheSizeForTest()
        val second = resolveMarkdownTagSegmentsForTest(source)

        assertEquals(listOf("#alpha", "#beta"), first)
        assertEquals(first, second)
        assertEquals(sizeAfterFirst, markdownTagSegmentCacheSizeForTest())
    }

    @Test
    fun resolveMarkdownTagSegmentsForTest_returnsEmptyWhenNoTag() {
        clearMarkdownTagSegmentCacheForTest()

        val segments = resolveMarkdownTagSegmentsForTest("memo without tag")

        assertEquals(emptyList<String>(), segments)
    }
}
