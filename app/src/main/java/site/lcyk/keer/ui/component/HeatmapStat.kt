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
import kotlin.math.abs

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
        isToday -> 1.dp
        isMonthStartHighlight -> HEATMAP_MONTH_START_BORDER_WIDTH
        else -> 0.dp
    }
    val borderColor = when {
        isToday -> MaterialTheme.colorScheme.onBackground
        isMonthStartHighlight -> resolveHeatmapMonthStartBorderColor(
            cellColor = color,
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
): Boolean {
    return date.dayOfMonth == 1
}

internal fun resolveHeatmapMonthStartBorderColor(
    cellColor: Color,
    onBackground: Color,
    background: Color,
): Color {
    val foregroundCandidate = onBackground.copy(alpha = HEATMAP_MONTH_START_BORDER_ALPHA)
    val backgroundCandidate = background.copy(alpha = HEATMAP_MONTH_START_BORDER_ALPHA)
    return if (
        resolveHeatmapBorderContrastDelta(cellColor, foregroundCandidate) >=
        resolveHeatmapBorderContrastDelta(cellColor, backgroundCandidate)
    ) {
        foregroundCandidate
    } else {
        backgroundCandidate
    }
}

internal fun resolveHeatmapBorderContrastDelta(
    cellColor: Color,
    borderColor: Color,
): Float {
    return abs(cellColor.luminance() - borderColor.luminance())
}

internal val HEATMAP_MONTH_START_BORDER_WIDTH = 1.dp
internal const val HEATMAP_MONTH_START_BORDER_ALPHA = 0.96f
