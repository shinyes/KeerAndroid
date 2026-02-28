package site.lcyk.keer.ui.page.common

import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.util.Consumer
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.launch
import site.lcyk.keer.MainActivity
import site.lcyk.keer.ext.string
import site.lcyk.keer.ui.page.account.AccountPage
import site.lcyk.keer.ui.page.account.AddAccountPage
import site.lcyk.keer.ui.page.login.LoginPage
import site.lcyk.keer.ui.page.memoinput.MemoInputPage
import site.lcyk.keer.ui.page.memos.MemoDetailPage
import site.lcyk.keer.ui.page.memos.MemosPage
import site.lcyk.keer.ui.page.memos.SearchPage
import site.lcyk.keer.ui.page.memos.TagMemoPage
import site.lcyk.keer.ui.page.resource.ResourceListPage
import site.lcyk.keer.ui.page.settings.AvatarSettingsPage
import site.lcyk.keer.ui.page.settings.DebugLogPage
import site.lcyk.keer.ui.page.settings.SettingsPage
import site.lcyk.keer.ui.theme.KeerTheme
import site.lcyk.keer.util.ForegroundSyncCoordinator
import site.lcyk.keer.data.service.SyncTrigger
import site.lcyk.keer.viewmodel.LocalMemos
import site.lcyk.keer.viewmodel.LocalUserState

@Composable
fun Navigation() {
    val navController = rememberNavController()
    val userStateViewModel = LocalUserState.current
    val memosViewModel = LocalMemos.current
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val foregroundSyncCoordinator = remember { ForegroundSyncCoordinator() }

    suspend fun runForegroundSync(trigger: SyncTrigger) {
        if (!userStateViewModel.hasAnyAccount()) {
            return
        }
        userStateViewModel.loadCurrentUser()
        memosViewModel.loadMemos(
            syncAfterLoad = true,
            trigger = trigger
        )
    }

    CompositionLocalProvider(LocalRootNavController provides navController) {
        KeerTheme {
            NavHost(
                modifier = Modifier.background(MaterialTheme.colorScheme.surface),
                navController = navController,
                startDestination = RouteName.MEMOS,
                enterTransition = {
                    slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Up,
                        initialOffset = { it / 4 }) + fadeIn()
                },
                exitTransition = {
                    slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Down,
                        targetOffset = { it / 4 }) + fadeOut()
                },
            ) {
                composable(RouteName.MEMOS) {
                    MemosPage()
                }

                composable(RouteName.CONFIG) {
                    SettingsPage(navController = navController)
                }

                composable(RouteName.SETTINGS) {
                    AvatarSettingsPage(navController = navController)
                }

                composable(RouteName.DEBUG_LOGS) {
                    DebugLogPage(navController = navController)
                }

                composable(RouteName.GROUP_MANAGEMENT) {
                    MemosPage(startDestination = RouteName.GROUP_MANAGEMENT)
                }

                composable(RouteName.ADD_ACCOUNT) {
                    AddAccountPage(navController = navController)
                }

                composable(RouteName.LOGIN) {
                    LoginPage(navController = navController)
                }

                composable(RouteName.INPUT) {
                    MemoInputPage()
                }

                composable("${RouteName.EDIT}?memoId={id}"
                ) { entry ->
                    MemoInputPage(memoIdentifier = entry.arguments?.getString("id"))
                }

                composable(RouteName.RESOURCE) {
                    ResourceListPage(navController = navController)
                }

                composable("${RouteName.ACCOUNT}?accountKey={accountKey}") { entry ->
                    AccountPage(
                        navController = navController,
                        selectedAccountKey = entry.arguments?.getString("accountKey") ?: ""
                    )
                }

                composable(RouteName.SEARCH) {
                    SearchPage(navController = navController)
                }

                composable("${RouteName.TAG}/{tag}") { entry ->
                    val tag = entry.arguments?.getString("tag")?.let(Uri::decode) ?: ""
                    TagMemoPage(tag = tag, navController = navController)
                }

                composable("${RouteName.MEMO_DETAIL}?memoId={memoId}") { entry ->
                    val memoId = entry.arguments?.getString("memoId")
                    if (memoId != null) {
                        MemoDetailPage(navController = navController, memoIdentifier = Uri.decode(memoId))
                    }
                }

                composable("${RouteName.GROUP_CHAT}?groupId={groupId}") { entry ->
                    val groupId = entry.arguments?.getString("groupId")
                    if (groupId != null) {
                        MemosPage(
                            startDestination = "${RouteName.GROUP_CHAT}?groupId=${Uri.encode(Uri.decode(groupId))}"
                        )
                    }
                }

                composable("${RouteName.GROUP_INPUT}?groupId={groupId}") { entry ->
                    val groupId = entry.arguments?.getString("groupId")
                    if (groupId != null) {
                        MemosPage(
                            startDestination = "${RouteName.GROUP_INPUT}?groupId=${Uri.encode(Uri.decode(groupId))}"
                        )
                    }
                }
            }
        }
    }


    LaunchedEffect(Unit) {
        if (!userStateViewModel.hasAnyAccount()) {
            if (navController.currentDestination?.route != RouteName.ADD_ACCOUNT) {
                navController.navigate(RouteName.ADD_ACCOUNT) {
                    popUpTo(navController.graph.id) {
                        inclusive = true
                    }
                    launchSingleTop = true
                }
            }
            return@LaunchedEffect
        }
        if (foregroundSyncCoordinator.requestAppStartSync()) {
            try {
                runForegroundSync(trigger = SyncTrigger.APP_START)
            } finally {
                foregroundSyncCoordinator.completeSync()
            }
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                if (!foregroundSyncCoordinator.requestResumeSync()) {
                    return@LifecycleEventObserver
                }
                scope.launch {
                    try {
                        runForegroundSync(trigger = SyncTrigger.APP_FOREGROUND)
                    } finally {
                        foregroundSyncCoordinator.completeSync()
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    fun handleIntent(intent: Intent) {
        when(intent.action) {
            Intent.ACTION_VIEW -> {
                when (intent.getStringExtra("action")) {
                    "compose" -> navController.navigate(RouteName.INPUT)
                    "search" -> navController.navigate(RouteName.SEARCH)
                }
            }
            MainActivity.ACTION_NEW_MEMO -> {
                navController.navigate(RouteName.INPUT)
            }
            MainActivity.ACTION_EDIT_MEMO -> {
                val memoId = intent.getStringExtra(MainActivity.EXTRA_MEMO_ID)
                if (memoId != null) {
                    navController.navigate("${RouteName.EDIT}?memoId=$memoId")
                }
            }
            MainActivity.ACTION_VIEW_MEMO -> {
                val memoId = intent.getStringExtra(MainActivity.EXTRA_MEMO_ID)
                if (memoId != null) {
                    navController.navigate("${RouteName.MEMO_DETAIL}?memoId=${Uri.encode(memoId)}")
                }
            }
        }
    }

    LaunchedEffect(context) {
        if (context is ComponentActivity && context.intent != null) {
            handleIntent(context.intent)
        }
    }

    DisposableEffect(context) {
        val activity = context as? ComponentActivity

        val listener = Consumer<Intent> {
            handleIntent(it)
        }

        activity?.addOnNewIntentListener(listener)

        onDispose {
            activity?.removeOnNewIntentListener(listener)
        }
    }
}

val LocalRootNavController =
    compositionLocalOf<NavHostController> { error(site.lcyk.keer.R.string.nav_host_controller_not_found.string) }
