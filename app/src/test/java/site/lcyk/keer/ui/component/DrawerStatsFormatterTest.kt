package site.lcyk.keer.ui.component

import org.junit.Assert.assertEquals
import org.junit.Test

class DrawerStatsFormatterTest {
    @Test
    fun formatDrawerStatsText_returnsCompactChineseStatsText() {
        val text = formatDrawerStatsText(
            memoCount = 123,
            days = 678,
            memoLabel = "灵感",
            dayLabel = "天",
        )

        assertEquals("123灵感 678天", text)
    }

    @Test
    fun formatDrawerStatsText_supportsZeroValues() {
        val text = formatDrawerStatsText(
            memoCount = 0,
            days = 0,
            memoLabel = "Memo",
            dayLabel = "Day",
        )

        assertEquals("0Memo 0Day", text)
    }

    @Test
    fun formatDrawerStatsText_supportsLargeNumbers() {
        val text = formatDrawerStatsText(
            memoCount = 9_876_543,
            days = 76_543,
            memoLabel = "Memo",
            dayLabel = "Day",
        )

        assertEquals("9876543Memo 76543Day", text)
    }
}
