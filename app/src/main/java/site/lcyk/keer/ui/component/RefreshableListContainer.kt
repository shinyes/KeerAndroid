package site.lcyk.keer.ui.component

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RefreshableListContainer(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    state: PullToRefreshState,
    modifier: Modifier = Modifier,
    isEmpty: Boolean = false,
    emptyContent: (@Composable () -> Unit)? = null,
    indicator: (@Composable BoxScope.() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    val contentBlock: @Composable BoxScope.() -> Unit = {
        if (isEmpty && !isRefreshing && emptyContent != null) {
            emptyContent()
        } else {
            content()
        }
    }
    if (indicator == null) {
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = onRefresh,
            state = state,
            modifier = modifier,
            content = contentBlock
        )
    } else {
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = onRefresh,
            state = state,
            modifier = modifier,
            indicator = indicator,
            content = contentBlock
        )
    }
}
