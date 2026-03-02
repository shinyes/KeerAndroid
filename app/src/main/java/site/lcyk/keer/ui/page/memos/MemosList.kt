package site.lcyk.keer.ui.page.memos

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
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
import site.lcyk.keer.ui.component.handleManualSyncResult
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
    val currentAccount by userStateViewModel.currentAccount.collectAsState()
    val collaboratorProfiles by userStateViewModel.collaboratorProfiles.collectAsState()
    val syncStatus by viewModel.syncStatus.collectAsState()
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

    RefreshableListContainer(
        isRefreshing = syncStatus.syncing,
        onRefresh = {
            if (syncStatus.syncing) {
                return@RefreshableListContainer
            }
            scope.launch {
                if (onRefresh != null) {
                    onRefresh()
                } else {
                    handleManualSyncResult(viewModel.refreshMemos())?.let { alert ->
                        syncAlert = alert
                    }
                }
            }
        },
        state = refreshState,
        indicator = {
            val rawPullFraction = refreshState.distanceFraction
            val pullFraction = rawPullFraction.coerceIn(0f, 1f)
            val isPulling = rawPullFraction > 0f
            val readyToRefresh = !syncStatus.syncing && rawPullFraction >= 1f
            var thresholdHapticTriggered by remember { mutableStateOf(false) }

            LaunchedEffect(readyToRefresh, syncStatus.syncing) {
                if (readyToRefresh && !thresholdHapticTriggered) {
                    hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                    thresholdHapticTriggered = true
                } else if (!readyToRefresh) {
                    thresholdHapticTriggered = false
                }
            }

            val targetWidthFraction = when {
                syncStatus.syncing -> 0.36f
                readyToRefresh -> 0.42f
                else -> 0.12f + (0.28f * pullFraction)
            }
            val targetAlpha = when {
                syncStatus.syncing -> 0.9f
                readyToRefresh -> 0.95f
                pullFraction > 0f -> 0.2f + (0.7f * pullFraction)
                else -> 0f
            }
            val widthFraction by animateFloatAsState(
                targetValue = targetWidthFraction,
                animationSpec = tween(
                    durationMillis = if (isPulling) 90 else 260,
                    easing = FastOutSlowInEasing
                ),
                label = "pull_indicator_width"
            )
            val alpha by animateFloatAsState(
                targetValue = targetAlpha,
                animationSpec = tween(
                    durationMillis = if (isPulling || syncStatus.syncing) 90 else 340,
                    easing = FastOutSlowInEasing
                ),
                label = "pull_indicator_alpha"
            )
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 8.dp)
                    .height(3.dp)
                    .fillMaxWidth(widthFraction.coerceIn(0f, 1f))
                    .clip(RoundedCornerShape(999.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = alpha))
            )
        },
        modifier = Modifier.padding(contentPadding)
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = lazyListState
        ) {
            items(filteredMemos, key = { it.identifier }) { memo ->
                MemosCard(
                    memo = memo,
                    onClick = { selectedMemo ->
                        navController.navigateToMemoDetailPage(selectedMemo.identifier)
                    },
                    editGesture = editGesture ?: MemoEditGesture.NONE,
                    previewMode = true,
                    showSyncStatus = currentAccount !is Account.Local,
                    onTagClick = onTagClick,
                    collaboratorProfiles = collaboratorProfiles,
                    avatarImageLoader = avatarImageLoader,
                    prefetchCollaborators = false
                )
            }
        }
    }

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
