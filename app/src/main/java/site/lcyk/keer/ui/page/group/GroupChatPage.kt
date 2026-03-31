package site.lcyk.keer.ui.page.group

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.PinDrop
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material3.DrawerState
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import site.lcyk.keer.R
import site.lcyk.keer.data.local.entity.MemoEntity
import site.lcyk.keer.data.model.Account
import site.lcyk.keer.data.model.Memo
import site.lcyk.keer.data.model.MemoEditGesture
import site.lcyk.keer.ext.popBackStackIfLifecycleIsResumed
import site.lcyk.keer.ext.string
import site.lcyk.keer.ui.component.MemoActionMenuButton
import site.lcyk.keer.ui.component.MemoMenuAction
import site.lcyk.keer.ui.component.MemoMenuConfirmation
import site.lcyk.keer.ui.component.MemosCard
import site.lcyk.keer.ui.component.MediaPreviewPrefetchEffect
import site.lcyk.keer.ui.component.MemoPreviewWarmupEffect
import site.lcyk.keer.ui.component.PullSyncLineIndicator
import site.lcyk.keer.ui.component.RefreshableListContainer
import site.lcyk.keer.ui.component.SurfaceHydrationLineOverlay
import site.lcyk.keer.ui.component.SyncAlertDialog
import site.lcyk.keer.ui.component.SyncAlertState
import site.lcyk.keer.ui.component.SyncStatusBadge
import site.lcyk.keer.ui.component.rememberAuthorizedImageLoader
import site.lcyk.keer.ui.component.rememberListRenderSchedulerState
import site.lcyk.keer.ui.component.rememberMemoExtremeListState
import site.lcyk.keer.ui.component.rememberThumbnailListImageLoader
import site.lcyk.keer.ui.page.common.LocalRootNavController
import site.lcyk.keer.ui.page.memoinput.QuickMemoComposer
import site.lcyk.keer.ui.page.common.navigateToGroupInputPage
import site.lcyk.keer.ui.page.common.navigateToMemoDetailPage
import site.lcyk.keer.ui.page.common.navigateToSearchPage
import site.lcyk.keer.ui.page.common.navigateToTagPage
import site.lcyk.keer.viewmodel.GroupChatCardUiModel
import site.lcyk.keer.viewmodel.GroupChatViewModel
import site.lcyk.keer.viewmodel.LocalMemos
import site.lcyk.keer.viewmodel.LocalUserState
import site.lcyk.keer.viewmodel.MemoUiScope
import site.lcyk.keer.viewmodel.UiHydrationState
import site.lcyk.keer.viewmodel.UiInteractionType
import site.lcyk.keer.viewmodel.patchByKey

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupChatPage(
    drawerState: DrawerState? = null,
    navController: NavHostController,
    groupId: String,
    onMenuButtonOpenRequested: (() -> Unit)? = null,
    viewModel: GroupChatViewModel = hiltViewModel()
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val rootNavController = LocalRootNavController.current
    val scope = rememberCoroutineScope()
    val memosViewModel = LocalMemos.current
    val userStateViewModel = LocalUserState.current
    val currentAccount by userStateViewModel.currentAccount.collectAsStateWithLifecycle()
    val generalSettings by userStateViewModel.generalSettings.collectAsStateWithLifecycle()
    val joinedGroups by userStateViewModel.joinedGroups.collectAsStateWithLifecycle()
    val groupIdAliases by userStateViewModel.groupIdAliases.collectAsStateWithLifecycle()
    val collaboratorProfiles by userStateViewModel.collaboratorProfiles.collectAsStateWithLifecycle()
    val groupFrozen by memosViewModel.observeScopeFrozen(MemoUiScope.GROUP_CHAT)
        .collectAsStateWithLifecycle(initialValue = false)
    val avatarImageLoader = rememberAuthorizedImageLoader()
    val mediaImageLoader = rememberThumbnailListImageLoader()

    val resolvedGroupId = groupIdAliases
        .firstOrNull { alias -> alias.localId == groupId }
        ?.remoteId
        ?: groupId
    val editGesture = generalSettings.memoEditGesture
    val group = joinedGroups.firstOrNull { it.id == resolvedGroupId }

    val cardListState by viewModel.visibleCardListState.collectAsStateWithLifecycle()
    val memos = remember { mutableStateListOf<GroupChatCardUiModel>() }
    val hydrationState by viewModel.hydrationState.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()
    val groupTags by viewModel.groupTags.collectAsStateWithLifecycle()
    val listState = rememberMemoExtremeListState()
    val renderSchedulerState = rememberListRenderSchedulerState(
        scopeFrozen = groupFrozen,
        isScrollInProgressProvider = { listState.isScrollInProgress },
    )
    val prefetchPaused = renderSchedulerState.prefetchPaused
    val warmupEnabled = renderSchedulerState.warmupEnabled
    val effectiveGroupFrozen = renderSchedulerState.uiFrozen
    val refreshState = rememberPullToRefreshState()
    val expandedFab by remember {
        derivedStateOf { listState.firstVisibleItemIndex == 0 }
    }

    var syncAlert by remember { mutableStateOf<SyncAlertState?>(null) }
    var showQuickComposer by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(cardListState.cards) {
        memos.patchByKey(cardListState.cards) { card -> card.source.remoteId }
    }

    val navigationIcon = if (drawerState != null) Icons.Filled.Menu else Icons.AutoMirrored.Filled.ArrowBack
    val navigationContentDescription = if (drawerState != null) R.string.menu.string else R.string.back.string

    suspend fun reloadGroup(forceSync: Boolean = false) {
        val resolvedGroup = group ?: return
        viewModel.loadGroupMemos(resolvedGroup.id, forceSync = forceSync)
        if (resolvedGroup.hasUnreadMessages) {
            val markReadSucceeded = viewModel.markGroupRead(resolvedGroup.id)
            if (!markReadSucceeded) {
                return
            }
        }
    }

    suspend fun requestManualSync() {
        reloadGroup(forceSync = true)
    }

    fun handleNavigationClick() {
        if (drawerState != null) {
            onMenuButtonOpenRequested?.invoke()
            scope.launch { drawerState.open() }
        } else {
            navController.popBackStackIfLifecycleIsResumed(lifecycleOwner)
        }
    }

    LaunchedEffect(group?.id) {
        reloadGroup(forceSync = false)
    }

    LaunchedEffect(group?.id) {
        val resolvedGroup = group ?: return@LaunchedEffect
        viewModel.loadGroupTags(resolvedGroup.id, forceSync = false)
    }
    LaunchedEffect(cardListState.collaboratorIdsToPrefetch) {
        if (cardListState.collaboratorIdsToPrefetch.isNotEmpty()) {
            userStateViewModel.prefetchCollaboratorAvatars(cardListState.collaboratorIdsToPrefetch)
        }
    }

    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .distinctUntilChanged()
            .collect { isScrolling ->
                memosViewModel.setInteractionActive(MemoUiScope.GROUP_CHAT, UiInteractionType.LIST_SCROLL, isScrolling)
            }
    }

    LaunchedEffect(refreshState) {
        snapshotFlow { refreshState.distanceFraction > 0f }
            .distinctUntilChanged()
            .collect { pullActive ->
                memosViewModel.setInteractionActive(MemoUiScope.GROUP_CHAT, UiInteractionType.PULL_REFRESH, pullActive)
            }
    }

    DisposableEffect(Unit) {
        onDispose {
            memosViewModel.setInteractionActive(MemoUiScope.GROUP_CHAT, UiInteractionType.LIST_SCROLL, false)
            memosViewModel.setInteractionActive(MemoUiScope.GROUP_CHAT, UiInteractionType.PULL_REFRESH, false)
        }
    }

    MediaPreviewPrefetchEffect(
        listState = listState,
        memos = cardListState.prefetchMemos,
        prefetchPaused = prefetchPaused,
        currentAccountKey = currentAccount?.accountKey(),
        okHttpClient = userStateViewModel.okHttpClient,
        cacheResourceFile = { identifier, downloadedUri ->
            memosViewModel.cacheResourceFile(identifier, downloadedUri)
        },
        cacheResourceThumbnail = { identifier, downloadedUri ->
            memosViewModel.cacheResourceThumbnail(identifier, downloadedUri)
        },
        updateResourceThumbnail = { identifier, localThumbnailUri ->
            memosViewModel.updateResourceThumbnail(identifier, localThumbnailUri)
        },
    )
    MemoPreviewWarmupEffect(
        memos = cardListState.prefetchMemos,
        enabled = warmupEnabled,
    )

    LaunchedEffect(group?.id) {
        var wasRunning = false
        memosViewModel.syncStatus
            .map { status -> status.syncing }
            .distinctUntilChanged()
            .collect { nowSyncing ->
                if (group != null && wasRunning && !nowSyncing) {
                    reloadGroup(forceSync = false)
                }
                wasRunning = nowSyncing
            }
        }
    

    LaunchedEffect(errorMessage) {
        val message = errorMessage ?: return@LaunchedEffect
        syncAlert = SyncAlertState.Failed(message)
        viewModel.clearErrorMessage()
    }

    if (group == null) {
        Scaffold(
            topBar = {
                GroupChatTopBar(
                    title = R.string.group_not_found.string,
                    navigationIcon = navigationIcon,
                    navigationContentDescription = navigationContentDescription,
                    onNavigationClick = ::handleNavigationClick,
                    showSyncBadge = false,
                    loading = false,
                    onManualSync = {},
                    onSearch = null
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

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                GroupChatTopBar(
                    title = group.name,
                    navigationIcon = navigationIcon,
                    navigationContentDescription = navigationContentDescription,
                    onNavigationClick = ::handleNavigationClick,
                    showSyncBadge = currentAccount !is Account.Local,
                    loading = loading,
                    onManualSync = {
                        scope.launch {
                            requestManualSync()
                        }
                    },
                    onSearch = {
                        navController.navigateToSearchPage()
                    }
                )
            },
            floatingActionButton = {
                if (!showQuickComposer) {
                    GroupChatFab(
                        expanded = expandedFab,
                        onClick = { showQuickComposer = true }
                    )
                }
            }
        ) { innerPadding ->
            GroupChatList(
                memoCards = memos,
                loading = loading,
                hydrationState = hydrationState,
                editGesture = editGesture,
                listState = listState,
                refreshState = refreshState,
                contentPadding = innerPadding,
                collaboratorProfiles = collaboratorProfiles,
                avatarImageLoader = avatarImageLoader,
                mediaImageLoader = mediaImageLoader,
                uiFrozen = effectiveGroupFrozen,
                onRefresh = {
                    if (memosViewModel.syncStatus.value.syncing) {
                        return@GroupChatList
                    }
                    scope.launch {
                        requestManualSync()
                    }
                },
                onOpenMemoDetail = { selectedMemo ->
                    memosViewModel.cacheMemoForDetail(selectedMemo)
                    rootNavController.navigateToMemoDetailPage(selectedMemo.identifier)
                },
                onTogglePinned = { memo, pinned ->
                    scope.launch {
                        viewModel.setGroupMemoPinned(
                            groupId = group.id,
                            memoRemoteId = memo.remoteId,
                            pinned = pinned
                        )
                    }
                },
                onEditMemo = { memo ->
                    navController.navigateToGroupInputPage(
                        groupId = group.id,
                        memoId = memo.remoteId
                    )
                },
                onDeleteMemo = { memo ->
                    scope.launch {
                        viewModel.deleteGroupMemo(
                            groupId = group.id,
                            memoRemoteId = memo.remoteId
                        )
                    }
                },
                onRequestQuote = { source ->
                    memosViewModel.cacheMemoForDetail(source)
                    navController.navigateToGroupInputPage(
                        groupId = group.id,
                        quoteMemoIdentifier = source.identifier
                    )
                },
                onOpenTopic = { _, _ -> },
                onTagClick = { tag ->
                    navController.navigateToTagPage(tag)
                }
            )
        }

        QuickMemoComposer(
            visible = showQuickComposer,
            onDismissRequest = { showQuickComposer = false },
            availableTags = groupTags,
            enableLocation = false,
            persistDraft = false,
            onSubmitRequest = { request ->
                viewModel.submitQuickComposerMemo(
                    groupId = group.id,
                    request = request,
                    existingTags = groupTags,
                )
            },
        )
    }

    SyncAlertDialog(
        alert = syncAlert,
        onDismiss = {
            syncAlert = null
            viewModel.clearErrorMessage()
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GroupChatTopBar(
    title: String,
    navigationIcon: androidx.compose.ui.graphics.vector.ImageVector,
    navigationContentDescription: String,
    onNavigationClick: () -> Unit,
    showSyncBadge: Boolean,
    loading: Boolean,
    onManualSync: () -> Unit,
    onSearch: (() -> Unit)?
) {
    TopAppBar(
        title = { Text(title) },
        navigationIcon = {
            IconButton(onClick = onNavigationClick) {
                Icon(
                    imageVector = navigationIcon,
                    contentDescription = navigationContentDescription
                )
            }
        },
        actions = {
            if (showSyncBadge) {
                GroupChatSyncBadgeAction(
                    loading = loading,
                    onSync = onManualSync
                )
            }
            if (onSearch != null) {
                IconButton(onClick = onSearch) {
                    Icon(Icons.Filled.Search, contentDescription = R.string.search.string)
                }
            }
        }
    )
}

@Composable
private fun GroupChatSyncBadgeAction(
    loading: Boolean,
    onSync: () -> Unit,
) {
    val memosViewModel = LocalMemos.current
    val syncingFlow = remember(memosViewModel) {
        memosViewModel.syncStatus
            .map { status -> status.syncing }
            .distinctUntilChanged()
    }
    val unsyncedCountFlow = remember(memosViewModel) {
        memosViewModel.syncStatus
            .map { status -> status.unsyncedCount }
            .distinctUntilChanged()
    }
    val progressFlow = remember(memosViewModel) {
        memosViewModel.syncStatus
            .map { status -> status.progress }
            .distinctUntilChanged()
    }
    val syncing by syncingFlow.collectAsStateWithLifecycle(initialValue = false)
    val unsyncedCount by unsyncedCountFlow.collectAsStateWithLifecycle(initialValue = 0)
    val progress by progressFlow.collectAsStateWithLifecycle(initialValue = null)

    if (!syncing && !loading) {
        return
    }
    SyncStatusBadge(
        syncing = syncing,
        unsyncedCount = unsyncedCount,
        progress = progress,
        onSync = onSync,
    )
}

@Composable
private fun GroupChatFab(
    expanded: Boolean,
    onClick: () -> Unit
) {
    ExtendedFloatingActionButton(
        onClick = onClick,
        expanded = expanded,
        text = { Text(R.string.new_memo.string) },
        icon = { Icon(Icons.Filled.Add, contentDescription = R.string.compose.string) }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GroupChatList(
    memoCards: List<GroupChatCardUiModel>,
    editGesture: MemoEditGesture,
    listState: LazyListState,
    refreshState: PullToRefreshState,
    loading: Boolean,
    hydrationState: UiHydrationState,
    contentPadding: PaddingValues,
    collaboratorProfiles: Map<String, site.lcyk.keer.data.model.CollaboratorProfile>,
    avatarImageLoader: coil3.ImageLoader,
    mediaImageLoader: coil3.ImageLoader,
    uiFrozen: Boolean,
    onRefresh: () -> Unit,
    onOpenMemoDetail: (MemoEntity) -> Unit,
    onTogglePinned: (Memo, Boolean) -> Unit,
    onEditMemo: (Memo) -> Unit,
    onDeleteMemo: (Memo) -> Unit,
    onRequestQuote: (MemoEntity) -> Unit,
    onOpenTopic: (String, String) -> Unit,
    onTagClick: (String) -> Unit
) {
    RefreshableListContainer(
        isRefreshing = false,
        pullRefreshActive = false,
        onRefresh = onRefresh,
        state = refreshState,
        indicator = {
            GroupPullSyncIndicator(
                refreshState = refreshState,
                loading = loading,
            )
            SurfaceHydrationLineOverlay(
                hydrationState = hydrationState,
                topPadding = 18.dp,
            )
        },
        modifier = Modifier.padding(contentPadding),
        isEmpty = memoCards.isEmpty() && !loading && !hydrationState.isHydrating,
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
                items = memoCards,
                key = { it.source.remoteId },
                contentType = { "memo" }
            ) { card ->
                MemosCard(
                    memo = card.memo,
                    onClick = onOpenMemoDetail,
                    editGesture = editGesture,
                    previewMode = true,
                    autoPreviewPrefetch = false,
                    showSyncStatus = true,
                    authorAvatarUrl = card.authorAvatarUrl,
                    authorName = card.authorName,
                    onRequestEdit = { onEditMemo(card.source) },
                    actionButton = { memoEntity ->
                        GroupMemoCardActionButton(
                            memo = memoEntity,
                            pinned = memoEntity.pinned,
                            canManage = card.canManage,
                            onTogglePinned = {
                                onTogglePinned(card.source, !memoEntity.pinned)
                            },
                            onQuote = { onRequestQuote(card.memo) },
                            onEdit = { onEditMemo(card.source) },
                            onDelete = { onDeleteMemo(card.source) }
                        )
                    },
                    onTagClick = onTagClick,
                    collaboratorProfiles = collaboratorProfiles,
                    avatarImageLoader = avatarImageLoader,
                    mediaImageLoader = mediaImageLoader,
                    uiFrozen = uiFrozen,
                    prefetchCollaborators = false,
                    resolvedQuote = card.resolvedQuote,
                    displayTags = card.displayTags,
                    collaboratorIds = card.collaboratorIds,
                )
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.GroupPullSyncIndicator(
    refreshState: PullToRefreshState,
    loading: Boolean,
) {
    val memosViewModel = LocalMemos.current
    val syncingFlow = remember(memosViewModel) {
        memosViewModel.syncStatus
            .map { status -> status.syncing }
            .distinctUntilChanged()
    }
    val syncing by syncingFlow.collectAsStateWithLifecycle(initialValue = false)
    PullSyncLineIndicator(
        refreshState = refreshState,
        syncing = syncing || loading,
    )
}

@Composable
private fun GroupMemoCardActionButton(
    memo: MemoEntity,
    pinned: Boolean,
    canManage: Boolean,
    onTogglePinned: () -> Unit,
    onQuote: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val actions = buildList {
        add(
            MemoMenuAction(
                key = "quote",
                label = R.string.quote.string,
                icon = Icons.Outlined.RecordVoiceOver,
                onSelected = onQuote
            )
        )
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
