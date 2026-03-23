package site.lcyk.keer.ui.component

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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
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
    val isMonthStartHighlight = shouldHighlightHeatmapMonthStart(date = day.date)
    val color = when (day.count) {
        0 -> Color(0xffeaeaea)
        1 -> Color(0xff9be9a8)
        2 -> Color(0xff40c463)
        in 3..4 -> Color(0xff30a14e)
        else -> Color(0xff216e39)
    }
    val borderWidth = when {
        isToday -> HEATMAP_TODAY_BORDER_WIDTH
        isMonthStartHighlight -> HEATMAP_MONTH_START_BORDER_WIDTH
        else -> 0.dp
    }
    val borderColor = when {
        isToday -> MaterialTheme.colorScheme.primary
        isMonthStartHighlight -> resolveHeatmapMonthStartBorderColor(
            cellColor = color,
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
                drawRoundRect(
                    color = color,
                    cornerRadius = CornerRadius(cornerRadiusPx, cornerRadiusPx),
                )
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

internal fun resolveHeatmapMonthStartBorderColor(
    cellColor: Color,
): Color {
    return if (cellColor.luminance() >= HEATMAP_MONTH_START_LIGHT_CELL_LUMINANCE_THRESHOLD) {
        Color.Black.copy(alpha = HEATMAP_MONTH_START_BORDER_ALPHA)
    } else {
        Color.White.copy(alpha = HEATMAP_MONTH_START_BORDER_ALPHA)
    }
}

internal val HEATMAP_TODAY_BORDER_WIDTH = 1.5.dp
internal val HEATMAP_MONTH_START_BORDER_WIDTH = 1.dp
internal const val HEATMAP_MONTH_START_BORDER_ALPHA = 0.98f
internal const val HEATMAP_MONTH_START_LIGHT_CELL_LUMINANCE_THRESHOLD = 0.55f
internal val HEATMAP_CELL_CORNER_RADIUS = 2.dp
