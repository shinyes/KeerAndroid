package site.lcyk.keer.ui.page.memos

import androidx.compose.material.icons.Icons
import androidx.compose.material3.DrawerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import site.lcyk.keer.R
import site.lcyk.keer.ext.string
import site.lcyk.keer.ui.page.common.PageScaffold

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExplorePage(
    drawerState: DrawerState? = null,
    onMenuButtonOpenRequested: (() -> Unit)? = null
) {
    PageScaffold(
        title = R.string.explore.string,
        drawerState = drawerState,
        onMenuButtonOpenRequested = onMenuButtonOpenRequested
    ) { innerPadding ->
            ExploreList(
                contentPadding = innerPadding
            )
    }
}
