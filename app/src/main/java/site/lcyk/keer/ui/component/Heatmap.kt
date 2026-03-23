package site.lcyk.keer.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    val listState = rememberLazyListState()
    var lastAutoScrollColumnCount by remember { mutableIntStateOf(-1) }

    LaunchedEffect(columns.size) {
        val columnCount = columns.size
        if (shouldAutoScrollToLatest(lastAutoScrollColumnCount, columnCount)) {
            listState.scrollToItem(latestHeatmapColumnIndex(columnCount))
            lastAutoScrollColumnCount = columnCount
        }
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
            if (day == null) {
                Spacer(modifier = Modifier.size(HEATMAP_CELL_SIZE))
            } else {
                Box(modifier = Modifier.size(HEATMAP_CELL_SIZE)) {
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
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (yearLabel != null) {
            Text(
                text = yearLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                maxLines = 1,
                textAlign = TextAlign.Center,
                overflow = TextOverflow.Clip,
            )
        } else {
            Spacer(modifier = Modifier.height(10.dp))
        }
        if (monthLabel != null) {
            Text(
                text = monthLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                maxLines = 1,
                textAlign = TextAlign.Center,
                overflow = TextOverflow.Clip,
            )
        } else {
            Spacer(modifier = Modifier.height(10.dp))
        }
    }
}

internal val HEATMAP_TOP_LABEL_HEIGHT = 20.dp
internal val HEATMAP_ROW_SPACING = 2.dp
internal val HEATMAP_COLUMN_SPACING = 2.dp
internal val HEATMAP_CELL_SIZE = 12.dp

internal fun latestHeatmapColumnIndex(columnCount: Int): Int {
    return (columnCount - 1).coerceAtLeast(0)
}

internal fun shouldAutoScrollToLatest(previousColumnCount: Int, currentColumnCount: Int): Boolean {
    return currentColumnCount > 0 && previousColumnCount != currentColumnCount
}
