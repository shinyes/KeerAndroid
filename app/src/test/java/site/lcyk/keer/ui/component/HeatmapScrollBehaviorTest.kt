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
}
