package site.lcyk.keer.ui.page.memos

import android.net.Uri
import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.compose.ui.res.stringResource
import site.lcyk.keer.data.local.entity.MemoEntity
import site.lcyk.keer.data.local.entity.ResourceEntity
import site.lcyk.keer.data.model.Account
import site.lcyk.keer.data.model.Memo
import site.lcyk.keer.data.model.MemoEditGesture
import site.lcyk.keer.R
import site.lcyk.keer.ui.component.MemosCard
import site.lcyk.keer.ui.page.common.LocalRootNavController
import site.lcyk.keer.ui.page.common.RouteName
import site.lcyk.keer.util.extractCollaboratorIds
import site.lcyk.keer.util.normalizeCollaboratorId
import site.lcyk.keer.viewmodel.ExploreMemoItem
import site.lcyk.keer.viewmodel.ExploreViewModel
import site.lcyk.keer.viewmodel.LocalUserState
import kotlinx.coroutines.launch

@Composable
fun ExploreList(
    viewModel: ExploreViewModel = hiltViewModel(),
    contentPadding: PaddingValues
) {
    val memos = viewModel.exploreMemos.collectAsLazyPagingItems()
    val userStateViewModel = LocalUserState.current
    val currentAccount by userStateViewModel.currentAccount.collectAsState()
    val mutationErrorMessage by viewModel.mutationErrorMessage.collectAsState()
    val rootNavController = LocalRootNavController.current
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val hapticFeedback = LocalHapticFeedback.current
    var topHapticArmed by remember { mutableStateOf(false) }
    var bottomHapticArmed by remember { mutableStateOf(false) }
    var editingMemo by remember { mutableStateOf<ExploreMemoItem?>(null) }
    var editingContent by remember { mutableStateOf("") }
    var deletingMemo by remember { mutableStateOf<ExploreMemoItem?>(null) }
    val accountKey = currentAccount?.accountKey() ?: "explore"
    val currentUserId = when (val account = currentAccount) {
        is Account.KeerV2 -> account.info.id.toString()
        is Account.Local -> "local"
        null -> ""
    }

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
            val memoItem = memos[index]
            memoItem?.let { item ->
                val adaptedMemo = remember(item.memo, accountKey) {
                    item.memo.toExploreMemoEntity(accountKey)
                }
                val canManageMemo = remember(item.memo, currentUserId) {
                    canManageExploreMemo(item.memo, currentUserId)
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
                    authorAvatarUrl = item.memo.creator?.avatarUrl,
                    authorName = item.memo.creator?.name,
                    actionButton = { memoEntity ->
                        ExploreMemoCardActionButton(
                            memo = memoEntity,
                            canManage = canManageMemo,
                            onEdit = {
                                val groupId = item.groupId
                                if (!groupId.isNullOrBlank()) {
                                    rootNavController.navigate(
                                        "${RouteName.GROUP_INPUT}?groupId=${Uri.encode(groupId)}&memoId=${Uri.encode(item.memo.remoteId)}"
                                    )
                                } else {
                                    editingMemo = item
                                    editingContent = item.memo.content
                                }
                            },
                            onDelete = {
                                deletingMemo = item
                            }
                        )
                    },
                    onTagClick = { tag ->
                        rootNavController.navigate("${RouteName.TAG}/${Uri.encode(tag)}") {
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                )
            }
        }
    }

    if (editingMemo != null) {
        AlertDialog(
            onDismissRequest = { editingMemo = null },
            title = { Text(text = stringResource(R.string.edit)) },
            text = {
                OutlinedTextField(
                    value = editingContent,
                    onValueChange = { editingContent = it },
                    label = { Text(text = stringResource(R.string.memo)) }
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
                                tags = target.memo.tags
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
            }
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
            }
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
            }
        )
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

@Composable
private fun ExploreMemoCardActionButton(
    memo: MemoEntity,
    canManage: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val clipboardManager = context.getSystemService(ClipboardManager::class.java)
    val memoLabel = stringResource(R.string.memo)
    val hapticFeedback = LocalHapticFeedback.current

    Box {
        IconButton(onClick = {
            hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
            menuExpanded = true
        }) {
            Icon(Icons.Filled.MoreVert, contentDescription = null)
        }
        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false }
        ) {
            if (canManage) {
                DropdownMenuItem(
                    text = { Text(text = stringResource(R.string.edit)) },
                    onClick = {
                        onEdit()
                        menuExpanded = false
                    },
                    leadingIcon = {
                        Icon(Icons.Outlined.Edit, contentDescription = null)
                    }
                )
            }
            DropdownMenuItem(
                text = { Text(text = stringResource(R.string.copy)) },
                onClick = {
                    clipboardManager?.setPrimaryClip(
                        ClipData.newPlainText(memoLabel, memo.content)
                    )
                    menuExpanded = false
                },
                leadingIcon = {
                    Icon(Icons.Outlined.ContentCopy, contentDescription = null)
                }
            )
            if (canManage) {
                DropdownMenuItem(
                    text = { Text(text = stringResource(R.string.delete)) },
                    onClick = {
                        onDelete()
                        menuExpanded = false
                    },
                    leadingIcon = {
                        Icon(Icons.Outlined.Delete, contentDescription = null)
                    },
                    colors = MenuDefaults.itemColors(
                        textColor = MaterialTheme.colorScheme.error,
                        leadingIconColor = MaterialTheme.colorScheme.error
                    )
                )
            }
        }
    }
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
