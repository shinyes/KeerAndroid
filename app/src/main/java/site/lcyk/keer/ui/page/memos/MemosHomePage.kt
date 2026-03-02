package site.lcyk.keer.ui.page.memos

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DrawerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.navigation.NavHostController
import kotlinx.coroutines.launch
import site.lcyk.keer.R
import site.lcyk.keer.data.model.Account
import site.lcyk.keer.ext.string
import site.lcyk.keer.ui.component.SyncAlertDialog
import site.lcyk.keer.ui.component.SyncAlertState
import site.lcyk.keer.ui.component.SyncStatusBadge
import site.lcyk.keer.ui.component.handleManualSyncResult
import site.lcyk.keer.ui.page.common.LocalRootNavController
import site.lcyk.keer.ui.page.common.navigateToSearchPage
import site.lcyk.keer.ui.page.common.navigateToTagPage
import site.lcyk.keer.ui.page.common.RouteName
import site.lcyk.keer.viewmodel.LocalMemos
import site.lcyk.keer.viewmodel.LocalUserState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemosHomePage(
    drawerState: DrawerState? = null,
    navController: NavHostController,
    onMenuButtonOpenRequested: (() -> Unit)? = null
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val rootNavController = LocalRootNavController.current
    val memosViewModel = LocalMemos.current
    val userStateViewModel = LocalUserState.current
    val currentAccount by userStateViewModel.currentAccount.collectAsState()
    val syncStatus by memosViewModel.syncStatus.collectAsState()
    val hapticFeedback = LocalHapticFeedback.current

    val expandedFab by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex == 0
        }
    }
    var syncAlert by remember { mutableStateOf<SyncAlertState?>(null) }

    suspend fun requestManualSync() {
        handleManualSyncResult(memosViewModel.refreshMemos())?.let { alert ->
            syncAlert = alert
        }
    }


    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = R.string.memos.string) },
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
                actions = {
                    if (currentAccount !is Account.Local) {
                        SyncStatusBadge(
                            syncing = syncStatus.syncing,
                            unsyncedCount = syncStatus.unsyncedCount,
                            progress = syncStatus.progress,
                            onSync = {
                                scope.launch {
                                    requestManualSync()
                                }
                            }
                        )
                    }
                    IconButton(onClick = {
                        navController.navigateToSearchPage()
                    }) {
                        Icon(Icons.Filled.Search, contentDescription = R.string.search.string)
                    }
                }
            )
        },

        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                    rootNavController.navigate(RouteName.INPUT)
                },
                expanded = expandedFab,
                text = { Text(R.string.new_memo.string) },
                icon = { Icon(Icons.Filled.Add, contentDescription = R.string.compose.string) }
            )
        },

        content = { innerPadding ->
            MemosList(
                lazyListState = listState,
                contentPadding = innerPadding,
                onRefresh = { requestManualSync() },
                onTagClick = { tag ->
                    navController.navigateToTagPage(tag)
                }
            )
        }
    )

    SyncAlertDialog(
        alert = syncAlert,
        onDismiss = { syncAlert = null }
    )
}
