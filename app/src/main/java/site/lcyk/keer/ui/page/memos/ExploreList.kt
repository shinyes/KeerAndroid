package site.lcyk.keer.ui.page.memos

import android.net.Uri
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.paging.compose.collectAsLazyPagingItems
import site.lcyk.keer.data.local.entity.MemoEntity
import site.lcyk.keer.data.local.entity.ResourceEntity
import site.lcyk.keer.data.model.Memo
import site.lcyk.keer.data.model.MemoEditGesture
import site.lcyk.keer.ui.component.MemosCard
import site.lcyk.keer.ui.page.common.LocalRootNavController
import site.lcyk.keer.ui.page.common.RouteName
import site.lcyk.keer.viewmodel.ExploreViewModel
import site.lcyk.keer.viewmodel.LocalUserState

@Composable
fun ExploreList(
    viewModel: ExploreViewModel = hiltViewModel(),
    contentPadding: PaddingValues
) {
    val memos = viewModel.exploreMemos.collectAsLazyPagingItems()
    val userStateViewModel = LocalUserState.current
    val currentAccount by userStateViewModel.currentAccount.collectAsState()
    val rootNavController = LocalRootNavController.current
    val listState = rememberLazyListState()
    val hapticFeedback = LocalHapticFeedback.current
    var topHapticArmed by remember { mutableStateOf(false) }
    var bottomHapticArmed by remember { mutableStateOf(false) }
    val accountKey = currentAccount?.accountKey() ?: "explore"

    val atTop = !listState.canScrollBackward
    val atBottom = memos.itemCount > 0 && !listState.canScrollForward

    LaunchedEffect(memos.itemCount) {
        if (memos.itemCount <= 0) {
            topHapticArmed = false
            bottomHapticArmed = false
            return@LaunchedEffect
        }
        topHapticArmed = !atTop
        bottomHapticArmed = !atBottom
    }

    LaunchedEffect(atTop, atBottom, memos.itemCount) {
        if (memos.itemCount <= 0) return@LaunchedEffect

        var shouldVibrate = false

        if (!atTop) {
            topHapticArmed = true
        } else if (topHapticArmed) {
            shouldVibrate = true
            topHapticArmed = false
        }

        if (!atBottom) {
            bottomHapticArmed = true
        } else if (bottomHapticArmed) {
            shouldVibrate = true
            bottomHapticArmed = false
        }

        if (shouldVibrate) {
            hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.consumeWindowInsets(contentPadding),
        contentPadding = contentPadding
    ) {
        items(memos.itemCount) { index ->
            val memo = memos[index]
            memo?.let { item ->
                val adaptedMemo = remember(item, accountKey) {
                    item.toExploreMemoEntity(accountKey)
                }
                MemosCard(
                    memo = adaptedMemo,
                    onClick = { selectedMemo ->
                        rootNavController.navigate(
                            "${RouteName.MEMO_DETAIL}?memoId=${Uri.encode(selectedMemo.identifier)}"
                        )
                    },
                    editGesture = MemoEditGesture.NONE,
                    previewMode = true,
                    showSyncStatus = false,
                    authorAvatarUrl = item.creator?.avatarUrl,
                    authorName = item.creator?.name,
                    onTagClick = { tag ->
                        rootNavController.navigate("${RouteName.TAG}/${Uri.encode(tag)}") {
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    actionButton = { _ -> }
                )
            }
        }
    }
}

private fun Memo.toExploreMemoEntity(accountKey: String): MemoEntity {
    val memoIdentifier = "explore:$remoteId"
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
        needsSync = false,
        isDeleted = false,
        lastModified = syncedAt,
        lastSyncedAt = syncedAt
    )
    entity.resources = resources.map { resource ->
        ResourceEntity(
            identifier = "explore:$remoteId:resource:${resource.remoteId}",
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
