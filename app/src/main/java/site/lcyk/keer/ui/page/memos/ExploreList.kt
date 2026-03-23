package site.lcyk.keer.ui.page.memos

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import site.lcyk.keer.R
import site.lcyk.keer.data.local.entity.MemoEntity
import site.lcyk.keer.data.model.Account
import site.lcyk.keer.data.model.Memo
import site.lcyk.keer.data.model.MemoEditGesture
import site.lcyk.keer.ui.component.MemoActionMenuButton
import site.lcyk.keer.ui.component.MemoMenuAction
import site.lcyk.keer.ui.component.MemosCard
import site.lcyk.keer.ui.component.MediaPreviewPrefetchEffect
import site.lcyk.keer.ui.component.PullSyncLineIndicator
import site.lcyk.keer.ui.component.RefreshableListContainer
import site.lcyk.keer.ui.component.SyncAlertDialog
import site.lcyk.keer.ui.component.SyncAlertState
import site.lcyk.keer.ui.component.processManualSyncResult
import site.lcyk.keer.ui.component.rememberAuthorizedImageLoader
import site.lcyk.keer.ui.component.rememberMemoExtremeListState
import site.lcyk.keer.ui.component.rememberMemoMediaImageLoader
import site.lcyk.keer.ui.page.common.LocalRootNavController
import site.lcyk.keer.ui.page.common.navigateToGroupInputPage
import site.lcyk.keer.ui.page.common.navigateToMemoDetailPage
import site.lcyk.keer.ui.page.common.navigateToMemoInputPage
import site.lcyk.keer.ui.page.common.navigateToTagPage
import site.lcyk.keer.util.extractCollaboratorIds
import site.lcyk.keer.util.normalizeCollaboratorId
import site.lcyk.keer.util.toMemoEntityForCard
import site.lcyk.keer.viewmodel.ExploreMemoItem
import site.lcyk.keer.viewmodel.ExploreViewModel
import site.lcyk.keer.viewmodel.LocalMemos
import site.lcyk.keer.viewmodel.LocalUserState
import site.lcyk.keer.viewmodel.MemoUiScope
import site.lcyk.keer.viewmodel.UiInteractionType

@Composable
fun ExploreList(
    viewModel: ExploreViewModel = hiltViewModel(),
    contentPadding: PaddingValues,
) {
    val items by viewModel.visibleItems.collectAsStateWithLifecycle()
    val resolvedQuoteMap by viewModel.visibleResolvedQuotes.collectAsStateWithLifecycle()
    val memosViewModel = LocalMemos.current
    val userStateViewModel = LocalUserState.current
    val currentAccount by userStateViewModel.currentAccount.collectAsStateWithLifecycle()
    val collaboratorProfiles by userStateViewModel.collaboratorProfiles.collectAsStateWithLifecycle()
    val exploreFrozen by memosViewModel.observeScopeFrozen(MemoUiScope.EXPLORE)
        .collectAsStateWithLifecycle(initialValue = false)
    val mutationErrorMessage by viewModel.mutationErrorMessage.collectAsStateWithLifecycle()
    val rootNavController = LocalRootNavController.current
    val listState = rememberMemoExtremeListState()
    val refreshState = rememberPullToRefreshState()
    val scope = rememberCoroutineScope()
    val avatarImageLoader = rememberAuthorizedImageLoader()
    val mediaImageLoader = rememberMemoMediaImageLoader()
    val effectiveExploreFrozen = exploreFrozen || listState.isScrollInProgress
    var syncAlert by remember { mutableStateOf<SyncAlertState?>(null) }
    var editingMemo by remember { mutableStateOf<ExploreMemoItem?>(null) }
    var editingContent by remember { mutableStateOf("") }
    var deletingMemo by remember { mutableStateOf<ExploreMemoItem?>(null) }
    val accountKey = currentAccount?.accountKey() ?: "explore"
    val prefetchMemoEntities = remember(items, accountKey) {
        items.map { item -> item.memo.toExploreMemoEntity(accountKey) }
    }
    val collaboratorIdsToPrefetch = remember(items) {
        items
            .asSequence()
            .flatMap { item -> extractCollaboratorIds(item.memo.tags).asSequence() }
            .distinct()
            .toList()
    }
    val currentUserId = when (val account = currentAccount) {
        is Account.KeerV2 -> account.info.id.toString()
        is Account.Local -> "local"
        null -> ""
    }

    LaunchedEffect(collaboratorIdsToPrefetch) {
        if (collaboratorIdsToPrefetch.isNotEmpty()) {
            userStateViewModel.prefetchCollaboratorAvatars(collaboratorIdsToPrefetch)
        }
    }

    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .distinctUntilChanged()
            .collect { isScrolling ->
                memosViewModel.setInteractionActive(MemoUiScope.EXPLORE, UiInteractionType.LIST_SCROLL, isScrolling)
            }
    }

    LaunchedEffect(refreshState) {
        snapshotFlow { refreshState.distanceFraction > 0f }
            .distinctUntilChanged()
            .collect { pullActive ->
                memosViewModel.setInteractionActive(MemoUiScope.EXPLORE, UiInteractionType.PULL_REFRESH, pullActive)
            }
    }

    DisposableEffect(Unit) {
        onDispose {
            memosViewModel.setInteractionActive(MemoUiScope.EXPLORE, UiInteractionType.LIST_SCROLL, false)
            memosViewModel.setInteractionActive(MemoUiScope.EXPLORE, UiInteractionType.PULL_REFRESH, false)
        }
    }

    MediaPreviewPrefetchEffect(
        listState = listState,
        memos = prefetchMemoEntities,
        frozen = effectiveExploreFrozen,
        currentAccountKey = currentAccount?.accountKey(),
        okHttpClient = userStateViewModel.okHttpClient,
        cacheResourceFile = { identifier, downloadedUri ->
            memosViewModel.cacheResourceFile(identifier, downloadedUri)
        },
        cacheResourceThumbnail = { identifier, downloadedUri ->
            memosViewModel.cacheResourceThumbnail(identifier, downloadedUri)
        },
    )

    ExploreMemoFeed(
        memos = items,
        listState = listState,
        refreshState = refreshState,
        contentPadding = contentPadding,
        collaboratorProfiles = collaboratorProfiles,
        avatarImageLoader = avatarImageLoader,
        mediaImageLoader = mediaImageLoader,
        uiFrozen = effectiveExploreFrozen,
        accountKey = accountKey,
        resolvedQuoteMap = resolvedQuoteMap,
        currentUserId = currentUserId,
        onRefresh = {
            if (memosViewModel.syncStatus.value.syncing) {
                return@ExploreMemoFeed
            }
            scope.launch {
                processManualSyncResult(memosViewModel.refreshExploreFeed()) { alert ->
                    syncAlert = alert
                }
            }
        },
        onOpenMemoDetail = { selectedMemo ->
            memosViewModel.cacheMemoForDetail(selectedMemo)
            rootNavController.navigateToMemoDetailPage(selectedMemo.identifier)
        },
        onTagClick = { tag ->
            rootNavController.navigateToTagPage(tag)
        },
        onRequestEdit = { item ->
            val groupId = item.groupId
            if (!groupId.isNullOrBlank()) {
                rootNavController.navigateToGroupInputPage(
                    groupId = groupId,
                    memoId = item.memo.remoteId,
                )
            } else {
                editingMemo = item
                editingContent = item.memo.content
            }
        },
        onRequestDelete = { item ->
            deletingMemo = item
        },
        onRequestQuote = { item ->
            val sourceMemo = item.memo.toExploreMemoEntity(accountKey)
            memosViewModel.cacheMemoForDetail(sourceMemo)
            rootNavController.navigateToMemoInputPage(quoteMemoIdentifier = sourceMemo.identifier)
        },
    )

    if (editingMemo != null) {
        AlertDialog(
            onDismissRequest = { editingMemo = null },
            title = { Text(text = stringResource(R.string.edit)) },
            text = {
                OutlinedTextField(
                    value = editingContent,
                    onValueChange = { editingContent = it },
                    label = { Text(text = stringResource(R.string.memo)) },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val target = editingMemo ?: return@TextButton
                        scope.launch {
                            val updated = viewModel.updateExploreMemo(
                                item = target,
                                content = editingContent,
                                tags = target.memo.tags,
                            )
                            if (updated) {
                                editingMemo = null
                            }
                        }
                    }
                ) {
                    Text(text = stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { editingMemo = null }) {
                    Text(text = stringResource(R.string.cancel))
                }
            },
        )
    }

    if (deletingMemo != null) {
        AlertDialog(
            onDismissRequest = { deletingMemo = null },
            title = { Text(text = stringResource(R.string.delete_this_memo)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        val target = deletingMemo ?: return@TextButton
                        scope.launch {
                            val deleted = viewModel.deleteExploreMemo(target)
                            if (deleted) {
                                deletingMemo = null
                            }
                        }
                    }
                ) {
                    Text(text = stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { deletingMemo = null }) {
                    Text(text = stringResource(R.string.cancel))
                }
            },
        )
    }

    if (!mutationErrorMessage.isNullOrBlank()) {
        AlertDialog(
            onDismissRequest = { viewModel.clearMutationError() },
            title = { Text(text = stringResource(R.string.sync_failed)) },
            text = { Text(text = mutationErrorMessage.orEmpty()) },
            confirmButton = {
                TextButton(onClick = { viewModel.clearMutationError() }) {
                    Text(text = stringResource(R.string.close))
                }
            },
        )
    }

    SyncAlertDialog(
        alert = syncAlert,
        onDismiss = { syncAlert = null },
    )
}

@Composable
private fun ExploreMemoFeed(
    memos: List<ExploreMemoItem>,
    listState: androidx.compose.foundation.lazy.LazyListState,
    refreshState: androidx.compose.material3.pulltorefresh.PullToRefreshState,
    contentPadding: PaddingValues,
    collaboratorProfiles: Map<String, site.lcyk.keer.data.model.CollaboratorProfile>,
    avatarImageLoader: coil3.ImageLoader,
    mediaImageLoader: coil3.ImageLoader,
    uiFrozen: Boolean,
    accountKey: String,
    resolvedQuoteMap: Map<String, site.lcyk.keer.util.ResolvedMemoQuote>,
    currentUserId: String,
    onRefresh: () -> Unit,
    onOpenMemoDetail: (MemoEntity) -> Unit,
    onTagClick: (String) -> Unit,
    onRequestEdit: (ExploreMemoItem) -> Unit,
    onRequestDelete: (ExploreMemoItem) -> Unit,
    onRequestQuote: (ExploreMemoItem) -> Unit,
) {
    RefreshableListContainer(
        isRefreshing = false,
        pullRefreshActive = false,
        onRefresh = onRefresh,
        state = refreshState,
        indicator = {
            ExplorePullSyncIndicator(refreshState = refreshState)
        },
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyColumn(
            state = listState,
            contentPadding = contentPadding,
        ) {
            items(
                items = memos,
                key = { item -> "${item.groupId.orEmpty()}|${item.memo.remoteId}" },
                contentType = { "memo" },
            ) { memoItem ->
                val adaptedMemo = remember(memoItem.memo, accountKey) {
                    memoItem.memo.toExploreMemoEntity(accountKey)
                }
                val canManageMemo = remember(memoItem.memo, currentUserId) {
                    canManageExploreMemo(memoItem.memo, currentUserId)
                }
                MemosCard(
                    memo = adaptedMemo,
                    onClick = onOpenMemoDetail,
                    editGesture = MemoEditGesture.NONE,
                    previewMode = true,
                    autoPreviewPrefetch = false,
                    showSyncStatus = false,
                    authorAvatarUrl = memoItem.memo.creator?.avatarUrl,
                    authorName = memoItem.memo.creator?.name,
                    onRequestEdit = { onRequestEdit(memoItem) },
                    actionButton = { memoEntity ->
                        ExploreMemoCardActionButton(
                            memo = memoEntity,
                            canManage = canManageMemo,
                            onQuote = { onRequestQuote(memoItem) },
                            onEdit = { onRequestEdit(memoItem) },
                            onDelete = { onRequestDelete(memoItem) },
                        )
                    },
                    onTagClick = onTagClick,
                    collaboratorProfiles = collaboratorProfiles,
                    avatarImageLoader = avatarImageLoader,
                    mediaImageLoader = mediaImageLoader,
                    uiFrozen = uiFrozen,
                    prefetchCollaborators = false,
                    resolvedQuote = resolvedQuoteMap[adaptedMemo.identifier],
                )
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.ExplorePullSyncIndicator(
    refreshState: androidx.compose.material3.pulltorefresh.PullToRefreshState
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
        syncing = syncing,
    )
}

private fun Memo.toExploreMemoEntity(accountKey: String): MemoEntity {
    return toMemoEntityForCard(
        identifier = "explore:$remoteId",
        accountKey = accountKey,
    )
}

@Composable
private fun ExploreMemoCardActionButton(
    memo: MemoEntity,
    canManage: Boolean,
    onQuote: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    val clipboardManager = context.getSystemService(ClipboardManager::class.java)
    val memoLabel = stringResource(R.string.memo)
    val actions = buildList {
        if (canManage) {
            add(
                MemoMenuAction(
                    key = "edit",
                    label = stringResource(R.string.edit),
                    icon = Icons.Outlined.Edit,
                    onSelected = onEdit,
                )
            )
        }
        add(
            MemoMenuAction(
                key = "quote",
                label = stringResource(R.string.quote),
                icon = Icons.Outlined.RecordVoiceOver,
                onSelected = onQuote,
            )
        )
        add(
            MemoMenuAction(
                key = "copy",
                label = stringResource(R.string.copy),
                icon = Icons.Outlined.ContentCopy,
                onSelected = {
                    clipboardManager?.setPrimaryClip(
                        ClipData.newPlainText(memoLabel, memo.content),
                    )
                },
            )
        )
        if (canManage) {
            add(
                MemoMenuAction(
                    key = "delete",
                    label = stringResource(R.string.delete),
                    icon = Icons.Outlined.Delete,
                    destructive = true,
                    onSelected = onDelete,
                )
            )
        }
    }
    MemoActionMenuButton(actions = actions)
}

private fun canManageExploreMemo(memo: Memo, currentUserId: String): Boolean {
    val normalizedCurrentUserID = normalizeCollaboratorId(currentUserId)
    if (normalizedCurrentUserID.isEmpty()) {
        return false
    }
    val creatorID = normalizeCollaboratorId(memo.creator?.identifier.orEmpty())
    if (creatorID == normalizedCurrentUserID) {
        return true
    }
    return extractCollaboratorIds(memo.tags).any { collaboratorID ->
        normalizeCollaboratorId(collaboratorID) == normalizedCurrentUserID
    }
}
