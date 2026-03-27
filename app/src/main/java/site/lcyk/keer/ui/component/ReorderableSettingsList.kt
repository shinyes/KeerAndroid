package site.lcyk.keer.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import sh.calvin.reorderable.ReorderableCollectionItemScope
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@Composable
fun <T : Any> ReorderableSettingsList(
    items: List<T>,
    key: (T) -> Any,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    onMove: (fromIndex: Int, toIndex: Int) -> Unit,
    itemContent: @Composable ReorderableCollectionItemScope.(item: T, isDragging: Boolean) -> Unit,
) {
    val lazyListState = rememberLazyListState()
    val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
        if (from.index != to.index) {
            onMove(from.index, to.index)
        }
    }

    LazyColumn(
        modifier = modifier,
        state = lazyListState,
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(
            items = items,
            key = key,
        ) { item ->
            ReorderableItem(
                state = reorderableState,
                key = key(item),
            ) { isDragging ->
                itemContent(item, isDragging)
            }
        }
    }
}
