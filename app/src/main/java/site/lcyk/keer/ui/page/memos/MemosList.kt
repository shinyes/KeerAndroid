package site.lcyk.keer.ui.page.memos

import android.net.Uri
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import site.lcyk.keer.ui.component.MemosCard
import site.lcyk.keer.ui.page.common.LocalRootNavController
import site.lcyk.keer.ui.page.common.RouteName
import site.lcyk.keer.viewmodel.LocalMemos
import site.lcyk.keer.viewmodel.LocalUserState
import site.lcyk.keer.viewmodel.ManualSyncResult
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
    val syncStatus by viewModel.syncStatus.collectAsState()
    val settings by context.settingsDataStore.data.collectAsState(initial = Settings())
    val editGesture = settings.usersList
        .firstOrNull { it.accountKey == settings.currentUser }
        ?.settings
        ?.editGesture
    val refreshState = rememberPullToRefreshState()
    val scope = rememberCoroutineScope()
    var syncAlert by remember { mutableStateOf<PullRefreshSyncAlert?>(null) }
    val filteredMemos = remember(viewModel.memos.toList(), tag, searchString) {
        val pinned = viewModel.memos.filter { it.pinned }
        val nonPinned = viewModel.memos.filter { !it.pinned }
        var fullList = pinned + nonPinned

        tag?.let { tag ->
            fullList = fullList.filter { memo ->
                memo.content.contains("#$tag") ||
                        memo.content.contains("#$tag/")
            }
        }

        searchString?.let { searchString ->
            if (searchString.isNotEmpty()) {
                fullList = fullList.filter { memo ->
                    memo.content.contains(searchString, true)
                }
            }
        }

        fullList
    }
    var listTopId: String? by rememberSaveable {
        mutableStateOf(null)
    }

    PullToRefreshBox(
        isRefreshing = syncStatus.syncing,
        onRefresh = {
            if (syncStatus.syncing) {
                return@PullToRefreshBox
            }
            scope.launch {
                if (onRefresh != null) {
                    onRefresh()
                } else {
                    when (val result = viewModel.refreshMemos()) {
                        ManualSyncResult.Completed -> Unit
                        is ManualSyncResult.Blocked -> {
                            syncAlert = PullRefreshSyncAlert.Blocked(result.message)
                        }
                        is ManualSyncResult.Failed -> {
                            syncAlert = PullRefreshSyncAlert.Failed(result.message)
                        }
                    }
                }
            }
        },
        state = refreshState,
        indicator = {
            val hapticFeedback = LocalHapticFeedback.current
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
                        navController.navigate(
                            "${RouteName.MEMO_DETAIL}?memoId=${Uri.encode(selectedMemo.identifier)}"
                        )
                    },
                    editGesture = editGesture ?: MemoEditGesture.NONE,
                    previewMode = true,
                    showSyncStatus = currentAccount !is Account.Local,
                    onTagClick = onTagClick
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

    when (val alert = syncAlert) {
        null -> Unit
        is PullRefreshSyncAlert.Blocked -> {
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
        is PullRefreshSyncAlert.Failed -> {
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

private sealed class PullRefreshSyncAlert {
    data class Blocked(val message: String) : PullRefreshSyncAlert()
    data class Failed(val message: String) : PullRefreshSyncAlert()
}
