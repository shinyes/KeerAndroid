package site.lcyk.keer.ui.page.memos

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.paging.compose.collectAsLazyPagingItems
import site.lcyk.keer.ui.component.ExploreMemoCard
import site.lcyk.keer.viewmodel.ExploreViewModel

@Composable
fun ExploreList(
    viewModel: ExploreViewModel = hiltViewModel(),
    contentPadding: PaddingValues
) {
    val memos = viewModel.exploreMemos.collectAsLazyPagingItems()
    val listState = rememberLazyListState()
    val hapticFeedback = LocalHapticFeedback.current
    var topHapticArmed by remember { mutableStateOf(false) }
    var bottomHapticArmed by remember { mutableStateOf(false) }

    val atTop = !listState.canScrollBackward
    val atBottom = memos.itemCount > 0 && !listState.canScrollForward

    LaunchedEffect(memos.itemCount) {
        if (memos.itemCount <= 0) {
            topHapticArmed = false
            bottomHapticArmed = false
            return@LaunchedEffect
        }
        topHapticArmed = !atTop
        bottomHapticArmed = !atBottom
    }

    LaunchedEffect(atTop, atBottom, memos.itemCount) {
        if (memos.itemCount <= 0) return@LaunchedEffect

        var shouldVibrate = false

        if (!atTop) {
            topHapticArmed = true
        } else if (topHapticArmed) {
            shouldVibrate = true
            topHapticArmed = false
        }

        if (!atBottom) {
            bottomHapticArmed = true
        } else if (bottomHapticArmed) {
            shouldVibrate = true
            bottomHapticArmed = false
        }

        if (shouldVibrate) {
            hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.consumeWindowInsets(contentPadding),
        contentPadding = contentPadding
    ) {
        items(memos.itemCount) { index ->
            val memo = memos[index]
            memo?.let {
                ExploreMemoCard(memo)
            }
        }
    }
}
