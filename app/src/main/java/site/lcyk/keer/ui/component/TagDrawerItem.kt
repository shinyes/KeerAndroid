package site.lcyk.keer.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material3.DrawerState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import kotlinx.coroutines.launch
import site.lcyk.keer.ui.page.common.RouteName
import java.net.URLEncoder

@Composable
fun TagDrawerItem(
    tag: String,
    displayName: String = tag,
    selected: Boolean,
    enabled: Boolean = true,
    depth: Int = 0,
    expandable: Boolean = false,
    expanded: Boolean = true,
    onToggleExpand: (() -> Unit)? = null,
    memosNavController: NavHostController,
    drawerState: DrawerState? = null,
    onDrawerItemCloseRequested: (() -> Unit)? = null
) {
    val scope = rememberCoroutineScope()
    val hapticFeedback = LocalHapticFeedback.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = (depth * 12).dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start
    ) {
        if (expandable && onToggleExpand != null) {
            IconButton(
                onClick = {
                    hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                    onToggleExpand()
                },
                modifier = Modifier.size(30.dp)
            ) {
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandMore else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null
                )
            }
        } else {
            Spacer(modifier = Modifier.width(30.dp))
        }

        NavigationDrawerItem(
            label = { Text(displayName) },
            icon = { Icon(Icons.Outlined.Tag, contentDescription = null) },
            selected = selected,
            onClick = {
                if (!enabled) {
                    return@NavigationDrawerItem
                }
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
            modifier = Modifier
                .weight(1f)
                .padding(end = 8.dp)
        )
    }
}
