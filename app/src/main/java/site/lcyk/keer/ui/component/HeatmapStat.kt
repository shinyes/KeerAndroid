package site.lcyk.keer.ui.component

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import site.lcyk.keer.data.model.DailyUsageStat
import java.time.LocalDate

/**
 * Low-saturation orange color for the first day of each month.
 * Works well in both light and dark themes.
 */
private val HeatmapMonthStartOrange = Color(0xFFE6A57E)

/**
 * Calculates the border color for month start cells.
 * Uses a darker shade of the orange for better contrast.
 */
internal fun resolveHeatmapMonthStartBorderColor(
    isDarkTheme: Boolean,
    baseColor: Color
): Color {
    if (isDarkTheme) {
        // Kept for API stability in tests and future contrast tuning.
    }
    // Use a slightly darker version of the orange for border
    return baseColor.copy(alpha = HEATMAP_MONTH_START_BORDER_ALPHA)
}

@Composable
fun HeatmapStat(
    day: DailyUsageStat,
    modifier: Modifier = Modifier,
) {
    val today = LocalDate.now()
    val isToday = day.date == today
    val isMonthStart = shouldHighlightHeatmapMonthStart(date = day.date)
    val isDarkTheme = isSystemInDarkTheme()
    
    // Determine the base color for the cell
    val baseColor = when (day.count) {
        0 -> Color(0xffeaeaea)
        1 -> Color(0xff9be9a8)
        2 -> Color(0xff40c463)
        in 3..4 -> Color(0xff30a14e)
        else -> Color(0xff216e39)
    }
    
    // For month start days, override with low-saturation orange
    val cellColor = if (isMonthStart) {
        HeatmapMonthStartOrange
    } else {
        baseColor
    }
    
    // Border configuration
    val borderWidth = when {
        isToday -> HEATMAP_TODAY_BORDER_WIDTH
        isMonthStart -> HEATMAP_MONTH_START_BORDER_WIDTH
        else -> 0.dp
    }
    
    val borderColor = when {
        isToday -> MaterialTheme.colorScheme.primary
        isMonthStart -> resolveHeatmapMonthStartBorderColor(
            isDarkTheme = isDarkTheme,
            baseColor = HeatmapMonthStartOrange
        )
        else -> Color.Transparent
    }
    
    val density = LocalDensity.current
    val resolvedModifier = modifier
        .drawWithCache {
            val strokeWidthPx = with(density) { borderWidth.toPx() }
            val cornerRadiusPx = with(density) { HEATMAP_CELL_CORNER_RADIUS.toPx() }
            val inset = strokeWidthPx / 2f
            val strokeSize = Size(
                width = (size.width - strokeWidthPx).coerceAtLeast(0f),
                height = (size.height - strokeWidthPx).coerceAtLeast(0f),
            )
            val strokeCornerRadius = CornerRadius(
                x = (cornerRadiusPx - inset).coerceAtLeast(0f),
                y = (cornerRadiusPx - inset).coerceAtLeast(0f),
            )
            onDrawBehind {
                // Draw the main cell rectangle
                drawRoundRect(
                    color = cellColor,
                    cornerRadius = CornerRadius(cornerRadiusPx, cornerRadiusPx),
                )
                // Draw border if needed
                if (strokeWidthPx > 0f) {
                    drawRoundRect(
                        color = borderColor,
                        topLeft = Offset(inset, inset),
                        size = strokeSize,
                        cornerRadius = strokeCornerRadius,
                        style = Stroke(width = strokeWidthPx),
                    )
                }
            }
        }

    Box(modifier = resolvedModifier)
}

internal fun shouldHighlightHeatmapMonthStart(
    date: LocalDate,
): Boolean {
    return date.dayOfMonth == 1
}

internal val HEATMAP_TODAY_BORDER_WIDTH = 1.5.dp
internal val HEATMAP_MONTH_START_BORDER_WIDTH = 1.dp
internal val HEATMAP_CELL_CORNER_RADIUS = 2.dp
internal const val HEATMAP_MONTH_START_BORDER_ALPHA = 0.85f
