package site.lcyk.keer.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import site.lcyk.keer.data.model.HeatmapTimeline
import site.lcyk.keer.data.model.HeatmapWeekColumn

@Composable
fun Heatmap(
    timeline: HeatmapTimeline,
) {
    val columns = timeline.columns
    val listState = remember(columns.size) {
        LazyListState(firstVisibleItemIndex = latestHeatmapColumnIndex(columns.size))
    }

    LazyRow(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(HEATMAP_COLUMN_SPACING),
        verticalAlignment = Alignment.Bottom,
        userScrollEnabled = true,
    ) {
        items(
            items = columns,
            key = { column -> column.weekStart },
        ) { column ->
            HeatmapWeekColumn(
                column = column,
                modifier = Modifier.fillMaxHeight(),
            )
        }
    }
}

@Composable
private fun HeatmapWeekColumn(
    column: HeatmapWeekColumn,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(HEATMAP_ROW_SPACING),
    ) {
        HeatmapTopLabel(
            monthLabel = column.monthLabel,
            yearLabel = column.yearLabel,
            modifier = Modifier
                .size(width = HEATMAP_CELL_SIZE, height = HEATMAP_TOP_LABEL_HEIGHT),
        )
        column.cells.forEach { day ->
            Box(modifier = Modifier.size(HEATMAP_CELL_SIZE)) {
                if (day == null) {
                    HeatmapPlaceholderStat(modifier = Modifier.fillMaxSize())
                } else {
                    HeatmapStat(
                        day = day,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

@Composable
private fun HeatmapTopLabel(
    monthLabel: String?,
    yearLabel: String?,
    modifier: Modifier = Modifier,
) {
    val combinedLabel = formatHeatmapTopLabel(monthLabel = monthLabel, yearLabel = yearLabel)

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.Center,
    ) {
        if (combinedLabel != null) {
            Text(
                text = combinedLabel,
                modifier = Modifier.requiredWidth(HEATMAP_TOP_LABEL_TEXT_WIDTH),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                maxLines = 1,
                textAlign = TextAlign.Start,
                overflow = TextOverflow.Clip,
            )
        }
    }
}

internal fun formatHeatmapTopLabel(
    monthLabel: String?,
    yearLabel: String?,
): String? {
    return when {
        monthLabel == null && yearLabel == null -> null
        monthLabel == null -> yearLabel
        yearLabel == null -> monthLabel
        else -> "$yearLabel $monthLabel"
    }
}

@Composable
private fun HeatmapPlaceholderStat(
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(2.dp))
            .background(MaterialTheme.colorScheme.heatmapPlaceholderColor())
    )
}

internal val HEATMAP_TOP_LABEL_HEIGHT = 20.dp
internal val HEATMAP_ROW_SPACING = 2.dp
internal val HEATMAP_COLUMN_SPACING = 2.dp
internal val HEATMAP_CELL_SIZE = 12.dp
internal val HEATMAP_TOP_LABEL_TEXT_WIDTH = 44.dp

internal fun latestHeatmapColumnIndex(columnCount: Int): Int {
    return (columnCount - 1).coerceAtLeast(0)
}

internal fun shouldAutoScrollToLatest(previousColumnCount: Int, currentColumnCount: Int): Boolean {
    return currentColumnCount > 0 && previousColumnCount != currentColumnCount
}

private fun ColorScheme.heatmapPlaceholderColor(): Color {
    return if (background.luminance() < 0.5f) {
        Color(0xff2a2a2a)
    } else {
        Color(0xffeaeaea)
    }
}
