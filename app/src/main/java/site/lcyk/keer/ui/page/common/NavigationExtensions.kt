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

fun NavHostController.navigateToAccountPage(accountKey: String) {
    navigateSingleTop("${RouteName.ACCOUNT}?accountKey=${Uri.encode(accountKey)}")
}

fun NavHostController.navigateToAddAccountPage() {
    navigateSingleTop(RouteName.ADD_ACCOUNT)
}

fun NavHostController.navigateToDebugLogsPage() {
    navigateSingleTop(RouteName.DEBUG_LOGS)
}

fun NavHostController.navigateToGroupChatPage(groupId: String) {
    navigateToTopLevel("${RouteName.GROUP_CHAT}?groupId=${Uri.encode(groupId)}")
}

fun NavHostController.navigateToGroupInputPage(
    groupId: String,
    memoId: String? = null
) {
    val encodedGroupId = Uri.encode(groupId)
    val route = if (memoId.isNullOrBlank()) {
        "${RouteName.GROUP_INPUT}?groupId=$encodedGroupId"
    } else {
        "${RouteName.GROUP_INPUT}?groupId=$encodedGroupId&memoId=${Uri.encode(memoId)}"
    }
    navigateSingleTop(route)
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
