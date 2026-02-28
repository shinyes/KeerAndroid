package site.lcyk.keer.ui.component

import android.content.ClipData
import android.content.ClipboardManager
import android.text.format.DateUtils
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.PinDrop
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import com.skydoves.sandwich.suspendOnSuccess
import kotlinx.coroutines.launch
import site.lcyk.keer.R
import site.lcyk.keer.data.local.entity.MemoEntity
import site.lcyk.keer.data.model.MemoEditGesture
import site.lcyk.keer.ext.string
import site.lcyk.keer.ui.page.common.LocalRootNavController
import site.lcyk.keer.ui.page.common.RouteName
import site.lcyk.keer.util.extractCollaboratorIds
import site.lcyk.keer.util.isCollaboratorTag
import site.lcyk.keer.util.normalizeTagList
import site.lcyk.keer.viewmodel.LocalMemos
import site.lcyk.keer.viewmodel.LocalUserState
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

@Composable
fun MemosCard(
    memo: MemoEntity,
    onClick: (MemoEntity) -> Unit,
    editGesture: MemoEditGesture = MemoEditGesture.NONE,
    previewMode: Boolean = false,
    showSyncStatus: Boolean = false,
    onTagClick: ((String) -> Unit)? = null,
    authorAvatarUrl: String? = null,
    authorName: String? = null,
    actionButton: (@Composable (MemoEntity) -> Unit)? = null
) {
    val context = LocalContext.current
    val memosViewModel = LocalMemos.current
    val rootNavController = LocalRootNavController.current
    val userStateViewModel = LocalUserState.current
    val collaboratorProfiles by userStateViewModel.collaboratorProfiles.collectAsState()
    val imageLoader = remember(userStateViewModel.okHttpClient) {
        ImageLoader.Builder(context)
            .components {
                add(OkHttpNetworkFetcherFactory(callFactory = { userStateViewModel.okHttpClient }))
            }
            .build()
    }
    val scope = rememberCoroutineScope()
    val displayTags = remember(memo.tags) {
        normalizeTagList(memo.tags.filterNot(::isCollaboratorTag))
    }
    val collaboratorIds = remember(memo.tags) { extractCollaboratorIds(memo.tags) }
    val hasAuthorIdentity = !authorAvatarUrl.isNullOrBlank() || !authorName.isNullOrBlank()
    val resolvedAuthorAvatarUrl = remember(authorAvatarUrl, userStateViewModel.host) {
        resolveAvatarUrl(userStateViewModel.host, authorAvatarUrl.orEmpty())
    }
    val authorAvatarFallback = remember(authorName) {
        authorName
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.take(2)
            ?.uppercase()
            ?: "?"
    }
    var showCollaboratorDialog by remember { mutableStateOf(false) }

    LaunchedEffect(collaboratorIds) {
        if (collaboratorIds.isNotEmpty()) {
            userStateViewModel.prefetchCollaboratorAvatars(collaboratorIds)
        }
    }

    val cardModifier = Modifier
        .padding(horizontal = 15.dp, vertical = 10.dp)
        .fillMaxWidth()
        .combinedClickable(
            onClick = {
                if (editGesture == MemoEditGesture.SINGLE) {
                    rootNavController.navigate("${RouteName.EDIT}?memoId=${memo.identifier}")
                } else {
                    onClick(memo)
                }
            },
            onLongClick = if (editGesture == MemoEditGesture.LONG) {
                {
                    rootNavController.navigate("${RouteName.EDIT}?memoId=${memo.identifier}")
                }
            } else {
                null
            },
            onDoubleClick = if (editGesture == MemoEditGesture.DOUBLE) {
                {
                    rootNavController.navigate("${RouteName.EDIT}?memoId=${memo.identifier}")
                }
            } else {
                null
            }
        )

    Card(
        modifier = cardModifier,
        border = if (memo.pinned) {
            BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
        } else {
            null
        }
    ) {
        Column {
            Row(
                modifier = Modifier
                    .padding(start = 15.dp)
                    .fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    DateUtils.getRelativeTimeSpanString(
                        memo.date.toEpochMilli(),
                        System.currentTimeMillis(),
                        DateUtils.SECOND_IN_MILLIS
                    ).toString(),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.outline
                )
                if (hasAuthorIdentity) {
                    Box(
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .size(20.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .border(1.dp, MaterialTheme.colorScheme.surface, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        if (!resolvedAuthorAvatarUrl.isNullOrBlank()) {
                            AsyncImage(
                                model = resolvedAuthorAvatarUrl,
                                imageLoader = imageLoader,
                                contentDescription = authorName,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(CircleShape)
                            )
                        } else {
                            Text(
                                text = authorAvatarFallback,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Clip,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                if (collaboratorIds.isNotEmpty()) {
                    CollaboratorAvatarStack(
                        collaboratorIds = collaboratorIds,
                        collaboratorProfiles = collaboratorProfiles,
                        onClick = { showCollaboratorDialog = true }
                    )
                }
                if (displayTags.isNotEmpty()) {
                    LazyRow(
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 8.dp, end = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        items(displayTags, key = { it }) { tag ->
                            KeerTagChip(
                                tag = tag,
                                onClick = { onTagClick?.invoke(tag) }
                            )
                        }
                    }
                } else {
                    Spacer(modifier = Modifier.weight(1f))
                }
                if (showSyncStatus && memo.needsSync) {
                    Icon(
                        imageVector = Icons.Outlined.CloudOff,
                        contentDescription = R.string.memo_sync_pending.string,
                        modifier = Modifier
                            .padding(start = 5.dp)
                            .size(20.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
                if (actionButton != null) {
                    actionButton(memo)
                } else {
                    MemosCardActionButton(memo)
                }
            }

            MemoContent(
                memo,
                previewMode = previewMode,
                checkboxChange = { checked, startOffset, endOffset ->
                    scope.launch {
                        var text = memo.content.substring(startOffset, endOffset)
                        text = if (checked) {
                            text.replace("[ ]", "[x]")
                        } else {
                            text.replace("[x]", "[ ]")
                        }
                        memosViewModel.editMemo(
                            memo.identifier,
                            memo.content.replaceRange(startOffset, endOffset, text),
                            memo.resources,
                            memo.visibility
                        )
                    }
                },
                onViewMore = {
                    onClick(memo)
                },
                onTagClick = onTagClick
            )
        }
    }

    if (showCollaboratorDialog) {
        CollaboratorListDialog(
            collaboratorIds = collaboratorIds,
            collaboratorProfiles = collaboratorProfiles,
            onDismiss = { showCollaboratorDialog = false }
        )
    }
}

private fun resolveAvatarUrl(host: String, avatarUrl: String): String? {
    if (avatarUrl.isBlank()) {
        return null
    }
    if (avatarUrl.toHttpUrlOrNull() != null || "://" in avatarUrl) {
        return avatarUrl
    }
    val baseUrl = host.toHttpUrlOrNull() ?: return avatarUrl
    return runCatching {
        baseUrl.toUrl().toURI().resolve(avatarUrl).toString()
    }.getOrDefault(avatarUrl)
}

@Composable
fun MemosCardActionButton(
    memo: MemoEntity,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val clipboardManager = context.getSystemService(ClipboardManager::class.java)
    val memosViewModel = LocalMemos.current
    val rootNavController = LocalRootNavController.current
    val scope = rememberCoroutineScope()
    var showDeleteDialog by remember { mutableStateOf(false) }
    val memoLabel = stringResource(R.string.memo)
    val hapticFeedback = LocalHapticFeedback.current

    Box {
        IconButton(onClick = {
            hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
            menuExpanded = true
        }) {
            Icon(Icons.Filled.MoreVert, contentDescription = null)
        }
        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
            if (memo.pinned) {
                DropdownMenuItem(
                    text = { Text(R.string.unpin.string) },
                    onClick = {
                        scope.launch {
                            memosViewModel.updateMemoPinned(memo.identifier, false).suspendOnSuccess {
                                menuExpanded = false
                            }
                        }
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Outlined.PinDrop,
                            contentDescription = null
                        )
                    })
            } else {
                DropdownMenuItem(
                    text = { Text(R.string.pin.string) },
                    onClick = {
                        scope.launch {
                            memosViewModel.updateMemoPinned(memo.identifier, true).suspendOnSuccess {
                                menuExpanded = false
                            }
                        }
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Outlined.PushPin,
                            contentDescription = null
                        )
                    })
            }
            DropdownMenuItem(
                text = { Text(R.string.edit.string) },
                onClick = {
                    rootNavController.navigate("${RouteName.EDIT}?memoId=${memo.identifier}")
                },
                leadingIcon = {
                    Icon(
                        Icons.Outlined.Edit,
                        contentDescription = null
                    )
                })
            DropdownMenuItem(
                text = { Text(R.string.copy.string) },
                onClick = {
                    clipboardManager?.setPrimaryClip(
                        ClipData.newPlainText(memoLabel, memo.content)
                    )
                    menuExpanded = false
                },
                leadingIcon = {
                    Icon(
                        Icons.Outlined.ContentCopy,
                        contentDescription = null
                    )
                })
            DropdownMenuItem(
                text = { Text(R.string.archive.string) },
                onClick = {
                    scope.launch {
                        memosViewModel.archiveMemo(memo.identifier).suspendOnSuccess {
                            menuExpanded = false
                        }
                    }
                },
                colors = MenuDefaults.itemColors(
                    textColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    leadingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
                leadingIcon = {
                    Icon(
                        Icons.Outlined.Archive,
                        contentDescription = null
                    )
                })
            DropdownMenuItem(
                text = { Text(R.string.delete.string) },
                onClick = {
                    showDeleteDialog = true
                    menuExpanded = false
                },
                colors = MenuDefaults.itemColors(
                    textColor = MaterialTheme.colorScheme.error,
                    leadingIconColor = MaterialTheme.colorScheme.error,
                ),
                leadingIcon = {
                    Icon(
                        Icons.Outlined.Delete,
                        contentDescription = null
                    )
                })
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(R.string.delete_this_memo.string) },
            confirmButton = {
                TextButton(
                    onClick = {
                        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                        scope.launch {
                            memosViewModel.deleteMemo(memo.identifier).suspendOnSuccess {
                                showDeleteDialog = false
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Text(R.string.confirm.string)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                    }
                ) {
                    Text(R.string.cancel.string)
                }
            }
        )
    }
}
