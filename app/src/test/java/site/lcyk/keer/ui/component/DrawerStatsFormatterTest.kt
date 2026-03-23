package site.lcyk.keer.ui.component

import org.junit.Assert.assertEquals
import org.junit.Test

class DrawerStatsFormatterTest {
    @Test
    fun formatDrawerStatsText_returnsCompactChineseStatsText() {
        val text = formatDrawerStatsText(
            memoCount = 123,
            tagCount = 45,
            days = 678,
            memoLabel = "灵感",
            tagLabel = "标签",
            dayLabel = "天",
        )

        assertEquals("123灵感 45标签 678天", text)
    }

    @Test
    fun formatDrawerStatsText_supportsZeroValues() {
        val text = formatDrawerStatsText(
            memoCount = 0,
            tagCount = 0,
            days = 0,
            memoLabel = "Memo",
            tagLabel = "Tag",
            dayLabel = "Day",
        )

        assertEquals("0Memo 0Tag 0Day", text)
    }

    @Test
    fun formatDrawerStatsText_supportsLargeNumbers() {
        val text = formatDrawerStatsText(
            memoCount = 9_876_543,
            tagCount = 321_987,
            days = 76_543,
            memoLabel = "Memo",
            tagLabel = "Tag",
            dayLabel = "Day",
        )

        assertEquals("9876543Memo 321987Tag 76543Day", text)
    }
}
