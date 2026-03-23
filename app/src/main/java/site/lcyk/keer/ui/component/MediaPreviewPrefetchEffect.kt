package site.lcyk.keer.ui.component

import android.net.Uri
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalContext
import com.skydoves.sandwich.ApiResponse
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.firstOrNull
import okhttp3.OkHttpClient
import site.lcyk.keer.data.local.entity.MemoEntity

@Composable
internal fun MediaPreviewPrefetchEffect(
    listState: LazyListState,
    memos: List<MemoEntity>,
    prefetchPaused: Boolean,
    currentAccountKey: String?,
    okHttpClient: OkHttpClient,
    cacheResourceFile: suspend (String, Uri) -> ApiResponse<Unit>,
    cacheResourceThumbnail: suspend (String, Uri) -> ApiResponse<Unit>,
) {
    val context = LocalContext.current
    LaunchedEffect(memos, prefetchPaused, currentAccountKey, listState, okHttpClient) {
        if (prefetchPaused || memos.isEmpty()) {
            return@LaunchedEffect
        }
        launch {
            val initialVisibleIndices = resolveInitialPrefetchVisibleIndices(
                immediateVisibleIndices = listState.layoutInfo.visibleItemsInfo.map { info -> info.index },
                visibleIndicesFlow = snapshotFlow {
                    listState.layoutInfo.visibleItemsInfo.map { info -> info.index }
                }.distinctUntilChanged(),
                fallbackVisibleIndices = resolveFallbackPrefetchVisibleIndices(
                    anchorIndex = listState.firstVisibleItemIndex,
                    memoCount = memos.size,
                ),
            )
            if (initialVisibleIndices.isEmpty()) {
                return@launch
            }
            val idleWindow = resolvePrefetchWindow(PrefetchDirection.IDLE)
            MediaPreviewPrefetchCoordinator.prefetchMemoWindowResources(
                context = context,
                okHttpClient = okHttpClient,
                currentAccountKey = currentAccountKey,
                memos = memos,
                visibleIndices = initialVisibleIndices,
                windowAhead = idleWindow.ahead,
                windowBehind = idleWindow.behind,
                cacheResourceFile = cacheResourceFile,
                cacheResourceThumbnail = cacheResourceThumbnail,
            )
        }
        collectPrefetchVisibleWindows(
            visibleIndicesFlow = snapshotFlow {
                if (listState.isScrollInProgress) {
                    emptyList<Int>()
                } else {
                    listState.layoutInfo.visibleItemsInfo.map { info -> info.index }
                }
            },
            prefetchPaused = prefetchPaused,
        ) { visibleIndices, window ->
            MediaPreviewPrefetchCoordinator.prefetchMemoWindowResources(
                context = context,
                okHttpClient = okHttpClient,
                currentAccountKey = currentAccountKey,
                memos = memos,
                visibleIndices = visibleIndices,
                windowAhead = window.ahead,
                windowBehind = window.behind,
                cacheResourceFile = cacheResourceFile,
                cacheResourceThumbnail = cacheResourceThumbnail,
            )
        }
    }
}

internal suspend fun resolveInitialPrefetchVisibleIndices(
    immediateVisibleIndices: List<Int>,
    visibleIndicesFlow: Flow<List<Int>>,
    fallbackVisibleIndices: List<Int> = emptyList(),
): List<Int> {
    if (immediateVisibleIndices.isNotEmpty()) {
        return immediateVisibleIndices
    }
    if (fallbackVisibleIndices.isNotEmpty()) {
        return fallbackVisibleIndices
    }
    return visibleIndicesFlow.firstOrNull { indices -> indices.isNotEmpty() } ?: emptyList()
}

internal fun resolveFallbackPrefetchVisibleIndices(
    anchorIndex: Int,
    memoCount: Int,
): List<Int> {
    if (memoCount <= 0) {
        return emptyList()
    }
    val normalizedAnchor = anchorIndex.coerceAtLeast(0).coerceAtMost(memoCount - 1)
    val endExclusive = (normalizedAnchor + PREFETCH_INITIAL_VISIBLE_FALLBACK_COUNT)
        .coerceAtMost(memoCount)
    return (normalizedAnchor until endExclusive).toList()
}

@OptIn(FlowPreview::class)
internal suspend fun collectPrefetchVisibleWindows(
    visibleIndicesFlow: Flow<List<Int>>,
    prefetchPaused: Boolean,
    onPrefetchVisibleWindow: suspend (List<Int>, PrefetchWindow) -> Unit,
) {
    var previousAnchorIndex: Int? = null
    visibleIndicesFlow
        .distinctUntilChanged()
        .debounce(PREFETCH_VISIBLE_DEBOUNCE_MS)
        .collectLatest { visibleIndices ->
            if (visibleIndices.isEmpty() || prefetchPaused) {
                return@collectLatest
            }
            val currentAnchorIndex = visibleIndices.minOrNull() ?: return@collectLatest
            val direction = resolvePrefetchDirection(
                previousAnchorIndex = previousAnchorIndex,
                currentAnchorIndex = currentAnchorIndex,
            )
            previousAnchorIndex = currentAnchorIndex
            onPrefetchVisibleWindow(
                visibleIndices,
                resolvePrefetchWindow(direction),
            )
        }
}

internal fun resolvePrefetchDirection(
    previousAnchorIndex: Int?,
    currentAnchorIndex: Int,
): PrefetchDirection {
    if (previousAnchorIndex == null) {
        return PrefetchDirection.IDLE
    }
    return when {
        currentAnchorIndex > previousAnchorIndex -> PrefetchDirection.FORWARD
        currentAnchorIndex < previousAnchorIndex -> PrefetchDirection.BACKWARD
        else -> PrefetchDirection.IDLE
    }
}

internal fun resolvePrefetchWindow(direction: PrefetchDirection): PrefetchWindow {
    return when (direction) {
        PrefetchDirection.FORWARD -> PrefetchWindow(
            ahead = PREFETCH_FORWARD_WINDOW_AHEAD,
            behind = PREFETCH_FORWARD_WINDOW_BEHIND,
        )
        PrefetchDirection.BACKWARD -> PrefetchWindow(
            ahead = PREFETCH_BACKWARD_WINDOW_AHEAD,
            behind = PREFETCH_BACKWARD_WINDOW_BEHIND,
        )
        PrefetchDirection.IDLE -> PrefetchWindow(
            ahead = PREFETCH_IDLE_WINDOW_AHEAD,
            behind = PREFETCH_IDLE_WINDOW_BEHIND,
        )
    }
}

internal data class PrefetchWindow(
    val ahead: Int,
    val behind: Int,
)

internal enum class PrefetchDirection {
    FORWARD,
    BACKWARD,
    IDLE,
}

internal const val PREFETCH_VISIBLE_DEBOUNCE_MS = 90L
internal const val PREFETCH_IDLE_WINDOW_AHEAD = 10
internal const val PREFETCH_IDLE_WINDOW_BEHIND = 4
internal const val PREFETCH_FORWARD_WINDOW_AHEAD = 14
internal const val PREFETCH_FORWARD_WINDOW_BEHIND = 3
internal const val PREFETCH_BACKWARD_WINDOW_AHEAD = 5
internal const val PREFETCH_BACKWARD_WINDOW_BEHIND = 12
internal const val PREFETCH_INITIAL_VISIBLE_FALLBACK_COUNT = 5
