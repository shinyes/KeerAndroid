package site.lcyk.keer.ui.component

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.layout.LazyLayoutCacheWindow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun rememberMemoListState(
    aggressiveCache: Boolean = true,
    firstVisibleItemIndex: Int = 0,
    firstVisibleItemScrollOffset: Int = 0,
): LazyListState {
    if (!aggressiveCache) {
        return rememberLazyListState(
            initialFirstVisibleItemIndex = firstVisibleItemIndex,
            initialFirstVisibleItemScrollOffset = firstVisibleItemScrollOffset,
        )
    }
    val cacheWindow = remember {
        LazyLayoutCacheWindow(
            ahead = EXTREME_LIST_CACHE_AHEAD_DP.dp,
            behind = EXTREME_LIST_CACHE_BEHIND_DP.dp,
        )
    }
    return rememberLazyListState(
        cacheWindow = cacheWindow,
        initialFirstVisibleItemIndex = firstVisibleItemIndex,
        initialFirstVisibleItemScrollOffset = firstVisibleItemScrollOffset,
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun rememberMemoExtremeListState(
    firstVisibleItemIndex: Int = 0,
    firstVisibleItemScrollOffset: Int = 0,
): LazyListState {
    return rememberMemoListState(
        aggressiveCache = true,
        firstVisibleItemIndex = firstVisibleItemIndex,
        firstVisibleItemScrollOffset = firstVisibleItemScrollOffset,
    )
}

private const val EXTREME_LIST_CACHE_AHEAD_DP = 800
private const val EXTREME_LIST_CACHE_BEHIND_DP = 400
