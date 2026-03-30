package site.lcyk.keer.ui.page.memoinput

import android.content.ClipData
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.outlined.FormatListBulleted
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.outlined.Attachment
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.FormatBold
import androidx.compose.material.icons.outlined.FormatItalic
import androidx.compose.material.icons.outlined.FormatListNumbered
import androidx.compose.material.icons.outlined.FormatStrikethrough
import androidx.compose.material.icons.outlined.GroupAdd
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.BottomAppBarDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.mimeTypes
import androidx.compose.ui.draganddrop.toAndroidDragEvent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import site.lcyk.keer.R
import site.lcyk.keer.data.local.entity.ResourceEntity
import site.lcyk.keer.data.model.User
import site.lcyk.keer.ext.formatString
import site.lcyk.keer.ext.string
import site.lcyk.keer.ui.component.Attachment
import site.lcyk.keer.ui.component.InputImage
import site.lcyk.keer.ui.component.KeerRemovableTagChip
import site.lcyk.keer.util.isValidTagName
import site.lcyk.keer.viewmodel.MemoEditorUploadFeedbackState
import site.lcyk.keer.viewmodel.MemoEditorUploadSectionKind
import site.lcyk.keer.viewmodel.MemoEditorUploadSectionState
import site.lcyk.keer.viewmodel.MemoEditorUploadTaskSectionState
import site.lcyk.keer.viewmodel.MemoEditorUploadsSummaryKind
import site.lcyk.keer.viewmodel.MemoEditorUploadsState
import site.lcyk.keer.viewmodel.MemoEditorUploadTaskItemState
import site.lcyk.keer.util.normalizeCollaboratorId
import site.lcyk.keer.util.normalizeTagList
import site.lcyk.keer.util.normalizeTagName
import site.lcyk.keer.viewmodel.UploadTaskStatus
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MemoInputTopBar(
    isEditMode: Boolean,
    canSubmit: Boolean,
    onClose: () -> Unit,
    onSubmit: () -> Unit
) {
    TopAppBar(
        title = {
            if (isEditMode) {
                Text(R.string.edit.string)
            } else {
                Text(R.string.compose.string)
            }
        },
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.close))
            }
        },
        actions = {
            IconButton(
                enabled = canSubmit,
                onClick = onSubmit
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.post))
            }
        }
    )
}

@Composable
internal fun MemoUploadFeedbackSnackbarEffect(
    hostState: SnackbarHostState,
    feedbackState: MemoEditorUploadFeedbackState,
) {
    var lastShownTriggerId by remember { mutableStateOf<String?>(null) }
    val snackbarMessage = when (feedbackState.recentlyCompletedResourceCount) {
        1 -> R.string.upload_recently_added_one.string
        0 -> null
        else -> R.string.upload_recently_added_many.formatString(
            feedbackState.recentlyCompletedResourceCount,
        )
    }

    LaunchedEffect(
        feedbackState.recentCompletionTriggerId,
        feedbackState.shouldShowRecentCompletionSnackbar,
        snackbarMessage,
    ) {
        val triggerId = feedbackState.recentCompletionTriggerId ?: return@LaunchedEffect
        if (!feedbackState.shouldShowRecentCompletionSnackbar) {
            return@LaunchedEffect
        }
        if (triggerId == lastShownTriggerId || snackbarMessage.isNullOrEmpty()) {
            return@LaunchedEffect
        }
        lastShownTriggerId = triggerId
        hostState.showSnackbar(snackbarMessage)
    }
}

@Composable
private fun FormattingButtons(
    onFormat: (MarkdownFormat) -> Unit,
) {
    MarkdownFormat.entries.forEach { format ->
        IconButton(onClick = { onFormat(format) }) {
            when (format) {
                MarkdownFormat.BOLD -> Icon(Icons.Outlined.FormatBold, contentDescription = format.label)
                MarkdownFormat.ITALIC -> Icon(Icons.Outlined.FormatItalic, contentDescription = format.label)
                MarkdownFormat.STRIKETHROUGH -> Icon(Icons.Outlined.FormatStrikethrough, contentDescription = format.label)
                MarkdownFormat.BULLET -> Icon(Icons.AutoMirrored.Outlined.FormatListBulleted, contentDescription = format.label)
                MarkdownFormat.NUMBERED -> Icon(Icons.Outlined.FormatListNumbered, contentDescription = format.label)
                MarkdownFormat.H1, MarkdownFormat.H2, MarkdownFormat.H3 -> Text(
                    text = format.label,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            }
        }
    }
}

@Composable
internal fun MemoInputBottomBar(
    selectedTags: List<String>,
    selectedTagCount: Int,
    selectedCollaborators: List<String>,
    onTagSelectorClick: () -> Unit,
    onTagRemove: (String) -> Unit,
    onCollaboratorSelectorClick: () -> Unit,
    onCollaboratorRemove: (String) -> Unit,
    onToggleTodoItem: () -> Unit,
    onPickImage: () -> Unit,
    onPickAttachment: () -> Unit,
    onTakePhoto: () -> Unit,
    onTakeVideo: () -> Unit,
    onFormat: (MarkdownFormat) -> Unit,
    compact: Boolean = false,
    trailingAction: (@Composable RowScope.() -> Unit)? = null,
) {
    val scrollState = rememberScrollState()
    val normalizedSelectedTags = remember(selectedTags) { normalizeTagList(selectedTags) }
    val normalizedCollaborators = remember(selectedCollaborators) {
        selectedCollaborators
            .map(::normalizeCollaboratorId)
            .filter { it.isNotEmpty() }
            .distinct()
    }

    Column {
        if (normalizedCollaborators.isNotEmpty()) {
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(normalizedCollaborators, key = { it }) { collaboratorId ->
                    KeerRemovableTagChip(
                        tag = "co:$collaboratorId",
                        onRemove = { onCollaboratorRemove(collaboratorId) }
                    )
                }
            }
        }
        if (normalizedSelectedTags.isNotEmpty()) {
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = if (compact) 4.dp else 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(normalizedSelectedTags, key = { it }) { tag ->
                    KeerRemovableTagChip(
                        tag = tag,
                        onRemove = { onTagRemove(tag) }
                    )
                }
            }
        }
        BottomAppBar(
            modifier = Modifier.height(if (compact) 60.dp else 80.dp),
            containerColor = if (compact) Color.Transparent else BottomAppBarDefaults.containerColor,
            tonalElevation = if (compact) 0.dp else BottomAppBarDefaults.ContainerElevation,
            contentPadding = if (compact) {
                PaddingValues(horizontal = 8.dp, vertical = 2.dp)
            } else {
                BottomAppBarDefaults.ContentPadding
            }
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.horizontalScroll(scrollState),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        MemoToolbarIconButton(compact = compact, onClick = onTagSelectorClick) {
                            BadgedBox(
                                badge = {
                                    if (selectedTagCount > 0) {
                                        Badge {
                                            Text(selectedTagCount.toString())
                                        }
                                    }
                                }
                            ) {
                                Icon(Icons.Outlined.Tag, contentDescription = stringResource(R.string.tag))
                            }
                        }

                        MemoToolbarIconButton(compact = compact, onClick = onCollaboratorSelectorClick) {
                            BadgedBox(
                                badge = {
                                    if (normalizedCollaborators.isNotEmpty()) {
                                        Badge {
                                            Text(normalizedCollaborators.size.toString())
                                        }
                                    }
                                }
                            ) {
                                Icon(
                                    Icons.Outlined.GroupAdd,
                                    contentDescription = stringResource(R.string.collaborators)
                                )
                            }
                        }

                        MemoToolbarIconButton(compact = compact, onClick = onPickImage) {
                            Icon(Icons.Outlined.Image, contentDescription = stringResource(R.string.add_media))
                        }

                        MemoToolbarIconButton(compact = compact, onClick = onTakePhoto) {
                            Icon(Icons.Outlined.PhotoCamera, contentDescription = stringResource(R.string.take_photo))
                        }

                        MemoToolbarIconButton(compact = compact, onClick = onTakeVideo) {
                            Icon(
                                Icons.Filled.Videocam,
                                contentDescription = stringResource(R.string.record_video),
                                modifier = Modifier.size(if (compact) 22.dp else 26.dp)
                            )
                        }

                        MemoToolbarIconButton(compact = compact, onClick = onPickAttachment) {
                            Icon(Icons.Outlined.Attachment, contentDescription = stringResource(R.string.attachment))
                        }

                        MemoToolbarIconButton(compact = compact, onClick = onToggleTodoItem) {
                            Icon(Icons.Outlined.CheckBox, contentDescription = stringResource(R.string.add_task))
                        }

                        Spacer(modifier = Modifier.size(if (compact) 2.dp else 4.dp))

                        FormattingButtons(onFormat = onFormat)
                    }
                }
                if (trailingAction != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.End,
                        content = trailingAction
                    )
                }
            }
        }
    }
}

@Composable
private fun MemoToolbarIconButton(
    compact: Boolean,
    onClick: () -> Unit,
    content: @Composable () -> Unit
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(if (compact) 40.dp else 48.dp),
        colors = IconButtonDefaults.iconButtonColors(
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
        ),
        content = content
    )
}

@Composable
internal fun MemoTagSelectorDialog(
    availableTags: List<String>,
    selectedTags: List<String>,
    onSelectedTagsChange: (List<String>) -> Unit,
    onDismiss: () -> Unit
) {
    val queryState = remember { mutableStateOf("") }
    val expandedTagNodes = remember { mutableStateMapOf<String, Boolean>() }
    val normalizedSelectedTags = remember(selectedTags) { normalizeTagList(selectedTags) }
    val normalizedTags = remember(availableTags, normalizedSelectedTags) {
        normalizeTagList(availableTags + normalizedSelectedTags)
    }
    val tagTree = remember(normalizedTags) { buildMemoTagTree(normalizedTags) }
    val query = normalizeTagName(queryState.value)
    val visibleTagEntries = flattenMemoTagTree(tagTree, expandedTagNodes, query)
    val parentTag = query.substringBeforeLast("/", "")
    val parentExists = parentTag.isEmpty() || normalizedTags.any { it.equals(parentTag, ignoreCase = true) }
    val showMissingParentHint = query.contains("/") && query.isNotEmpty() && !parentExists
    val canCreateTag = query.isNotEmpty() &&
            isValidTagName(query) &&
            parentExists &&
            normalizedTags.none { it.equals(query, ignoreCase = true) }

    fun toggleTag(tag: String) {
        val normalizedTag = normalizeTagName(tag)
        if (normalizedTag.isEmpty()) {
            return
        }
        val next = normalizedSelectedTags.toMutableList()
        val existingIndex = next.indexOfFirst { it.equals(normalizedTag, ignoreCase = false) }
        if (existingIndex >= 0) {
            next.removeAt(existingIndex)
        } else {
            next.add(normalizedTag)
        }
        onSelectedTagsChange(normalizeTagList(next))
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(R.string.tag.string) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = queryState.value,
                    onValueChange = { queryState.value = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(R.string.search.string) },
                    singleLine = true
                )

                if (normalizedSelectedTags.isNotEmpty()) {
                    Text(
                        text = R.string.selected.string,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(normalizedSelectedTags, key = { it }) { tag ->
                            KeerRemovableTagChip(
                                tag = tag,
                                onRemove = { toggleTag(tag) }
                            )
                        }
                    }
                }

                if (showMissingParentHint) {
                    Text(
                        text = stringResource(R.string.create_parent_tag_first, parentTag),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 280.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (canCreateTag) {
                        item("create_$query") {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                                    .clickable {
                                        toggleTag(query)
                                        queryState.value = ""
                                    }
                                    .padding(horizontal = 10.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Outlined.Tag, contentDescription = null)
                                Spacer(modifier = Modifier.size(8.dp))
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    Text(
                                        text = query,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = stringResource(R.string.create_new_tag),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Text(
                                    text = "+",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }

                    items(visibleTagEntries, key = { it.fullPath }) { entry ->
                        val selected = normalizedSelectedTags.any { it.equals(entry.fullPath, ignoreCase = true) }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    MaterialTheme.colorScheme.surfaceVariant.copy(
                                        alpha = if (selected) 0.65f else 0f
                                    )
                                )
                                .clickable(
                                    enabled = entry.selectable
                                ) { toggleTag(entry.fullPath) }
                                .padding(horizontal = 6.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (entry.expandable) {
                                IconButton(
                                    onClick = {
                                        expandedTagNodes[entry.fullPath] = !entry.expanded
                                    },
                                    modifier = Modifier.size(30.dp)
                                ) {
                                    Icon(
                                        imageVector = if (entry.expanded) {
                                            Icons.Filled.ExpandMore
                                        } else {
                                            Icons.AutoMirrored.Filled.KeyboardArrowRight
                                        },
                                        contentDescription = null
                                    )
                                }
                            } else {
                                Spacer(modifier = Modifier.size(30.dp))
                            }
                            Spacer(modifier = Modifier.width((entry.depth * 10).dp))
                            Icon(
                                imageVector = Icons.Outlined.Tag,
                                contentDescription = null,
                                tint = if (selected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.size(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = entry.displayName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (entry.selectable) {
                                        MaterialTheme.colorScheme.onSurface
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                                )
                                if (entry.parentPath.isNotEmpty()) {
                                    Text(
                                        text = entry.parentPath,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            if (entry.selectable) {
                                Checkbox(
                                    checked = selected,
                                    onCheckedChange = { toggleTag(entry.fullPath) }
                                )
                            } else {
                                Spacer(modifier = Modifier.size(24.dp))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(R.string.confirm.string)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(R.string.cancel.string)
            }
        }
    )
}

@Composable
internal fun MemoCollaboratorDialog(
    availableCollaborators: List<User>,
    selectedCollaborators: List<String>,
    onSelectedCollaboratorsChange: (List<String>) -> Unit,
    onDismiss: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    val collaborators = remember(selectedCollaborators) {
        selectedCollaborators
            .map(::normalizeCollaboratorId)
            .filter { it.isNotEmpty() }
            .distinct()
    }
    val selectedSet = remember(collaborators) { collaborators.toSet() }
    val visibleCollaborators = remember(availableCollaborators, collaborators, query) {
        val knownIds = availableCollaborators
            .map { collaborator -> normalizeCollaboratorId(collaborator.identifier) }
            .toSet()
        val unknownSelected = collaborators
            .filterNot(knownIds::contains)
            .map { collaboratorId ->
                User(
                    identifier = collaboratorId,
                    name = collaboratorId
                )
            }
        (availableCollaborators + unknownSelected)
            .distinctBy { collaborator -> normalizeCollaboratorId(collaborator.identifier) }
            .filter { collaborator ->
                val normalizedId = normalizeCollaboratorId(collaborator.identifier)
                if (normalizedId.isEmpty()) {
                    return@filter false
                }
                val normalizedQuery = query.trim()
                normalizedQuery.isEmpty() ||
                    collaborator.name.contains(normalizedQuery, ignoreCase = true) ||
                    normalizedId.contains(normalizedQuery, ignoreCase = true)
            }
            .sortedBy { collaborator -> collaborator.name.lowercase() }
    }

    fun toggleCollaborator(raw: String) {
        val normalized = normalizeCollaboratorId(raw)
        if (normalized.isEmpty()) {
            return
        }
        val next = collaborators.toMutableList()
        if (selectedSet.contains(normalized)) {
            next.removeAll { it == normalized }
        } else {
            next.add(normalized)
        }
        onSelectedCollaboratorsChange(next.distinct())
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(R.string.collaborators.string) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(R.string.search.string) },
                    singleLine = true
                )

                if (collaborators.isNotEmpty()) {
                    LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(collaborators, key = { it }) { collaboratorId ->
                            KeerRemovableTagChip(
                                tag = "co:$collaboratorId",
                                onRemove = { toggleCollaborator(collaboratorId) }
                            )
                        }
                    }
                }

                if (visibleCollaborators.isEmpty()) {
                    Text(
                        text = R.string.no_friends_available_for_collaboration.string,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 280.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(
                            visibleCollaborators,
                            key = { collaborator -> normalizeCollaboratorId(collaborator.identifier) }
                        ) { collaborator ->
                            val normalizedId = normalizeCollaboratorId(collaborator.identifier)
                            val selected = selectedSet.contains(normalizedId)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(
                                        MaterialTheme.colorScheme.surfaceVariant.copy(
                                            alpha = if (selected) 0.65f else 0f
                                        )
                                    )
                                    .clickable { toggleCollaborator(normalizedId) }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = collaborator.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                                    )
                                    Text(
                                        text = normalizedId,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Checkbox(
                                    checked = selected,
                                    onCheckedChange = { toggleCollaborator(normalizedId) }
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(R.string.confirm.string)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(R.string.cancel.string)
            }
        }
    )
}

private data class MemoTagTreeNode(
    val segment: String,
    val fullPath: String,
    var isRealTag: Boolean = false,
    val children: LinkedHashMap<String, MemoTagTreeNode> = linkedMapOf()
)

private data class MemoTagListEntry(
    val fullPath: String,
    val displayName: String,
    val parentPath: String,
    val depth: Int,
    val selectable: Boolean,
    val expandable: Boolean,
    val expanded: Boolean
)

private fun buildMemoTagTree(tags: List<String>): List<MemoTagTreeNode> {
    val roots = linkedMapOf<String, MemoTagTreeNode>()

    tags.forEach { rawTag ->
        val normalizedTag = normalizeTagName(rawTag)
        if (normalizedTag.isEmpty()) {
            return@forEach
        }
        val segments = normalizedTag
            .split("/")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (segments.isEmpty()) {
            return@forEach
        }

        var currentMap = roots
        var currentPath = ""
        var lastNode: MemoTagTreeNode? = null

        segments.forEach { segment ->
            currentPath = if (currentPath.isEmpty()) segment else "$currentPath/$segment"
            val node = currentMap.getOrPut(segment) {
                MemoTagTreeNode(
                    segment = segment,
                    fullPath = currentPath
                )
            }
            currentMap = node.children
            lastNode = node
        }
        lastNode?.isRealTag = true
    }

    return roots.values.toList()
}

private fun flattenMemoTagTree(
    roots: List<MemoTagTreeNode>,
    expandedState: Map<String, Boolean>,
    query: String
): List<MemoTagListEntry> {
    val result = mutableListOf<MemoTagListEntry>()

    fun nodeOrChildrenMatch(node: MemoTagTreeNode): Boolean {
        if (query.isEmpty()) {
            return true
        }
        if (node.fullPath.contains(query, ignoreCase = true)) {
            return true
        }
        return node.children.values.any(::nodeOrChildrenMatch)
    }

    fun visit(node: MemoTagTreeNode, depth: Int) {
        if (!nodeOrChildrenMatch(node)) {
            return
        }

        val hasChildren = node.children.isNotEmpty()
        val expanded = if (query.isNotEmpty()) {
            true
        } else {
            expandedState[node.fullPath] ?: true
        }
        val parentPath = node.fullPath.substringBeforeLast("/", "")

        result += MemoTagListEntry(
            fullPath = node.fullPath,
            displayName = node.segment,
            parentPath = parentPath,
            depth = depth,
            selectable = node.isRealTag,
            expandable = hasChildren,
            expanded = expanded
        )

        if (hasChildren && expanded) {
            node.children.values.forEach { child ->
                visit(child, depth + 1)
            }
        }
    }

    roots.forEach { root ->
        visit(root, 0)
    }

    return result
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun MemoInputEditor(
    modifier: Modifier = Modifier,
    text: TextFieldValue,
    onTextChange: (TextFieldValue) -> Unit,
    focusRequester: FocusRequester,
    quotePreview: (@Composable () -> Unit)? = null,
    quotePreviewPadding: PaddingValues = PaddingValues(start = 20.dp, top = 0.dp, end = 20.dp, bottom = 12.dp),
    editorPadding: PaddingValues = PaddingValues(start = 20.dp, top = 0.dp, end = 20.dp, bottom = 20.dp),
    fillAvailableHeight: Boolean = true,
    editorMinHeight: Dp = Dp.Unspecified,
    editorMaxHeight: Dp = Dp.Unspecified,
    minLines: Int = 1,
    maxLines: Int = Int.MAX_VALUE,
    validMimeTypePrefixes: Set<String>,
    onDroppedText: (String) -> Unit,
    uploadsState: MemoEditorUploadsState,
    onRemoveUploadResource: (ResourceEntity) -> Unit,
    onClearImageUploadResources: () -> Unit,
    onClearAttachmentUploadResources: () -> Unit,
    onCancelUploadTask: (String) -> Unit,
    onCancelActiveUploadTasks: () -> Unit,
    onRetryFailedUploadTasks: () -> Unit,
    onClearFailedUploadTasks: () -> Unit,
    onRetryUploadTask: (String) -> Unit,
    onDismissUploadTask: (String) -> Unit
) {
    var taskSectionExpanded by rememberSaveable { mutableStateOf(uploadsState.taskSection.defaultExpanded) }
    var imageSectionExpanded by rememberSaveable { mutableStateOf(uploadsState.imageSection.defaultExpanded) }
    var attachmentSectionExpanded by rememberSaveable { mutableStateOf(uploadsState.attachmentSection.defaultExpanded) }

    LaunchedEffect(
        uploadsState.taskSection.defaultExpanded,
        uploadsState.taskSection.totalCount,
        uploadsState.taskSection.activeCount,
        uploadsState.taskSection.failedCount,
    ) {
        if (uploadsState.taskSection.totalCount == 0) {
            taskSectionExpanded = uploadsState.taskSection.defaultExpanded
        } else if (uploadsState.taskSection.defaultExpanded) {
            taskSectionExpanded = true
        }
    }
    LaunchedEffect(
        uploadsState.imageSection.defaultExpanded,
        uploadsState.imageSection.totalCount,
        uploadsState.imageSection.highlightedCount,
    ) {
        if (uploadsState.imageSection.totalCount == 0) {
            imageSectionExpanded = uploadsState.imageSection.defaultExpanded
        } else if (uploadsState.imageSection.defaultExpanded && uploadsState.imageSection.highlightedCount > 0) {
            imageSectionExpanded = true
        }
    }
    LaunchedEffect(
        uploadsState.attachmentSection.defaultExpanded,
        uploadsState.attachmentSection.totalCount,
        uploadsState.attachmentSection.highlightedCount,
    ) {
        if (uploadsState.attachmentSection.totalCount == 0) {
            attachmentSectionExpanded = uploadsState.attachmentSection.defaultExpanded
        } else if (uploadsState.attachmentSection.defaultExpanded && uploadsState.attachmentSection.highlightedCount > 0) {
            attachmentSectionExpanded = true
        }
    }

    Column(
        modifier
            .then(
                if (fillAvailableHeight) {
                    Modifier.fillMaxHeight()
                } else {
                    Modifier
                }
            )
            .dragAndDropTarget(
                shouldStartDragAndDrop = accept@{ startEvent ->
                    startEvent
                        .mimeTypes()
                        .any { eventMimeType ->
                            validMimeTypePrefixes.any(eventMimeType::startsWith)
                        }
                },
                target = object : DragAndDropTarget {
                    override fun onDrop(event: DragAndDropEvent): Boolean {
                        val androidDragEvent = event.toAndroidDragEvent()
                        val concatText = androidDragEvent.clipData
                            .textList()
                            .fold("") { acc, droppedText ->
                                if (acc.isNotBlank()) {
                                    acc.trimEnd { it == '\n' } + "\n\n" + droppedText.trimStart { it == '\n' }
                                } else {
                                    droppedText
                                }
                            }
                        onDroppedText(concatText)
                        return true
                    }
                }
            )
    ) {
        if (quotePreview != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(quotePreviewPadding)
            ) {
                quotePreview()
            }
        }

        OutlinedTextField(
            modifier = Modifier
                .fillMaxWidth()
                .padding(editorPadding)
                .then(
                    if (fillAvailableHeight) {
                        Modifier.weight(1f)
                    } else {
                        Modifier.heightIn(min = editorMinHeight, max = editorMaxHeight)
                    }
                )
                .focusRequester(focusRequester),
            value = text,
            label = { Text(R.string.any_thoughts.string) },
            onValueChange = onTextChange,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            minLines = minLines,
            maxLines = maxLines
        )

        if (uploadsState.summary.hasVisibleContent) {
            UploadSummaryBar(
                uploadsState = uploadsState,
                onCancelActiveUploadTasks = onCancelActiveUploadTasks,
                onRetryFailedUploadTasks = onRetryFailedUploadTasks,
                onClearFailedUploadTasks = onClearFailedUploadTasks,
            )
        }

        if (uploadsState.taskSection.totalCount > 0) {
            UploadTaskSectionHeader(
                section = uploadsState.taskSection,
                expanded = taskSectionExpanded,
                onToggleExpanded = { taskSectionExpanded = !taskSectionExpanded },
            )
            if (taskSectionExpanded) {
                LazyRow(
                    modifier = Modifier
                        .padding(start = 15.dp, end = 15.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(uploadsState.taskSection.items, key = { it.task.id }) { taskItem ->
                        UploadTaskItem(
                            taskItem = taskItem,
                            onCancel = { onCancelUploadTask(taskItem.task.id) },
                            onRetry = { onRetryUploadTask(taskItem.task.id) },
                            onDismiss = { onDismissUploadTask(taskItem.task.id) },
                        )
                    }
                }
            }
        }

        if (uploadsState.imageSection.totalCount > 0) {
            UploadResourceSectionHeader(
                section = uploadsState.imageSection,
                expanded = imageSectionExpanded,
                onToggleExpanded = { imageSectionExpanded = !imageSectionExpanded },
                onClearAll = onClearImageUploadResources,
            )
            if (imageSectionExpanded) {
                LazyRow(
                    modifier = Modifier
                        .height(80.dp)
                        .padding(
                            start = 15.dp,
                            end = 15.dp,
                            bottom = if (uploadsState.attachmentSection.totalCount == 0) 15.dp else 8.dp
                        ),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(uploadsState.imageSection.items, key = { it.resource.identifier }) { item ->
                        UploadResourceHighlightFrame(highlighted = item.isHighlighted) {
                            InputImage(
                                resource = item.resource,
                                onRemove = onRemoveUploadResource,
                            )
                        }
                    }
                }
            }
        }

        if (uploadsState.attachmentSection.totalCount > 0) {
            UploadResourceSectionHeader(
                section = uploadsState.attachmentSection,
                expanded = attachmentSectionExpanded,
                onToggleExpanded = { attachmentSectionExpanded = !attachmentSectionExpanded },
                onClearAll = onClearAttachmentUploadResources,
            )
            if (attachmentSectionExpanded) {
                LazyRow(
                    modifier = Modifier
                        .padding(start = 15.dp, end = 15.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(uploadsState.attachmentSection.items, key = { it.resource.identifier }) { item ->
                        UploadResourceHighlightFrame(highlighted = item.isHighlighted) {
                            Attachment(
                                resource = item.resource,
                                onRemove = { onRemoveUploadResource(item.resource) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun UploadSummaryBar(
    uploadsState: MemoEditorUploadsState,
    onCancelActiveUploadTasks: () -> Unit,
    onRetryFailedUploadTasks: () -> Unit,
    onClearFailedUploadTasks: () -> Unit,
) {
    val summary = uploadsState.summary
    val actions = uploadsState.actions
    val summaryText = when (summary.kind) {
        MemoEditorUploadsSummaryKind.MIXED -> stringResource(
            R.string.upload_summary_mixed,
            summary.activeTaskCount,
            summary.failedTaskCount,
            summary.totalResourceCount,
        )
        MemoEditorUploadsSummaryKind.ACTIVE -> stringResource(
            R.string.upload_summary_active,
            summary.activeTaskCount,
            summary.totalResourceCount,
        )
        MemoEditorUploadsSummaryKind.FAILED -> stringResource(
            R.string.upload_summary_failed,
            summary.failedTaskCount,
            summary.totalResourceCount,
        )
        MemoEditorUploadsSummaryKind.READY -> stringResource(
            R.string.upload_summary_ready,
            summary.totalResourceCount,
        )
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 15.dp, end = 15.dp, bottom = 8.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = summaryText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (uploadsState.feedback.showRecentCompletionHint) {
                Text(
                    text = stringResource(
                        R.string.upload_summary_recent,
                        uploadsState.feedback.recentlyCompletedResourceCount,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (
                actions.canCancelActiveTasks ||
                actions.canRetryFailedTasks ||
                actions.canClearFailedTasks
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    if (actions.canCancelActiveTasks) {
                        TextButton(onClick = onCancelActiveUploadTasks) {
                            Text(stringResource(R.string.cancel_active_uploads))
                        }
                    }
                    if (actions.canRetryFailedTasks) {
                        TextButton(onClick = onRetryFailedUploadTasks) {
                            Text(stringResource(R.string.retry_failed_uploads))
                        }
                    }
                    if (actions.canClearFailedTasks) {
                        TextButton(onClick = onClearFailedUploadTasks) {
                            Text(stringResource(R.string.clear_failed_uploads))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun UploadResourceSectionHeader(
    section: MemoEditorUploadSectionState,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onClearAll: () -> Unit,
) {
    val title = when (section.kind) {
        MemoEditorUploadSectionKind.IMAGES -> stringResource(
            R.string.upload_section_images,
            section.totalCount,
        )
        MemoEditorUploadSectionKind.ATTACHMENTS -> stringResource(
            R.string.upload_section_attachments,
            section.totalCount,
        )
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 15.dp, end = 15.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (section.canCollapse) {
                    IconButton(
                        onClick = onToggleExpanded,
                        modifier = Modifier.size(28.dp),
                    ) {
                        Icon(
                            imageVector = if (expanded) {
                                Icons.Filled.ExpandMore
                            } else {
                                Icons.AutoMirrored.Filled.KeyboardArrowRight
                            },
                            contentDescription = stringResource(
                                if (expanded) R.string.collapse else R.string.expand,
                            ),
                        )
                    }
                    Spacer(modifier = Modifier.width(2.dp))
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (section.highlightedCount > 0) {
                Text(
                    text = stringResource(
                        R.string.upload_section_recent,
                        section.highlightedCount,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        if (section.canClearAll) {
            TextButton(onClick = onClearAll) {
                Text(stringResource(R.string.clear_all))
            }
        }
    }
}

@Composable
private fun UploadTaskSectionHeader(
    section: MemoEditorUploadTaskSectionState,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
) {
    val title = stringResource(R.string.upload_section_tasks, section.totalCount)
    val subtitle = when {
        section.activeCount > 0 && section.failedCount > 0 -> stringResource(
            R.string.upload_section_tasks_mixed,
            section.activeCount,
            section.failedCount,
        )
        section.activeCount > 0 -> stringResource(
            R.string.upload_section_tasks_active,
            section.activeCount,
        )
        section.failedCount > 0 -> stringResource(
            R.string.upload_section_tasks_failed,
            section.failedCount,
        )
        else -> null
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 15.dp, end = 15.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (section.canCollapse) {
                    IconButton(
                        onClick = onToggleExpanded,
                        modifier = Modifier.size(28.dp),
                    ) {
                        Icon(
                            imageVector = if (expanded) {
                                Icons.Filled.ExpandMore
                            } else {
                                Icons.AutoMirrored.Filled.KeyboardArrowRight
                            },
                            contentDescription = stringResource(
                                if (expanded) R.string.collapse else R.string.expand,
                            ),
                        )
                    }
                    Spacer(modifier = Modifier.width(2.dp))
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!subtitle.isNullOrEmpty()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun UploadResourceHighlightFrame(
    highlighted: Boolean,
    content: @Composable () -> Unit,
) {
    val highlightBorderColor by animateColorAsState(
        targetValue = if (highlighted) {
            MaterialTheme.colorScheme.tertiary
        } else {
            Color.Transparent
        },
        animationSpec = tween(durationMillis = 280),
        label = "uploadHighlightBorder",
    )
    val highlightBackgroundColor by animateColorAsState(
        targetValue = if (highlighted) {
            MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.55f)
        } else {
            Color.Transparent
        },
        animationSpec = tween(durationMillis = 320),
        label = "uploadHighlightBackground",
    )
    val highlightScale by animateFloatAsState(
        targetValue = if (highlighted) 1.03f else 1f,
        animationSpec = tween(durationMillis = 260),
        label = "uploadHighlightScale",
    )
    Box(
        modifier = Modifier
            .graphicsLayer(
                scaleX = highlightScale,
                scaleY = highlightScale,
            )
            .clip(RoundedCornerShape(12.dp))
            .background(highlightBackgroundColor)
            .border(
                width = 1.dp,
                color = highlightBorderColor,
                shape = RoundedCornerShape(12.dp),
            )
            .padding(3.dp),
    ) {
        content()
    }
}

@Composable
private fun UploadTaskItem(
    taskItem: MemoEditorUploadTaskItemState,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit
) {
    val task = taskItem.task
    val hasProgress = task.totalBytes > 0
    val progress = if (hasProgress) {
        (task.uploadedBytes.toFloat() / task.totalBytes.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    val statusText = when (task.status) {
        UploadTaskStatus.PREPARING -> stringResource(R.string.upload_preparing)
        UploadTaskStatus.UPLOADING -> {
            if (hasProgress) {
                "${stringResource(R.string.uploading)} ${(progress * 100).roundToInt()}% · ${formatBytes(task.uploadedBytes)}/${formatBytes(task.totalBytes)}"
            } else {
                stringResource(R.string.uploading)
            }
        }
        UploadTaskStatus.FAILED -> task.errorMessage ?: stringResource(R.string.upload_failed)
    }

    Card(modifier = Modifier.fillMaxWidth(0.9f)) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Outlined.Attachment,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.size(6.dp))
                    Text(
                        text = task.filename,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (taskItem.canCancel) {
                        IconButton(onClick = onCancel, modifier = Modifier.size(24.dp)) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = stringResource(R.string.cancel)
                            )
                        }
                    }
                    if (taskItem.canRetry) {
                        IconButton(onClick = onRetry, modifier = Modifier.size(24.dp)) {
                            Icon(
                                imageVector = Icons.Filled.Refresh,
                                contentDescription = stringResource(R.string.retry)
                            )
                        }
                    }
                    if (taskItem.canDismiss) {
                        IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = stringResource(R.string.close)
                            )
                        }
                    }
                }
            }

            Text(
                text = statusText,
                style = MaterialTheme.typography.bodySmall,
                color = if (task.status == UploadTaskStatus.FAILED) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )

            if (task.status == UploadTaskStatus.UPLOADING) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
internal fun SaveChangesDialog(
    onSave: () -> Unit,
    onDiscard: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(R.string.save_changes_title.string) },
        text = { Text(R.string.save_changes_message.string) },
        confirmButton = {
            Button(onClick = onSave) {
                Text(R.string.save.string)
            }
        },
        dismissButton = {
            Button(onClick = onDiscard) {
                Text(R.string.discard.string)
            }
        }
    )
}

private fun ClipData.textList(): List<String> {
    return (0 until itemCount)
        .mapNotNull(::getItemAt)
        .mapNotNull { it.text?.toString() }
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024L) return "${bytes}B"
    val kb = bytes / 1024.0
    if (kb < 1024.0) return String.format("%.1fKB", kb)
    val mb = kb / 1024.0
    if (mb < 1024.0) return String.format("%.1fMB", mb)
    val gb = mb / 1024.0
    return String.format("%.2fGB", gb)
}
