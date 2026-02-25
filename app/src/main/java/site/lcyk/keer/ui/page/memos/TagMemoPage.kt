package site.lcyk.keer.ui.page.memos

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.DrawerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.navigation.NavHostController
import kotlinx.coroutines.launch
import site.lcyk.keer.R
import site.lcyk.keer.ext.string
import site.lcyk.keer.ui.page.common.RouteName

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TagMemoPage(
    drawerState: DrawerState? = null,
    tag: String,
    navController: NavHostController,
    onMenuButtonOpenRequested: (() -> Unit)? = null
) {
    val scope = rememberCoroutineScope()
    val normalizedCurrentTag = remember(tag) { normalizeTag(tag) }
    val hapticFeedback = LocalHapticFeedback.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(tag) },
                navigationIcon = {
                    if (drawerState != null) {
                        IconButton(onClick = {
                            onMenuButtonOpenRequested?.invoke()
                            hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                            scope.launch { drawerState.open() }
                        }) {
                            Icon(Icons.Filled.Menu, contentDescription = R.string.menu.string)
                        }
                    }
                },
//                actions = {
//                    IconButton(onClick = {
//
//                    }) {
//                        Icon(Icons.Filled.Search, contentDescription = "Search")
//                    }
//                }
            )
        },

        content = { innerPadding ->
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
    )
}

private fun normalizeTag(tag: String): String {
    return tag.removePrefix("#")
}
