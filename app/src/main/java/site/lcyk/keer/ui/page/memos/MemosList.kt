package site.lcyk.keer.ui.page.memos

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import site.lcyk.keer.R
import site.lcyk.keer.data.model.Account
import site.lcyk.keer.data.model.MemoEditGesture
import site.lcyk.keer.data.model.Settings
import site.lcyk.keer.ext.settingsDataStore
import site.lcyk.keer.ext.string
import site.lcyk.keer.ui.component.RefreshableListContainer
import site.lcyk.keer.ui.component.SyncAlertDialog
import site.lcyk.keer.ui.component.SyncAlertState
import site.lcyk.keer.ui.component.PullSyncLineIndicator
import site.lcyk.keer.ui.component.processManualSyncResult
import site.lcyk.keer.ui.component.rememberListEdgeHaptics
import site.lcyk.keer.ui.component.MemosCard
import site.lcyk.keer.ui.component.rememberAuthorizedImageLoader
import site.lcyk.keer.ui.page.common.LocalRootNavController
import site.lcyk.keer.ui.page.common.navigateToMemoDetailPage
import site.lcyk.keer.util.extractCollaboratorIds
import site.lcyk.keer.viewmodel.LocalMemos
import site.lcyk.keer.viewmodel.LocalUserState
import timber.log.Timber

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemosList(
    contentPadding: PaddingValues,
    lazyListState: LazyListState = rememberLazyListState(),
    tag: String? = null,
    searchString: String? = null,
    onRefresh: (suspend () -> Unit)? = null,
    onTagClick: ((String) -> Unit)? = null,
) {
    val context = LocalContext.current
    val navController = LocalRootNavController.current
    val viewModel = LocalMemos.current
    val userStateViewModel = LocalUserState.current
    val currentAccount by userStateViewModel.currentAccount.collectAsStateWithLifecycle()
    val collaboratorProfiles by userStateViewModel.collaboratorProfiles.collectAsStateWithLifecycle()
    val syncStatus by viewModel.syncStatus.collectAsStateWithLifecycle()
    val settings by context.settingsDataStore.data.collectAsState(initial = Settings())
    val editGesture = settings.usersList
        .firstOrNull { it.accountKey == settings.currentUser }
        ?.settings
        ?.editGesture
    val refreshState = rememberPullToRefreshState()
    val scope = rememberCoroutineScope()
    val hapticFeedback = LocalHapticFeedback.current
    val avatarImageLoader = rememberAuthorizedImageLoader()
    var syncAlert by remember { mutableStateOf<SyncAlertState?>(null) }
    val filteredMemos by remember(viewModel.memos, tag, searchString) {
        derivedStateOf {
            val normalizedTag = tag?.takeIf { it.isNotBlank() }
            val normalizedQuery = searchString?.takeIf { it.isNotBlank() }
            val pinned = mutableListOf<site.lcyk.keer.data.local.entity.MemoEntity>()
            val normal = mutableListOf<site.lcyk.keer.data.local.entity.MemoEntity>()

            for (memo in viewModel.memos) {
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
    val atTop = !lazyListState.canScrollBackward
    val atBottom = filteredMemos.isNotEmpty() && !lazyListState.canScrollForward

    rememberListEdgeHaptics(
        itemCount = filteredMemos.size,
        atTop = atTop,
        atBottom = atBottom
    )

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

    var listTopId: String? by rememberSaveable {
        mutableStateOf(null)
    }

    MemoFeedList(
        memos = filteredMemos,
        lazyListState = lazyListState,
        refreshState = refreshState,
        contentPadding = contentPadding,
        syncStatus = syncStatus,
        hapticFeedback = hapticFeedback,
        showSyncStatus = currentAccount !is Account.Local,
        editGesture = editGesture ?: MemoEditGesture.NONE,
        collaboratorProfiles = collaboratorProfiles,
        avatarImageLoader = avatarImageLoader,
        quoteResolverSettings = settings,
        onRefresh = {
            if (syncStatus.syncing) {
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
        onTagClick = onTagClick
    )

    LaunchedEffect(viewModel.errorMessage) {
        viewModel.errorMessage?.let {
            Timber.d(it)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.loadMemos()
    }

    LaunchedEffect(filteredMemos.firstOrNull()?.identifier) {
        if (listTopId != null && filteredMemos.isNotEmpty() && listTopId != filteredMemos.first().identifier) {
            lazyListState.scrollToItem(0)
        }

        listTopId = filteredMemos.firstOrNull()?.identifier
    }

    SyncAlertDialog(
        alert = syncAlert,
        onDismiss = { syncAlert = null }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MemoFeedList(
    memos: List<site.lcyk.keer.data.local.entity.MemoEntity>,
    lazyListState: LazyListState,
    refreshState: androidx.compose.material3.pulltorefresh.PullToRefreshState,
    contentPadding: PaddingValues,
    syncStatus: site.lcyk.keer.data.model.SyncStatus,
    hapticFeedback: androidx.compose.ui.hapticfeedback.HapticFeedback,
    showSyncStatus: Boolean,
    editGesture: MemoEditGesture,
    collaboratorProfiles: Map<String, site.lcyk.keer.data.model.CollaboratorProfile>,
    avatarImageLoader: coil3.ImageLoader,
    quoteResolverSettings: Settings,
    onRefresh: () -> Unit,
    onOpenMemoDetail: (site.lcyk.keer.data.local.entity.MemoEntity) -> Unit,
    onTagClick: ((String) -> Unit)?
) {
    RefreshableListContainer(
        isRefreshing = syncStatus.syncing,
        onRefresh = onRefresh,
        state = refreshState,
        indicator = {
            PullSyncLineIndicator(
                refreshState = refreshState,
                syncing = syncStatus.syncing,
                hapticFeedback = hapticFeedback
            )
        },
        modifier = Modifier.padding(contentPadding)
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = lazyListState
        ) {
            items(
                items = memos,
                key = { it.identifier },
                contentType = { "memo" }
            ) { memo ->
                MemosCard(
                    memo = memo,
                    onClick = onOpenMemoDetail,
                    editGesture = editGesture,
                    previewMode = true,
                    showSyncStatus = showSyncStatus,
                    onTagClick = onTagClick,
                    collaboratorProfiles = collaboratorProfiles,
                    avatarImageLoader = avatarImageLoader,
                    prefetchCollaborators = false,
                    quoteResolverSettings = quoteResolverSettings
                )
            }
        }
    }
}
