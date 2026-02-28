package site.lcyk.keer.ui.page.memos

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.navigation.NavHostController
import kotlinx.coroutines.launch
import site.lcyk.keer.R
import site.lcyk.keer.data.model.Account
import site.lcyk.keer.data.model.Settings
import site.lcyk.keer.ext.string
import site.lcyk.keer.ext.settingsDataStore
import site.lcyk.keer.ui.component.SyncStatusBadge
import site.lcyk.keer.ui.page.common.LocalRootNavController
import site.lcyk.keer.ui.page.common.RouteName
import site.lcyk.keer.viewmodel.LocalMemos
import site.lcyk.keer.viewmodel.ManualSyncResult
import site.lcyk.keer.viewmodel.LocalUserState
import java.net.URLEncoder

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
    val context = LocalContext.current
    val currentAccount by userStateViewModel.currentAccount.collectAsState()
    val settings by context.settingsDataStore.data.collectAsState(initial = Settings())
    val syncStatus by memosViewModel.syncStatus.collectAsState()
    val hapticFeedback = LocalHapticFeedback.current
    val currentUser = userStateViewModel.currentUser

    val expandedFab by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex == 0
        }
    }
    val createdGroups = remember(settings, currentUser?.identifier) {
        val groups = settings.usersList
            .firstOrNull { it.accountKey == settings.currentUser }
            ?.settings
            ?.groups
            .orEmpty()
        val creatorId = currentUser?.identifier
        groups
            .filter { group -> group.creatorId == creatorId }
    }
    var showCreatedGroupMemosOnly by rememberSaveable { mutableStateOf(false) }
    var syncAlert by remember { mutableStateOf<HomeSyncAlert?>(null) }

    LaunchedEffect(createdGroups) {
        if (createdGroups.isEmpty()) {
            showCreatedGroupMemosOnly = false
        }
    }

    LaunchedEffect(showCreatedGroupMemosOnly, createdGroups, currentUser?.identifier) {
        if (showCreatedGroupMemosOnly) {
            memosViewModel.loadCreatedGroupMemos(createdGroups, currentUser?.identifier)
        }
    }

    suspend fun requestManualSync() {
        when (val result = memosViewModel.refreshMemos()) {
            ManualSyncResult.Completed -> Unit
            is ManualSyncResult.Blocked -> {
                syncAlert = HomeSyncAlert.Blocked(result.message)
            }
            is ManualSyncResult.Failed -> {
                syncAlert = HomeSyncAlert.Failed(result.message)
            }
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
                        navController.navigate(RouteName.SEARCH)
                    }) {
                        Icon(Icons.Filled.Search, contentDescription = R.string.search.string)
                    }
                    if (createdGroups.isNotEmpty()) {
                        IconButton(onClick = {
                            showCreatedGroupMemosOnly = !showCreatedGroupMemosOnly
                        }) {
                            Icon(
                                imageVector = Icons.Outlined.Group,
                                contentDescription = R.string.created_group_memos.string,
                                tint = if (showCreatedGroupMemosOnly) {
                                    androidx.compose.material3.MaterialTheme.colorScheme.primary
                                } else {
                                    androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }
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
            if (showCreatedGroupMemosOnly) {
                CreatedGroupMemosList(
                    contentPadding = innerPadding,
                    memos = memosViewModel.createdGroupMemos,
                    loading = memosViewModel.createdGroupMemosLoading,
                    errorMessage = memosViewModel.createdGroupMemosErrorMessage,
                    onRefresh = {
                        memosViewModel.loadCreatedGroupMemos(createdGroups, currentUser?.identifier)
                    }
                )
            } else {
                MemosList(
                    lazyListState = listState,
                    contentPadding = innerPadding,
                    onRefresh = { requestManualSync() },
                    onTagClick = { tag ->
                        navController.navigate("${RouteName.TAG}/${URLEncoder.encode(tag, "UTF-8")}") {
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                )
            }
        }
    )

    when (val alert = syncAlert) {
        null -> Unit
        is HomeSyncAlert.Blocked -> {
            AlertDialog(
                onDismissRequest = { syncAlert = null },
                title = { Text(R.string.unsupported_memos_version_title.string) },
                text = { Text(alert.message) },
                confirmButton = {
                    TextButton(onClick = { syncAlert = null }) {
                        Text(R.string.close.string)
                    }
                }
            )
        }
        is HomeSyncAlert.Failed -> {
            AlertDialog(
                onDismissRequest = { syncAlert = null },
                title = { Text(R.string.sync_failed.string) },
                text = { Text(alert.message) },
                confirmButton = {
                    TextButton(onClick = { syncAlert = null }) {
                        Text(R.string.close.string)
                    }
                }
            )
        }
    }
}

private sealed class HomeSyncAlert {
    data class Blocked(val message: String) : HomeSyncAlert()
    data class Failed(val message: String) : HomeSyncAlert()
}
