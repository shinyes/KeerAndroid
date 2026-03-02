package site.lcyk.keer.ui.page.memos

import androidx.compose.material3.DrawerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.navigation.NavHostController
import site.lcyk.keer.ui.page.common.PageScaffold
import site.lcyk.keer.ui.page.common.RouteName

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TagMemoPage(
    drawerState: DrawerState? = null,
    tag: String,
    navController: NavHostController,
    onMenuButtonOpenRequested: (() -> Unit)? = null
) {
    val normalizedCurrentTag = remember(tag) { normalizeTag(tag) }

    PageScaffold(
        title = tag,
        drawerState = drawerState,
        onMenuButtonOpenRequested = onMenuButtonOpenRequested
    ) { innerPadding ->
            MemosList(
                contentPadding = innerPadding,
                tag = tag,
                onTagClick = { clickedTag ->
                    if (normalizeTag(clickedTag) == normalizedCurrentTag) {
                        return@MemosList
                    }
                    navController.navigate("${RouteName.TAG}/${java.net.URLEncoder.encode(clickedTag, "UTF-8")}") {
                        launchSingleTop = true
                        restoreState = true
                    }
                }
            )
    }
}

private fun normalizeTag(tag: String): String {
    return tag.removePrefix("#")
}
