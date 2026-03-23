package site.lcyk.keer.ui.component

import androidx.compose.ui.graphics.Color
import java.time.LocalDate
import org.junit.Assert.assertEquals
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

    @Test
    fun resolveHeatmapMonthStartBorderColor_darkThemeUsesDarkBorderForLightCell() {
        val resolved = resolveHeatmapMonthStartBorderColor(
            cellColor = Color(0xffeaeaea),
            isDarkTheme = true,
            onBackground = Color.White,
            background = Color.Black,
        )

        assertEquals(
            Color.Black.copy(alpha = HEATMAP_MONTH_START_DARK_THEME_LIGHT_CELL_ALPHA),
            resolved
        )
    }

    @Test
    fun resolveHeatmapMonthStartBorderColor_darkThemeUsesLightBorderForDarkCell() {
        val resolved = resolveHeatmapMonthStartBorderColor(
            cellColor = Color(0xff216e39),
            isDarkTheme = true,
            onBackground = Color.White,
            background = Color.Black,
        )

        assertEquals(
            Color.White.copy(alpha = HEATMAP_MONTH_START_DARK_THEME_DARK_CELL_ALPHA),
            resolved
        )
    }
}
