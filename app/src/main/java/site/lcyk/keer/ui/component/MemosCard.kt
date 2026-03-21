package site.lcyk.keer.ui.component

import android.content.ClipData
import android.content.ClipboardManager
import android.text.format.DateUtils
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.PinDrop
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import site.lcyk.keer.R
import site.lcyk.keer.data.local.entity.MemoEntity
import site.lcyk.keer.data.model.CollaboratorProfile
import site.lcyk.keer.data.model.MemoEditGesture
import site.lcyk.keer.ext.string
import site.lcyk.keer.ui.page.common.LocalRootNavController
import site.lcyk.keer.ui.page.common.RouteName
import site.lcyk.keer.ui.page.common.navigateToMemoInputPage
import site.lcyk.keer.util.MemoQuoteSourceKind
import site.lcyk.keer.util.extractCollaboratorIds
import site.lcyk.keer.util.isCollaboratorTag
import site.lcyk.keer.util.isQuoteTag
import site.lcyk.keer.util.normalizeTagList
import site.lcyk.keer.util.resolveAvatarUrl
import site.lcyk.keer.util.ResolvedMemoQuote
import site.lcyk.keer.viewmodel.LocalMemos
import site.lcyk.keer.viewmodel.LocalUserState

@Composable
fun MemosCard(
    memo: MemoEntity,
    onClick: (MemoEntity) -> Unit,
    editGesture: MemoEditGesture = MemoEditGesture.NONE,
    previewMode: Boolean = false,
    autoPreviewPrefetch: Boolean = true,
    showSyncStatus: Boolean = false,
    onTagClick: ((String) -> Unit)? = null,
    authorAvatarUrl: String? = null,
    authorName: String? = null,
    actionButton: (@Composable (MemoEntity) -> Unit)? = null,
    onRequestEdit: ((MemoEntity) -> Unit)? = null,
    collaboratorProfiles: Map<String, CollaboratorProfile> = emptyMap(),
    avatarImageLoader: ImageLoader? = null,
    mediaImageLoader: ImageLoader? = null,
    prefetchCollaborators: Boolean = true,
    resolvedQuote: ResolvedMemoQuote? = null,
) {
    val memosViewModel = LocalMemos.current
    val rootNavController = LocalRootNavController.current
    val userStateViewModel = LocalUserState.current
    val imageLoader = avatarImageLoader ?: rememberAuthorizedImageLoader()
    val scope = rememberCoroutineScope()
    val displayTags = remember(memo.tags) {
        normalizeTagList(
            memo.tags
                .filterNot(::isCollaboratorTag)
                .filterNot(::isQuoteTag)
        )
    }
    val collaboratorIds = remember(memo.tags) { extractCollaboratorIds(memo.tags) }
    val quotedMemo = resolvedQuote?.sourceMemo
    val quotePreview = remember(
        resolvedQuote,
    ) {
        resolvedQuote?.preview
    }
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
    val requestEdit = remember(memo, onRequestEdit, rootNavController) {
        {
            if (onRequestEdit != null) {
                onRequestEdit(memo)
            } else {
                rootNavController.navigate("${RouteName.EDIT}?memoId=${memo.identifier}")
            }
        }
    }
    var showCollaboratorDialog by remember { mutableStateOf(false) }

    LaunchedEffect(collaboratorIds, collaboratorProfiles, prefetchCollaborators) {
        if (!prefetchCollaborators) {
            return@LaunchedEffect
        }
        if (collaboratorIds.any { collaboratorId -> !collaboratorProfiles.containsKey(collaboratorId) }) {
            userStateViewModel.prefetchCollaboratorAvatars(collaboratorIds)
        }
    }

    val cardModifier = Modifier
        .padding(horizontal = 15.dp, vertical = 10.dp)
        .fillMaxWidth()
        .combinedClickable(
            onClick = {
                if (editGesture == MemoEditGesture.SINGLE) {
                    requestEdit()
                } else {
                    onClick(memo)
                }
            },
            onLongClick = if (editGesture == MemoEditGesture.LONG) {
                {
                    requestEdit()
                }
            } else {
                null
            },
            onDoubleClick = if (editGesture == MemoEditGesture.DOUBLE) {
                {
                    requestEdit()
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
                            .background(MaterialTheme.colorScheme.surfaceVariant),
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
                    MemosCardActionButton(
                        memo = memo,
                        onRequestEdit = onRequestEdit
                    )
                }
            }

            MemoContent(
                memo,
                previewMode = previewMode,
                autoPreviewPrefetch = autoPreviewPrefetch,
                mediaImageLoader = mediaImageLoader,
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

            if (resolvedQuote != null) {
                MemoQuoteReferenceCard(
                    quotedMemo = quotePreview,
                    modifier = Modifier
                        .padding(start = 15.dp, end = 15.dp, bottom = 10.dp),
                    onClick = quotedMemo?.let { source ->
                        {
                            onClick(source)
                        }
                    }
                )
            }
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


@Composable
fun MemosCardActionButton(
    memo: MemoEntity,
    onRequestEdit: ((MemoEntity) -> Unit)? = null,
    onRequestQuote: ((MemoEntity) -> Unit)? = null,
    showPinAction: Boolean = true,
    onTogglePin: ((MemoEntity) -> Unit)? = null,
) {
    val context = LocalContext.current
    val clipboardManager = context.getSystemService(ClipboardManager::class.java)
    val memosViewModel = LocalMemos.current
    val rootNavController = LocalRootNavController.current
    val scope = rememberCoroutineScope()
    val memoLabel = stringResource(R.string.memo)
    val actions = buildList {
        if (showPinAction) {
            add(
                MemoMenuAction(
                    key = if (memo.pinned) "unpin" else "pin",
                    label = if (memo.pinned) R.string.unpin.string else R.string.pin.string,
                    icon = if (memo.pinned) Icons.Outlined.PinDrop else Icons.Outlined.PushPin,
                    onSelected = {
                        if (onTogglePin != null) {
                            onTogglePin(memo)
                        } else {
                            scope.launch {
                                memosViewModel.updateMemoPinned(memo.identifier, !memo.pinned)
                            }
                        }
                    }
                )
            )
        }
        add(
            MemoMenuAction(
                key = "edit",
                label = R.string.edit.string,
                icon = Icons.Outlined.Edit,
                onSelected = {
                    if (onRequestEdit != null) {
                        onRequestEdit(memo)
                    } else {
                        rootNavController.navigate("${RouteName.EDIT}?memoId=${memo.identifier}")
                    }
                }
            )
        )
        add(
            MemoMenuAction(
                key = "quote",
                label = R.string.quote.string,
                icon = Icons.Outlined.RecordVoiceOver,
                onSelected = {
                    if (onRequestQuote != null) {
                        onRequestQuote(memo)
                    } else {
                        rootNavController.navigateToMemoInputPage(quoteMemoIdentifier = memo.identifier)
                    }
                }
            )
        )
        add(
            MemoMenuAction(
                key = "copy",
                label = R.string.copy.string,
                icon = Icons.Outlined.ContentCopy,
                onSelected = {
                    clipboardManager?.setPrimaryClip(
                        ClipData.newPlainText(memoLabel, memo.content)
                    )
                }
            )
        )
        add(
            MemoMenuAction(
                key = "archive",
                label = R.string.archive.string,
                icon = Icons.Outlined.Archive,
                onSelected = {
                    scope.launch {
                        memosViewModel.archiveMemo(memo.identifier)
                    }
                }
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
                onSelected = {
                    scope.launch {
                        memosViewModel.deleteMemo(memo.identifier)
                    }
                }
            )
        )
    }
    MemoActionMenuButton(actions = actions)
}
