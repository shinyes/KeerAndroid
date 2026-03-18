package site.lcyk.keer.ui.component

import android.net.Uri
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalContext
import com.skydoves.sandwich.ApiResponse
import kotlinx.coroutines.flow.distinctUntilChanged
import okhttp3.OkHttpClient
import site.lcyk.keer.data.local.entity.MemoEntity

@Composable
internal fun MediaPreviewPrefetchEffect(
    listState: LazyListState,
    memos: List<MemoEntity>,
    frozen: Boolean,
    syncing: Boolean,
    currentAccountKey: String?,
    okHttpClient: OkHttpClient,
    cacheResourceFile: suspend (String, Uri) -> ApiResponse<Unit>,
    cacheResourceThumbnail: suspend (String, Uri) -> ApiResponse<Unit>,
) {
    val context = LocalContext.current
    LaunchedEffect(memos, frozen, syncing, currentAccountKey, listState, okHttpClient) {
        if (frozen || syncing || memos.isEmpty()) {
            return@LaunchedEffect
        }
        snapshotFlow {
            if (listState.isScrollInProgress) {
                emptyList<Int>()
            } else {
                listState.layoutInfo.visibleItemsInfo.map { info -> info.index }
            }
        }
            .distinctUntilChanged()
            .collect { visibleIndices ->
                if (visibleIndices.isEmpty() || frozen || syncing) {
                    return@collect
                }
                MediaPreviewPrefetchCoordinator.prefetchMemoWindowResources(
                    context = context,
                    okHttpClient = okHttpClient,
                    currentAccountKey = currentAccountKey,
                    memos = memos,
                    visibleIndices = visibleIndices,
                    cacheResourceFile = cacheResourceFile,
                    cacheResourceThumbnail = cacheResourceThumbnail,
                )
            }
    }
}
