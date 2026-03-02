package site.lcyk.keer.ui.page.common

import android.net.Uri
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController

fun NavHostController.navigateToTopLevel(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) {
            saveState = true
        }
        launchSingleTop = true
        restoreState = true
    }
}

fun NavHostController.navigateSingleTop(route: String) {
    navigate(route) {
        launchSingleTop = true
        restoreState = true
    }
}

fun NavHostController.navigateReplacingCurrent(
    route: String,
    destinationPattern: String
) {
    navigate(route) {
        popUpTo(destinationPattern) {
            inclusive = true
        }
        launchSingleTop = true
        restoreState = true
    }
}

fun NavHostController.navigateToSearchPage() {
    navigateSingleTop(RouteName.SEARCH)
}

fun NavHostController.navigateToTagPage(tag: String) {
    navigateReplacingCurrent(
        route = "${RouteName.TAG}/${Uri.encode(tag)}",
        destinationPattern = "${RouteName.TAG}/{tag}"
    )
}

fun NavHostController.navigateToMemoDetailPage(memoIdentifier: String) {
    navigateReplacingCurrent(
        route = "${RouteName.MEMO_DETAIL}?memoId=${Uri.encode(memoIdentifier)}",
        destinationPattern = "${RouteName.MEMO_DETAIL}?memoId={memoId}"
    )
}
