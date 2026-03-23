package site.lcyk.keer.ui.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeatmapScrollBehaviorTest {
    @Test
    fun latestHeatmapColumnIndex_returnsLastValidIndex() {
        assertEquals(0, latestHeatmapColumnIndex(0))
        assertEquals(0, latestHeatmapColumnIndex(1))
        assertEquals(9, latestHeatmapColumnIndex(10))
    }

    @Test
    fun shouldAutoScrollToLatest_onlyWhenColumnCountChangedAndNonEmpty() {
        assertTrue(shouldAutoScrollToLatest(previousColumnCount = -1, currentColumnCount = 10))
        assertTrue(shouldAutoScrollToLatest(previousColumnCount = 10, currentColumnCount = 11))
        assertFalse(shouldAutoScrollToLatest(previousColumnCount = 10, currentColumnCount = 10))
        assertFalse(shouldAutoScrollToLatest(previousColumnCount = 3, currentColumnCount = 0))
    }

    @Test
    fun formatHeatmapTopLabel_keepsYearWhenJanuaryMonthHidden() {
        assertEquals("2025", formatHeatmapTopLabel(monthLabel = null, yearLabel = "2025"))
        assertEquals("Feb", formatHeatmapTopLabel(monthLabel = "Feb", yearLabel = null))
        assertEquals("2025 Feb", formatHeatmapTopLabel(monthLabel = "Feb", yearLabel = "2025"))
        assertEquals(null, formatHeatmapTopLabel(monthLabel = null, yearLabel = null))
    }

    @Test
    fun resolveHeatmapTopLabelTextWidth_usesStableWidthsByLabelType() {
        assertEquals(
            HEATMAP_TOP_LABEL_MONTH_ONLY_WIDTH,
            resolveHeatmapTopLabelTextWidth(monthLabel = "2月", yearLabel = null),
        )
        assertEquals(
            HEATMAP_TOP_LABEL_YEAR_ONLY_WIDTH,
            resolveHeatmapTopLabelTextWidth(monthLabel = null, yearLabel = "2026"),
        )
        assertEquals(
            HEATMAP_TOP_LABEL_COMBINED_WIDTH,
            resolveHeatmapTopLabelTextWidth(monthLabel = "2月", yearLabel = "2026"),
        )
    }
}
