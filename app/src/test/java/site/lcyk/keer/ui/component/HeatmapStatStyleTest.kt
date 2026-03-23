package site.lcyk.keer.ui.component

import java.time.LocalDate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeatmapStatStyleTest {
    @Test
    fun shouldHighlightHeatmapMonthStart_trueOnlyForMonthStartAndNotToday() {
        val today = LocalDate.of(2026, 3, 23)

        assertTrue(
            shouldHighlightHeatmapMonthStart(
                date = LocalDate.of(2026, 4, 1),
                today = today,
            )
        )
        assertFalse(
            shouldHighlightHeatmapMonthStart(
                date = LocalDate.of(2026, 4, 2),
                today = today,
            )
        )
        assertFalse(
            shouldHighlightHeatmapMonthStart(
                date = LocalDate.of(2026, 3, 1),
                today = LocalDate.of(2026, 3, 1),
            )
        )
    }
}
