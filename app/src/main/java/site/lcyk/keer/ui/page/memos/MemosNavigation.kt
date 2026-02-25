package site.lcyk.keer.ui.page.memos

import android.net.Uri
import androidx.compose.material3.DrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import site.lcyk.keer.data.model.Account
import site.lcyk.keer.ui.page.common.RouteName
import site.lcyk.keer.viewmodel.LocalUserState

@Composable
fun MemosNavigation(
    drawerState: DrawerState? = null,
    navController: NavHostController,
    onMenuButtonOpenRequested: (() -> Unit)? = null
) {
    val userStateViewModel = LocalUserState.current
    val currentAccount by userStateViewModel.currentAccount.collectAsState()
    val hasExplore = currentAccount !is Account.Local

    NavHost(
        navController = navController,
        startDestination = RouteName.MEMOS
    ) {
        composable(
            RouteName.MEMOS,
        ) {
            MemosHomePage(
                drawerState = drawerState,
                navController = navController,
                onMenuButtonOpenRequested = onMenuButtonOpenRequested
            )
        }

        composable(
            RouteName.ARCHIVED
        ) {
            ArchivedMemoPage(
                drawerState = drawerState,
                onMenuButtonOpenRequested = onMenuButtonOpenRequested
            )
        }

        composable(
            "${RouteName.TAG}/{tag}"
        ) { entry ->
            TagMemoPage(
                drawerState = drawerState,
                tag = entry.arguments?.getString("tag")?.let(Uri::decode) ?: "",
                navController = navController,
                onMenuButtonOpenRequested = onMenuButtonOpenRequested
            )
        }

        composable(
            RouteName.EXPLORE
        ) {
            if (hasExplore) {
                ExplorePage(
                    drawerState = drawerState,
                    onMenuButtonOpenRequested = onMenuButtonOpenRequested
                )
            } else {
                LaunchedEffect(Unit) {
                    navController.navigate(RouteName.MEMOS) {
                        popUpTo(RouteName.EXPLORE) {
                            inclusive = true
                        }
                        launchSingleTop = true
                    }
                }
            }
        }

        composable(RouteName.SEARCH) {
            SearchPage(navController = navController)
        }
    }
}
