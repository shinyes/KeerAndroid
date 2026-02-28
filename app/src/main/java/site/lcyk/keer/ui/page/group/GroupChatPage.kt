package site.lcyk.keer.ui.page.group

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.PinDrop
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.DrawerState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import site.lcyk.keer.data.local.entity.ResourceEntity
import site.lcyk.keer.data.model.Account
import site.lcyk.keer.data.model.Memo
import site.lcyk.keer.data.model.MemoEditGesture
import site.lcyk.keer.data.model.Settings
import site.lcyk.keer.ext.popBackStackIfLifecycleIsResumed
import site.lcyk.keer.ext.settingsDataStore
import site.lcyk.keer.ext.string
import site.lcyk.keer.ui.component.MemosCard
import site.lcyk.keer.ui.component.SyncStatusBadge
import site.lcyk.keer.ui.page.common.RouteName
import site.lcyk.keer.viewmodel.GroupChatViewModel
import site.lcyk.keer.viewmodel.LocalMemos
import site.lcyk.keer.viewmodel.LocalUserState
import site.lcyk.keer.viewmodel.ManualSyncResult
import java.net.URLEncoder
import java.time.Instant

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
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val hapticFeedback = LocalHapticFeedback.current
    val memosViewModel = LocalMemos.current
    val userStateViewModel = LocalUserState.current
    val currentAccount by userStateViewModel.currentAccount.collectAsState()
    val syncStatus by memosViewModel.syncStatus.collectAsState()

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
    var syncAlert by remember { mutableStateOf<GroupSyncAlert?>(null) }
    var syncWasRunning by remember { mutableStateOf(syncStatus.syncing) }

    suspend fun reloadGroup(forceSync: Boolean = false) {
        val resolvedGroup = group ?: return
        viewModel.loadGroupMemos(resolvedGroup.id, forceSync = forceSync)
    }

    suspend fun requestManualSync() {
        when (val result = memosViewModel.refreshMemos()) {
            ManualSyncResult.Completed -> Unit
            is ManualSyncResult.Blocked -> {
                syncAlert = GroupSyncAlert.Blocked(result.message)
            }
            is ManualSyncResult.Failed -> {
                syncAlert = GroupSyncAlert.Failed(result.message)
            }
        }
    }

    LaunchedEffect(group?.id) {
        reloadGroup(forceSync = false)
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
        PullToRefreshBox(
            isRefreshing = loading,
            onRefresh = {
                scope.launch { reloadGroup(forceSync = true) }
            },
            state = refreshState,
            modifier = Modifier.padding(innerPadding)
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                if (memos.isEmpty() && !loading) {
                    item {
                        Text(
                            text = R.string.no_memos.string,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 24.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                items(memos, key = { it.remoteId }) { memo ->
                    val adaptedMemo = remember(memo, activeAccountKey, group.id) {
                        memo.toGroupMemoEntity(
                            accountKey = activeAccountKey,
                            groupId = group.id
                        )
                    }
                    MemosCard(
                        memo = adaptedMemo,
                        onClick = { },
                        editGesture = editGesture,
                        previewMode = true,
                        showSyncStatus = true,
                        authorAvatarUrl = memo.creator?.avatarUrl,
                        authorName = memo.creator?.name,
                        actionButton = { memoEntity ->
                            GroupMemoCardActionButton(
                                pinned = memoEntity.pinned,
                                onTogglePinned = {
                                    scope.launch {
                                        viewModel.setGroupMemoPinned(
                                            groupId = group.id,
                                            memoRemoteId = memo.remoteId,
                                            pinned = !memoEntity.pinned
                                        )
                                    }
                                }
                            )
                        },
                        onTagClick = { tag ->
                            navController.navigate("${RouteName.TAG}/${URLEncoder.encode(tag, "UTF-8")}") {
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
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

    when (val alert = syncAlert) {
        null -> Unit
        is GroupSyncAlert.Blocked -> {
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
        is GroupSyncAlert.Failed -> {
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

private sealed class GroupSyncAlert {
    data class Blocked(val message: String) : GroupSyncAlert()
    data class Failed(val message: String) : GroupSyncAlert()
}

private fun Memo.toGroupMemoEntity(
    accountKey: String,
    groupId: String
): MemoEntity {
    val memoIdentifier = "group:$groupId:$remoteId"
    val syncedAt = updatedAt ?: date
    val entity = MemoEntity(
        identifier = memoIdentifier,
        remoteId = remoteId,
        accountKey = accountKey,
        content = content,
        date = date,
        visibility = visibility,
        pinned = pinned,
        archived = archived,
        latitude = latitude,
        longitude = longitude,
        needsSync = remoteId.startsWith("local:"),
        isDeleted = false,
        lastModified = updatedAt ?: date,
        lastSyncedAt = syncedAt
    )
    entity.resources = resources.map { resource ->
        ResourceEntity(
            identifier = "group:$groupId:$remoteId:resource:${resource.remoteId}",
            remoteId = resource.remoteId,
            accountKey = accountKey,
            date = resource.date,
            filename = resource.filename,
            uri = resource.uri,
            localUri = resource.localUri,
            mimeType = resource.mimeType,
            thumbnailUri = resource.thumbnailUri,
            thumbnailLocalUri = resource.thumbnailLocalUri,
            memoId = memoIdentifier
        )
    }
    entity.tags = tags
    return entity
}

@Composable
private fun GroupMemoCardActionButton(
    pinned: Boolean,
    onTogglePinned: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }

    IconButton(onClick = { menuExpanded = true }) {
        Icon(Icons.Filled.MoreVert, contentDescription = null)
    }
    DropdownMenu(
        expanded = menuExpanded,
        onDismissRequest = { menuExpanded = false }
    ) {
        if (pinned) {
            DropdownMenuItem(
                text = { Text(R.string.unpin.string) },
                onClick = {
                    onTogglePinned()
                    menuExpanded = false
                },
                leadingIcon = {
                    Icon(Icons.Outlined.PinDrop, contentDescription = null)
                },
                colors = MenuDefaults.itemColors(
                    textColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    leadingIconColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            )
        } else {
            DropdownMenuItem(
                text = { Text(R.string.pin.string) },
                onClick = {
                    onTogglePinned()
                    menuExpanded = false
                },
                leadingIcon = {
                    Icon(Icons.Outlined.PushPin, contentDescription = null)
                }
            )
        }
    }
}
