package site.lcyk.keer.ui.page.group

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.PinDrop
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.DrawerState
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavHostController
import kotlinx.coroutines.launch
import site.lcyk.keer.R
import site.lcyk.keer.data.local.entity.MemoEntity
import site.lcyk.keer.data.model.Account
import site.lcyk.keer.data.model.Memo
import site.lcyk.keer.data.model.MemoEditGesture
import site.lcyk.keer.data.model.Settings
import site.lcyk.keer.ext.popBackStackIfLifecycleIsResumed
import site.lcyk.keer.ext.settingsDataStore
import site.lcyk.keer.ext.string
import site.lcyk.keer.ui.component.MemoActionMenuButton
import site.lcyk.keer.ui.component.MemoMenuAction
import site.lcyk.keer.ui.component.MemoMenuConfirmation
import site.lcyk.keer.ui.component.MemosCard
import site.lcyk.keer.ui.component.PullSyncLineIndicator
import site.lcyk.keer.ui.component.RefreshableListContainer
import site.lcyk.keer.ui.component.SyncStatusBadge
import site.lcyk.keer.ui.component.SyncAlertDialog
import site.lcyk.keer.ui.component.SyncAlertState
import site.lcyk.keer.ui.component.processManualSyncResult
import site.lcyk.keer.ui.component.rememberListEdgeHaptics
import site.lcyk.keer.ui.component.rememberAuthorizedImageLoader
import site.lcyk.keer.ui.page.common.LocalRootNavController
import site.lcyk.keer.ui.page.common.navigateToMemoDetailPage
import site.lcyk.keer.ui.page.common.navigateToSearchPage
import site.lcyk.keer.ui.page.common.navigateToTagPage
import site.lcyk.keer.ui.page.common.RouteName
import site.lcyk.keer.util.extractCollaboratorIds
import site.lcyk.keer.util.toMemoEntityForCard
import site.lcyk.keer.viewmodel.GroupChatViewModel
import site.lcyk.keer.viewmodel.LocalMemos
import site.lcyk.keer.viewmodel.LocalUserState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupChatPage(
    drawerState: DrawerState? = null,
    navController: NavHostController,
    groupId: String,
    onMenuButtonOpenRequested: (() -> Unit)? = null,
    viewModel: GroupChatViewModel = hiltViewModel()
) {
    val context = navController.context
    val rootNavController = LocalRootNavController.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val hapticFeedback = LocalHapticFeedback.current
    val memosViewModel = LocalMemos.current
    val userStateViewModel = LocalUserState.current
    val currentAccount by userStateViewModel.currentAccount.collectAsState()
    val collaboratorProfiles by userStateViewModel.collaboratorProfiles.collectAsState()
    val syncStatus by memosViewModel.syncStatus.collectAsState()
    val avatarImageLoader = rememberAuthorizedImageLoader()

    val settings by context.settingsDataStore.data.collectAsState(initial = Settings())
    val currentUserSettings = settings.usersList
        .firstOrNull { it.accountKey == settings.currentUser }
        ?.settings
    val groups = currentUserSettings?.groups.orEmpty()
    val resolvedGroupId = currentUserSettings
        ?.groupIdAliases
        .orEmpty()
        .firstOrNull { it.localId == groupId }
        ?.remoteId
        ?: groupId
    val activeAccountKey = settings.currentUser
    val currentUserId = when (val account = currentAccount) {
        is Account.KeerV2 -> account.info.id.toString()
        is Account.Local -> "local"
        null -> ""
    }
    val editGesture = settings.usersList
        .firstOrNull { it.accountKey == settings.currentUser }
        ?.settings
        ?.editGesture
        ?: MemoEditGesture.NONE
    val group = groups.firstOrNull { it.id == resolvedGroupId }

    val memos by viewModel.memos.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val listState = rememberLazyListState()
    val refreshState = rememberPullToRefreshState()
    val expandedFab by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex == 0
        }
    }
    val atTop = !listState.canScrollBackward
    val atBottom = memos.isNotEmpty() && !listState.canScrollForward
    var syncAlert by remember { mutableStateOf<SyncAlertState?>(null) }
    var syncWasRunning by remember { mutableStateOf(syncStatus.syncing) }

    suspend fun reloadGroup(forceSync: Boolean = false) {
        val resolvedGroup = group ?: return
        viewModel.loadGroupMemos(resolvedGroup.id, forceSync = forceSync)
    }

    suspend fun requestManualSync() {
        processManualSyncResult(memosViewModel.refreshMemos()) { alert ->
            syncAlert = alert
        }
    }

    LaunchedEffect(group?.id) {
        reloadGroup(forceSync = false)
    }

    rememberListEdgeHaptics(
        itemCount = memos.size,
        atTop = atTop,
        atBottom = atBottom
    )

    val collaboratorIdsToPrefetch = remember(memos) {
        memos
            .asSequence()
            .flatMap { memo -> extractCollaboratorIds(memo.tags).asSequence() }
            .distinct()
            .toList()
    }

    LaunchedEffect(collaboratorIdsToPrefetch) {
        if (collaboratorIdsToPrefetch.isNotEmpty()) {
            userStateViewModel.prefetchCollaboratorAvatars(collaboratorIdsToPrefetch)
        }
    }

    LaunchedEffect(syncStatus.syncing, group?.id) {
        val wasRunning = syncWasRunning
        syncWasRunning = syncStatus.syncing
        if (group != null && wasRunning && !syncStatus.syncing) {
            reloadGroup(forceSync = false)
        }
    }

    if (group == null) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(R.string.group_not_found.string) },
                    navigationIcon = {
                        IconButton(
                            onClick = {
                                if (drawerState != null) {
                                    onMenuButtonOpenRequested?.invoke()
                                    hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                                    scope.launch { drawerState.open() }
                                } else {
                                    navController.popBackStackIfLifecycleIsResumed(lifecycleOwner)
                                }
                            }
                        ) {
                            Icon(
                                imageVector = if (drawerState != null) Icons.Filled.Menu else Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = if (drawerState != null) R.string.menu.string else R.string.back.string
                            )
                        }
                    }
                )
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = R.string.group_not_found.string,
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(group.name) },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            if (drawerState != null) {
                                onMenuButtonOpenRequested?.invoke()
                                hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                                scope.launch { drawerState.open() }
                            } else {
                                navController.popBackStackIfLifecycleIsResumed(lifecycleOwner)
                            }
                        }
                    ) {
                        Icon(
                            imageVector = if (drawerState != null) Icons.Filled.Menu else Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = if (drawerState != null) R.string.menu.string else R.string.back.string
                        )
                    }
                },
                actions = {
                    if (currentAccount !is Account.Local && syncStatus.syncing) {
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
                    navController.navigate("${RouteName.GROUP_INPUT}?groupId=${Uri.encode(group.id)}")
                },
                expanded = expandedFab,
                text = { Text(R.string.new_memo.string) },
                icon = { Icon(Icons.Filled.Add, contentDescription = R.string.compose.string) }
            )
        }
    ) { innerPadding ->
        RefreshableListContainer(
            isRefreshing = syncStatus.syncing,
            onRefresh = {
                if (syncStatus.syncing) {
                    return@RefreshableListContainer
                }
                scope.launch {
                    requestManualSync()
                }
            },
            state = refreshState,
            indicator = {
                PullSyncLineIndicator(
                    refreshState = refreshState,
                    syncing = syncStatus.syncing,
                    hapticFeedback = hapticFeedback
                )
            },
            modifier = Modifier.padding(innerPadding),
            isEmpty = memos.isEmpty() && errorMessage.isNullOrBlank() && !loading,
            emptyContent = {
                Text(
                    text = R.string.no_memos.string,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 24.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                items(
                    items = memos,
                    key = { it.remoteId },
                    contentType = { "memo" }
                ) { memo ->
                    val adaptedMemo = remember(memo, activeAccountKey, group.id) {
                        memo.toGroupMemoEntity(
                            accountKey = activeAccountKey,
                            groupId = group.id
                        )
                    }
                    val canManageMemo = remember(memo, currentUserId) {
                        viewModel.canManageGroupMemo(memo, currentUserId)
                    }
                    MemosCard(
                        memo = adaptedMemo,
                        onClick = { selectedMemo ->
                            memosViewModel.cacheMemoForDetail(selectedMemo)
                            rootNavController.navigateToMemoDetailPage(selectedMemo.identifier)
                        },
                        editGesture = editGesture,
                        previewMode = true,
                        showSyncStatus = true,
                        authorAvatarUrl = memo.creator?.avatarUrl,
                        authorName = memo.creator?.name,
                        actionButton = { memoEntity ->
                            GroupMemoCardActionButton(
                                pinned = memoEntity.pinned,
                                canManage = canManageMemo,
                                onTogglePinned = {
                                    scope.launch {
                                        viewModel.setGroupMemoPinned(
                                            groupId = group.id,
                                            memoRemoteId = memo.remoteId,
                                            pinned = !memoEntity.pinned
                                        )
                                    }
                                },
                                onEdit = {
                                    navController.navigate(
                                        "${RouteName.GROUP_INPUT}?groupId=${Uri.encode(group.id)}&memoId=${Uri.encode(memo.remoteId)}"
                                    )
                                },
                                onDelete = {
                                    scope.launch {
                                        viewModel.deleteGroupMemo(
                                            groupId = group.id,
                                            memoRemoteId = memo.remoteId
                                        )
                                    }
                                }
                            )
                        },
                        onTagClick = { tag ->
                            navController.navigateToTagPage(tag)
                        },
                        collaboratorProfiles = collaboratorProfiles,
                        avatarImageLoader = avatarImageLoader,
                        prefetchCollaborators = false
                    )
                }

                if (!errorMessage.isNullOrBlank()) {
                    item {
                        Text(
                            text = errorMessage.orEmpty(),
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                }
            }
        }
    }

    SyncAlertDialog(
        alert = syncAlert,
        onDismiss = { syncAlert = null }
    )
}

private fun Memo.toGroupMemoEntity(
    accountKey: String,
    groupId: String
): MemoEntity {
    return toMemoEntityForCard(
        identifier = "group:$groupId:$remoteId",
        accountKey = accountKey,
        needsSync = remoteId.startsWith("local:")
    )
}

@Composable
private fun GroupMemoCardActionButton(
    pinned: Boolean,
    canManage: Boolean,
    onTogglePinned: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val actions = buildList {
        add(
            MemoMenuAction(
                key = if (pinned) "unpin" else "pin",
                label = if (pinned) R.string.unpin.string else R.string.pin.string,
                icon = if (pinned) Icons.Outlined.PinDrop else Icons.Outlined.PushPin,
                onSelected = onTogglePinned
            )
        )
        if (canManage) {
            add(
                MemoMenuAction(
                    key = "edit",
                    label = R.string.edit.string,
                    icon = Icons.Outlined.Edit,
                    onSelected = onEdit
                )
            )
            add(
                MemoMenuAction(
                    key = "delete",
                    label = R.string.delete.string,
                    icon = Icons.Outlined.Delete,
                    destructive = true,
                    confirmation = MemoMenuConfirmation(
                        title = R.string.delete_this_memo.string,
                        confirmLabel = R.string.confirm.string,
                        cancelLabel = R.string.cancel.string
                    ),
                    onSelected = onDelete
                )
            )
        }
    }
    MemoActionMenuButton(actions = actions)
}
