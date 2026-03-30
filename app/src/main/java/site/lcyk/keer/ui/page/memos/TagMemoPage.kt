package site.lcyk.keer.ui.page.memos

import androidx.compose.material3.DrawerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import site.lcyk.keer.ui.component.rememberMemoListState
import site.lcyk.keer.ui.page.common.PageScaffold
import site.lcyk.keer.ui.page.common.navigateToTagPage
import site.lcyk.keer.viewmodel.LocalMemos

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TagMemoPage(
    drawerState: DrawerState? = null,
    tag: String,
    navController: NavHostController,
    onMenuButtonOpenRequested: (() -> Unit)? = null
) {
    val normalizedCurrentTag = remember(tag) { normalizeTag(tag) }
    val listState = rememberMemoListState(aggressiveCache = false)
    val memosViewModel = LocalMemos.current
    val filteredMemoCardListStateFlow = remember(memosViewModel, tag) {
        memosViewModel.observeMemoCardListStateForTag(tag)
    }
    val initialFilteredMemoCardListState = remember(memosViewModel, tag) {
        memosViewModel.currentMemoCardListStateForTag(tag)
    }
    val filteredMemoCardListState by filteredMemoCardListStateFlow.collectAsStateWithLifecycle(
        initialValue = initialFilteredMemoCardListState
    )

    PageScaffold(
        title = tag,
        drawerState = drawerState,
        onMenuButtonOpenRequested = onMenuButtonOpenRequested
    ) { innerPadding ->
            MemosList(
                contentPadding = innerPadding,
                lazyListState = listState,
                memoCards = filteredMemoCardListState.cards,
                prefetchMemoEntities = filteredMemoCardListState.prefetchMemos,
                collaboratorIdsToPrefetch = filteredMemoCardListState.collaboratorIdsToPrefetch,
                onTagClick = { clickedTag ->
                    if (normalizeTag(clickedTag) == normalizedCurrentTag) {
                        return@MemosList
                    }
                    navController.navigateToTagPage(clickedTag)
                }
            )
    }
}

private fun normalizeTag(tag: String): String {
    return tag.removePrefix("#")
}
