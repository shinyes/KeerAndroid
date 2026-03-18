package site.lcyk.keer.ui.page.memos

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import site.lcyk.keer.R
import site.lcyk.keer.data.model.Account
import site.lcyk.keer.data.model.MemoEditGesture
import site.lcyk.keer.ext.string
import site.lcyk.keer.ui.component.MemoActionMenuButton
import site.lcyk.keer.ui.component.MemoMenuAction
import site.lcyk.keer.ui.component.MediaPreviewPrefetchEffect
import site.lcyk.keer.ui.component.RefreshableListContainer
import site.lcyk.keer.ui.component.SyncAlertDialog
import site.lcyk.keer.ui.component.SyncAlertState
import site.lcyk.keer.ui.component.PullSyncLineIndicator
import site.lcyk.keer.ui.component.processManualSyncResult
import site.lcyk.keer.ui.component.MemosCard
import site.lcyk.keer.ui.component.MemosCardActionButton
import site.lcyk.keer.ui.component.rememberAuthorizedImageLoader
import site.lcyk.keer.ui.page.common.LocalRootNavController
import site.lcyk.keer.ui.page.common.RouteName
import site.lcyk.keer.ui.page.common.navigateToGroupInputPage
import site.lcyk.keer.ui.page.common.navigateToMemoInputPage
import site.lcyk.keer.ui.page.common.navigateToMemoDetailPage
import site.lcyk.keer.util.extractCollaboratorIds
import site.lcyk.keer.viewmodel.LocalMemos
import site.lcyk.keer.viewmodel.LocalUserState
import site.lcyk.keer.viewmodel.MemoCardUiModel
import site.lcyk.keer.viewmodel.MemoUiScope
import site.lcyk.keer.viewmodel.UiInteractionType
import timber.log.Timber

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemosList(
    contentPadding: PaddingValues,
    lazyListState: LazyListState = rememberLazyListState(),
    memos: List<site.lcyk.keer.data.local.entity.MemoEntity>? = null,
    tag: String? = null,
    searchString: String? = null,
    onRefresh: (suspend () -> Unit)? = null,
    onTagClick: ((String) -> Unit)? = null,
    onRequestEdit: ((site.lcyk.keer.data.local.entity.MemoEntity) -> Unit)? = null,
    editGestureResolver: ((site.lcyk.keer.data.local.entity.MemoEntity, MemoEditGesture) -> MemoEditGesture)? = null,
    actionButton: (@Composable (site.lcyk.keer.data.local.entity.MemoEntity) -> Unit)? = null,
) {
    val navController = LocalRootNavController.current
    val viewModel = LocalMemos.current
    val userStateViewModel = LocalUserState.current
    val currentAccount by userStateViewModel.currentAccount.collectAsStateWithLifecycle()
    val generalSettings by userStateViewModel.generalSettings.collectAsStateWithLifecycle()
    val collaboratorProfiles by userStateViewModel.collaboratorProfiles.collectAsStateWithLifecycle()
    val feedFrozen by viewModel.observeScopeFrozen(MemoUiScope.FEED)
        .collectAsStateWithLifecycle(initialValue = false)
    val visibleMemos by viewModel.visibleMemos.collectAsStateWithLifecycle()
    val visibleResolvedQuotes by viewModel.visibleResolvedQuotes.collectAsStateWithLifecycle()
    val editGesture = generalSettings.memoEditGesture
    val refreshState = rememberPullToRefreshState()
    val scope = rememberCoroutineScope()
    val avatarImageLoader = rememberAuthorizedImageLoader()
    var syncAlert by remember { mutableStateOf<SyncAlertState?>(null) }
    val sourceMemos = memos ?: visibleMemos
    val sourceMemoSnapshot = remember(sourceMemos) { sourceMemos.toList() }
    val resolvedQuoteMap = visibleResolvedQuotes
    val filteredMemos by produceState(
        initialValue = sourceMemoSnapshot,
        sourceMemoSnapshot,
        tag,
        searchString,
    ) {
        value = withContext(Dispatchers.Default) {
            val normalizedTag = tag?.takeIf { it.isNotBlank() }
            val normalizedQuery = searchString?.takeIf { it.isNotBlank() }
            val pinned = mutableListOf<site.lcyk.keer.data.local.entity.MemoEntity>()
            val normal = mutableListOf<site.lcyk.keer.data.local.entity.MemoEntity>()

            for (memo in sourceMemoSnapshot) {
                if (normalizedTag != null) {
                    val matchedTag = memo.tags.any { memoTag ->
                        memoTag == normalizedTag || memoTag.startsWith("$normalizedTag/")
                    }
                    if (!matchedTag) {
                        continue
                    }
                }
                if (normalizedQuery != null && !memo.content.contains(normalizedQuery, ignoreCase = true)) {
                    continue
                }
                if (memo.pinned) {
                    pinned += memo
                } else {
                    normal += memo
                }
            }

            buildList(pinned.size + normal.size) {
                addAll(pinned)
                addAll(normal)
            }
        }
    }
    val memoCardUiModels by produceState(
        initialValue = emptyList<MemoCardUiModel>(),
        filteredMemos,
        resolvedQuoteMap,
    ) {
        value = withContext(Dispatchers.Default) {
            filteredMemos.map { memo ->
                MemoCardUiModel(
                    memo = memo,
                    resolvedQuote = resolvedQuoteMap[memo.identifier],
                )
            }
        }
    }
    val prefetchMemos = remember(memoCardUiModels) {
        memoCardUiModels.map { it.memo }
    }

    val collaboratorIdsToPrefetch = remember(filteredMemos) {
        filteredMemos
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

    LaunchedEffect(lazyListState) {
        snapshotFlow { lazyListState.isScrollInProgress }
            .distinctUntilChanged()
            .collect { isScrolling ->
                viewModel.setInteractionActive(MemoUiScope.FEED, UiInteractionType.LIST_SCROLL, isScrolling)
            }
    }

    DisposableEffect(Unit) {
        onDispose {
            viewModel.setInteractionActive(MemoUiScope.FEED, UiInteractionType.LIST_SCROLL, false)
        }
    }

    MediaPreviewPrefetchEffect(
        listState = lazyListState,
        memos = prefetchMemos,
        frozen = feedFrozen,
        currentAccountKey = currentAccount?.accountKey(),
        okHttpClient = userStateViewModel.okHttpClient,
        cacheResourceFile = { identifier, downloadedUri ->
            viewModel.cacheResourceFile(identifier, downloadedUri)
        },
        cacheResourceThumbnail = { identifier, downloadedUri ->
            viewModel.cacheResourceThumbnail(identifier, downloadedUri)
        },
    )

    MemoFeedList(
        memoCards = memoCardUiModels,
        lazyListState = lazyListState,
        refreshState = refreshState,
        contentPadding = contentPadding,
        showSyncStatus = currentAccount !is Account.Local,
        editGesture = editGesture,
        collaboratorProfiles = collaboratorProfiles,
        avatarImageLoader = avatarImageLoader,
        onRefresh = {
            if (viewModel.syncStatus.value.syncing) {
                return@MemoFeedList
            }
            scope.launch {
                if (onRefresh != null) {
                    onRefresh()
                } else {
                    processManualSyncResult(viewModel.refreshMemos()) { alert ->
                        syncAlert = alert
                    }
                }
            }
        },
        onOpenMemoDetail = { selectedMemo ->
            viewModel.cacheMemoForDetail(selectedMemo)
            navController.navigateToMemoDetailPage(selectedMemo.identifier)
        },
        onTagClick = onTagClick,
        onRequestEdit = onRequestEdit,
        editGestureResolver = editGestureResolver,
        actionButton = actionButton,
    )

    LaunchedEffect(viewModel.errorMessage) {
        viewModel.errorMessage?.let {
            Timber.d(it)
        }
    }
    SyncAlertDialog(
        alert = syncAlert,
        onDismiss = { syncAlert = null }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MemoFeedList(
    memoCards: List<MemoCardUiModel>,
    lazyListState: LazyListState,
    refreshState: androidx.compose.material3.pulltorefresh.PullToRefreshState,
    contentPadding: PaddingValues,
    showSyncStatus: Boolean,
    editGesture: MemoEditGesture,
    collaboratorProfiles: Map<String, site.lcyk.keer.data.model.CollaboratorProfile>,
    avatarImageLoader: coil3.ImageLoader,
    onRefresh: () -> Unit,
    onOpenMemoDetail: (site.lcyk.keer.data.local.entity.MemoEntity) -> Unit,
    onTagClick: ((String) -> Unit)?,
    onRequestEdit: ((site.lcyk.keer.data.local.entity.MemoEntity) -> Unit)?,
    editGestureResolver: ((site.lcyk.keer.data.local.entity.MemoEntity, MemoEditGesture) -> MemoEditGesture)?,
    actionButton: (@Composable (site.lcyk.keer.data.local.entity.MemoEntity) -> Unit)?,
) {
    RefreshableListContainer(
        isRefreshing = false,
        pullRefreshActive = false,
        onRefresh = onRefresh,
        state = refreshState,
        indicator = {
            FeedPullSyncIndicator(refreshState = refreshState)
        },
        modifier = Modifier.fillMaxSize()
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = lazyListState,
            contentPadding = contentPadding
        ) {
            items(
                items = memoCards,
                key = { it.memo.identifier },
                contentType = { "memo" }
            ) { card ->
                val memo = card.memo
                MemosCard(
                    memo = memo,
                    onClick = onOpenMemoDetail,
                    editGesture = editGestureResolver?.invoke(memo, editGesture) ?: editGesture,
                    previewMode = true,
                    autoPreviewPrefetch = false,
                    showSyncStatus = showSyncStatus,
                    onTagClick = onTagClick,
                    actionButton = actionButton,
                    onRequestEdit = onRequestEdit,
                    collaboratorProfiles = collaboratorProfiles,
                    avatarImageLoader = avatarImageLoader,
                    prefetchCollaborators = false,
                    resolvedQuote = card.resolvedQuote,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun androidx.compose.foundation.layout.BoxScope.FeedPullSyncIndicator(
    refreshState: androidx.compose.material3.pulltorefresh.PullToRefreshState
) {
    val viewModel = LocalMemos.current
    val syncing by viewModel.syncStatus
        .map { status -> status.syncing }
        .distinctUntilChanged()
        .collectAsStateWithLifecycle(initialValue = false)
    PullSyncLineIndicator(
        refreshState = refreshState,
        syncing = syncing
    )
}

@Composable
fun HomeGroupMemoActionButton(
    memo: site.lcyk.keer.data.local.entity.MemoEntity,
    groupId: String,
    onRequestEdit: () -> Unit,
) {
    val context = LocalContext.current
    val clipboardManager = context.getSystemService(ClipboardManager::class.java)
    val rootNavController = LocalRootNavController.current
    val memoLabel = stringResource(R.string.memo)
    val actions = buildList {
        add(
            MemoMenuAction(
                key = "edit",
                label = stringResource(R.string.edit),
                icon = Icons.Outlined.Edit,
                onSelected = onRequestEdit
            )
        )
        add(
            MemoMenuAction(
                key = "quote",
                label = stringResource(R.string.quote),
                icon = Icons.Outlined.RecordVoiceOver,
                onSelected = {
                    rootNavController.navigateToGroupInputPage(
                        groupId = groupId,
                        quoteMemoIdentifier = memo.identifier
                    )
                }
            )
        )
        add(
            MemoMenuAction(
                key = "copy",
                label = stringResource(R.string.copy),
                icon = Icons.Outlined.ContentCopy,
                onSelected = {
                    clipboardManager?.setPrimaryClip(
                        ClipData.newPlainText(memoLabel, memo.content)
                    )
                }
            )
        )
    }
    MemoActionMenuButton(actions = actions)
}
