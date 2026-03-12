package site.lcyk.keer.ui.page.settings

import androidx.compose.material3.DrawerState
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController

@Composable
fun AvatarSettingsPage(
    drawerState: DrawerState? = null,
    navController: NavHostController,
    onMenuButtonOpenRequested: (() -> Unit)? = null
) {
    SettingsPage(
        drawerState = drawerState,
        navController = navController,
        onMenuButtonOpenRequested = onMenuButtonOpenRequested
    )
}
