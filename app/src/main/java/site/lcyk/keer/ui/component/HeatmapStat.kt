package site.lcyk.keer.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import site.lcyk.keer.data.model.DailyUsageStat
import java.time.LocalDate

@Composable
fun HeatmapStat(
    day: DailyUsageStat,
    modifier: Modifier = Modifier,
) {
    val today = LocalDate.now()
    val isToday = day.date == today
    val isMonthStartHighlight = shouldHighlightHeatmapMonthStart(
        date = day.date,
        today = today,
    )
    val color = when (day.count) {
        0 -> Color(0xffeaeaea)
        1 -> Color(0xff9be9a8)
        2 -> Color(0xff40c463)
        in 3..4 -> Color(0xff30a14e)
        else -> Color(0xff216e39)
    }
    val isDarkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val borderWidth = when {
        isToday -> 1.dp
        isMonthStartHighlight -> HEATMAP_MONTH_START_BORDER_WIDTH
        else -> 0.dp
    }
    val borderColor = when {
        isToday -> MaterialTheme.colorScheme.onBackground
        isMonthStartHighlight -> resolveHeatmapMonthStartBorderColor(
            cellColor = color,
            isDarkTheme = isDarkTheme,
            onBackground = MaterialTheme.colorScheme.onBackground,
            background = MaterialTheme.colorScheme.background,
        )
        else -> Color.Transparent
    }
    var resolvedModifier = modifier
        .clip(RoundedCornerShape(2.dp))
        .background(color = color)
    if (borderWidth > 0.dp) {
        resolvedModifier = resolvedModifier.border(
            borderWidth,
            borderColor,
            shape = RoundedCornerShape(2.dp)
        )
    }

    Box(modifier = resolvedModifier)
}

internal fun shouldHighlightHeatmapMonthStart(
    date: LocalDate,
    today: LocalDate,
): Boolean {
    return date.dayOfMonth == 1 && date != today
}

internal fun resolveHeatmapMonthStartBorderColor(
    cellColor: Color,
    isDarkTheme: Boolean,
    onBackground: Color,
    background: Color,
): Color {
    val lightCell = cellColor.luminance() >= HEATMAP_MONTH_START_LIGHT_CELL_LUMINANCE_THRESHOLD
    return if (isDarkTheme) {
        if (lightCell) {
            background.copy(alpha = HEATMAP_MONTH_START_DARK_THEME_LIGHT_CELL_ALPHA)
        } else {
            onBackground.copy(alpha = HEATMAP_MONTH_START_DARK_THEME_DARK_CELL_ALPHA)
        }
    } else {
        if (lightCell) {
            onBackground.copy(alpha = HEATMAP_MONTH_START_LIGHT_THEME_LIGHT_CELL_ALPHA)
        } else {
            background.copy(alpha = HEATMAP_MONTH_START_LIGHT_THEME_DARK_CELL_ALPHA)
        }
    }
}

internal val HEATMAP_MONTH_START_BORDER_WIDTH = 0.9.dp
internal const val HEATMAP_MONTH_START_LIGHT_CELL_LUMINANCE_THRESHOLD = 0.55f
internal const val HEATMAP_MONTH_START_DARK_THEME_LIGHT_CELL_ALPHA = 0.86f
internal const val HEATMAP_MONTH_START_DARK_THEME_DARK_CELL_ALPHA = 0.92f
internal const val HEATMAP_MONTH_START_LIGHT_THEME_LIGHT_CELL_ALPHA = 0.72f
internal const val HEATMAP_MONTH_START_LIGHT_THEME_DARK_CELL_ALPHA = 0.88f
