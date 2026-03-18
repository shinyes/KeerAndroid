package site.lcyk.keer.ui.page.memos

import androidx.compose.material3.DrawerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavHostController
import site.lcyk.keer.R
import site.lcyk.keer.ext.popBackStackIfLifecycleIsResumed
import site.lcyk.keer.ext.string
import site.lcyk.keer.ui.page.common.PageScaffold

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArchivedMemoPage(
    drawerState: DrawerState? = null,
    navController: NavHostController,
    onMenuButtonOpenRequested: (() -> Unit)? = null
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    PageScaffold(
        title = R.string.archived.string,
        drawerState = drawerState,
        onMenuButtonOpenRequested = onMenuButtonOpenRequested,
        onBack = { navController.popBackStackIfLifecycleIsResumed(lifecycleOwner) },
    ) { innerPadding ->
            ArchivedMemoList(
                contentPadding = innerPadding
            )
    }
}
