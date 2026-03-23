package site.lcyk.keer.ui.component

import androidx.compose.ui.graphics.Color
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeatmapStatStyleTest {
    @Test
    fun shouldHighlightHeatmapMonthStart_trueForAnyMonthStartIncludingToday() {
        assertTrue(
            shouldHighlightHeatmapMonthStart(
                date = LocalDate.of(2026, 4, 1),
            )
        )
        assertFalse(
            shouldHighlightHeatmapMonthStart(
                date = LocalDate.of(2026, 4, 2),
            )
        )
        assertTrue(
            shouldHighlightHeatmapMonthStart(
                date = LocalDate.of(2026, 3, 1),
            )
        )
    }

    @Test
    fun resolveHeatmapMonthStartBorderColor_choosesHigherContrastForLightCell() {
        val resolved = resolveHeatmapMonthStartBorderColor(
            cellColor = Color(0xffeaeaea),
        )

        assertEquals(
            Color.Black.copy(alpha = HEATMAP_MONTH_START_BORDER_ALPHA),
            resolved
        )
    }

    @Test
    fun resolveHeatmapMonthStartBorderColor_choosesHigherContrastForDarkCell() {
        val resolved = resolveHeatmapMonthStartBorderColor(
            cellColor = Color(0xff216e39),
        )

        assertEquals(
            Color.White.copy(alpha = HEATMAP_MONTH_START_BORDER_ALPHA),
            resolved
        )
    }
}
