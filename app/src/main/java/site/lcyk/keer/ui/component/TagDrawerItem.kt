package site.lcyk.keer.ui.component

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material3.DrawerState
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.navigation.NavHostController
import kotlinx.coroutines.launch
import site.lcyk.keer.ui.page.common.RouteName
import java.net.URLEncoder

@Composable
fun TagDrawerItem(
    tag: String,
    selected: Boolean,
    memosNavController: NavHostController,
    drawerState: DrawerState? = null,
    onDrawerItemCloseRequested: (() -> Unit)? = null
) {
    val scope = rememberCoroutineScope()
    val hapticFeedback = LocalHapticFeedback.current

    NavigationDrawerItem(
        label = { Text(tag) },
        icon = { Icon(Icons.Outlined.Tag, contentDescription = null) },
        selected = selected,
        onClick = {
            hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
            scope.launch {
                memosNavController.navigate("${RouteName.TAG}/${URLEncoder.encode(tag, "UTF-8")}") {
                    launchSingleTop = true
                    restoreState = true
                }
                onDrawerItemCloseRequested?.invoke()
                drawerState?.close()
            }
        },
        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
    )
}
