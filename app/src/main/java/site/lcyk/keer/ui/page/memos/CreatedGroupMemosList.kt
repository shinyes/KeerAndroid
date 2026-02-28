package site.lcyk.keer.ui.page.memos

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import site.lcyk.keer.R
import site.lcyk.keer.data.model.Memo
import site.lcyk.keer.ext.string
import site.lcyk.keer.ui.component.ExploreMemoCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreatedGroupMemosList(
    contentPadding: PaddingValues,
    memos: List<Memo>,
    loading: Boolean,
    errorMessage: String?,
    onRefresh: suspend () -> Unit
) {
    val scope = rememberCoroutineScope()
    val refreshState = rememberPullToRefreshState()

    PullToRefreshBox(
        isRefreshing = loading,
        onRefresh = {
            scope.launch { onRefresh() }
        },
        state = refreshState,
        modifier = Modifier.padding(contentPadding)
    ) {
        if (memos.isEmpty() && !loading && errorMessage.isNullOrBlank()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = R.string.no_memos.string,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            return@PullToRefreshBox
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize()
        ) {
            items(memos, key = { it.remoteId }) { memo ->
                ExploreMemoCard(memo = memo)
            }

            if (!errorMessage.isNullOrBlank()) {
                item {
                    Text(
                        text = errorMessage.orEmpty(),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
            }
        }
    }
}
