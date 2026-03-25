package site.lcyk.keer.ui.page.memos

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import site.lcyk.keer.R
import site.lcyk.keer.data.model.Account
import site.lcyk.keer.ext.string
import site.lcyk.keer.ui.component.SyncAlertDialog
import site.lcyk.keer.ui.component.SyncAlertState
import site.lcyk.keer.ui.component.SyncStatusBadge
import site.lcyk.keer.ui.component.processManualSyncResult
import site.lcyk.keer.ui.component.MemosCardActionButton
import site.lcyk.keer.ui.page.memoinput.QuickMemoComposer
import site.lcyk.keer.ui.page.common.LocalRootNavController
import site.lcyk.keer.ui.page.common.RouteName
import site.lcyk.keer.ui.page.common.navigateToGroupInputPage
import site.lcyk.keer.ui.page.common.navigateToSearchPage
import site.lcyk.keer.ui.page.common.navigateToTagPage
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
    val currentAccount by userStateViewModel.currentAccount.collectAsStateWithLifecycle()
    val homeMemos by memosViewModel.visibleHomeMemos.collectAsStateWithLifecycle()
    val homeMemoItemsById = remember(homeMemos) {
        homeMemos.associateBy { item -> item.memo.identifier }
    }
    val homeFeedMemos = remember(homeMemos) {
        homeMemos.map { item -> item.memo }
    }

    val expandedFab by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex == 0
        }
    }
    var syncAlert by remember { mutableStateOf<SyncAlertState?>(null) }
    var showQuickComposer by rememberSaveable { mutableStateOf(false) }

    suspend fun requestManualSync() {
        processManualSyncResult(memosViewModel.refreshHomeFeed()) { alert ->
            syncAlert = alert
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(text = R.string.memos.string) },
                    navigationIcon = {
                        if (drawerState != null) {
                            IconButton(onClick = {
                                onMenuButtonOpenRequested?.invoke()
                                scope.launch { drawerState.open() }
                            }) {
                                Icon(Icons.Filled.Menu, contentDescription = R.string.menu.string)
                            }
                        }
                    },
                    actions = {
                        if (currentAccount !is Account.Local) {
                            HomeSyncBadgeAction(
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
                if (!showQuickComposer) {
                    ExtendedFloatingActionButton(
                        onClick = { showQuickComposer = true },
                        expanded = expandedFab,
                        text = { Text(R.string.new_memo.string) },
                        icon = { Icon(Icons.Filled.Add, contentDescription = R.string.compose.string) }
                    )
                }
            },

            content = { innerPadding ->
                MemosList(
                    memos = homeFeedMemos,
                    lazyListState = listState,
                    contentPadding = innerPadding,
                    onRefresh = { requestManualSync() },
                    onTagClick = { tag ->
                        navController.navigateToTagPage(tag)
                    },
                    onRequestEdit = { memo ->
                        val item = homeMemoItemsById[memo.identifier]
                        if (item?.groupId.isNullOrBlank()) {
                            rootNavController.navigate("${RouteName.EDIT}?memoId=${memo.identifier}")
                        } else {
                            navController.navigateToGroupInputPage(
                                groupId = requireNotNull(item.groupId),
                                memoId = memo.remoteId
                            )
                        }
                    },
                    editGestureResolver = { memo, defaultGesture ->
                        val item = homeMemoItemsById[memo.identifier]
                        if (item?.groupId.isNullOrBlank()) {
                            defaultGesture
                        } else {
                            site.lcyk.keer.data.model.MemoEditGesture.NONE
                        }
                    },
                    actionButton = { memo ->
                        val item = homeMemoItemsById[memo.identifier]
                        if (item?.groupId.isNullOrBlank()) {
                            MemosCardActionButton(
                                memo = memo,
                                onRequestEdit = { target ->
                                    rootNavController.navigate("${RouteName.EDIT}?memoId=${target.identifier}")
                                }
                            )
                        } else {
                            HomeGroupMemoActionButton(
                                memo = memo,
                                groupId = requireNotNull(item.groupId),
                                onRequestEdit = {
                                    navController.navigateToGroupInputPage(
                                        groupId = requireNotNull(item.groupId),
                                        memoId = memo.remoteId
                                    )
                                }
                            )
                        }
                    }
                )
            }
        )

        QuickMemoComposer(
            visible = showQuickComposer,
            onDismissRequest = { showQuickComposer = false }
        )
    }

    SyncAlertDialog(
        alert = syncAlert,
        onDismiss = { syncAlert = null }
    )
}

@Composable
private fun HomeSyncBadgeAction(
    onSync: () -> Unit,
) {
    val memosViewModel = LocalMemos.current
    val syncState by memosViewModel.syncStatus.collectAsStateWithLifecycle()
    
    if (!syncState.syncing) {
        return
    }
    SyncStatusBadge(
        syncing = syncState.syncing,
        unsyncedCount = syncState.unsyncedCount,
        progress = syncState.progress,
        onSync = onSync,
    )
}
