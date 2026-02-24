package site.lcyk.keer.ui.page.memoinput

import android.content.ClipData
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.outlined.FormatListBulleted
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Attachment
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.FormatBold
import androidx.compose.material.icons.outlined.FormatItalic
import androidx.compose.material.icons.outlined.FormatListNumbered
import androidx.compose.material.icons.outlined.FormatStrikethrough
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.PopupProperties
import site.lcyk.keer.R
import site.lcyk.keer.data.local.entity.ResourceEntity
import site.lcyk.keer.data.model.Account
import site.lcyk.keer.data.model.MemoVisibility
import site.lcyk.keer.ext.icon
import site.lcyk.keer.ext.string
import site.lcyk.keer.ext.titleResource
import site.lcyk.keer.ui.component.Attachment
import site.lcyk.keer.ui.component.InputImage
import site.lcyk.keer.viewmodel.MemoInputViewModel
import site.lcyk.keer.viewmodel.UploadTaskState
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
    currentAccount: Account?,
    currentVisibility: MemoVisibility,
    visibilityMenuExpanded: Boolean,
    onVisibilityExpandedChange: (Boolean) -> Unit,
    onVisibilitySelected: (MemoVisibility) -> Unit,
    tags: List<String>,
    tagMenuExpanded: Boolean,
    onTagExpandedChange: (Boolean) -> Unit,
    onHashTagClick: () -> Unit,
    onTagSelected: (String) -> Unit,
    onToggleTodoItem: () -> Unit,
    onPickImage: () -> Unit,
    onPickAttachment: () -> Unit,
    onTakePhoto: () -> Unit,
    onFormat: (MarkdownFormat) -> Unit,
) {
    val scrollState = rememberScrollState()

    BottomAppBar {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.horizontalScroll(scrollState),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (currentAccount !is Account.Local) {
                    Box {
                        DropdownMenu(
                            expanded = visibilityMenuExpanded,
                            onDismissRequest = { onVisibilityExpandedChange(false) },
                            properties = PopupProperties(focusable = false)
                        ) {
                            enumValues<MemoVisibility>().forEach { visibility ->
                                DropdownMenuItem(
                                    text = { Text(stringResource(visibility.titleResource)) },
                                    onClick = {
                                        onVisibilitySelected(visibility)
                                        onVisibilityExpandedChange(false)
                                    },
                                    leadingIcon = {
                                        Icon(
                                            visibility.icon,
                                            contentDescription = stringResource(visibility.titleResource)
                                        )
                                    },
                                    trailingIcon = {
                                        if (currentVisibility == visibility) {
                                            Icon(Icons.Outlined.Check, contentDescription = null)
                                        }
                                    }
                                )
                            }
                        }
                        IconButton(onClick = { onVisibilityExpandedChange(!visibilityMenuExpanded) }) {
                            Icon(
                                currentVisibility.icon,
                                contentDescription = stringResource(currentVisibility.titleResource)
                            )
                        }
                    }
                }

                if (tags.isEmpty()) {
                    IconButton(onClick = onHashTagClick) {
                        Icon(Icons.Outlined.Tag, contentDescription = stringResource(R.string.tag))
                    }
                } else {
                    Box {
                        DropdownMenu(
                            expanded = tagMenuExpanded,
                            onDismissRequest = { onTagExpandedChange(false) },
                            properties = PopupProperties(focusable = false)
                        ) {
                            tags.forEach { tag ->
                                DropdownMenuItem(
                                    text = { Text(tag) },
                                    onClick = {
                                        onTagSelected(tag)
                                        onTagExpandedChange(false)
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Outlined.Tag, contentDescription = null)
                                    }
                                )
                            }
                        }
                        IconButton(onClick = { onTagExpandedChange(!tagMenuExpanded) }) {
                            Icon(Icons.Outlined.Tag, contentDescription = stringResource(R.string.tag))
                        }
                    }
                }

                IconButton(onClick = onToggleTodoItem) {
                    Icon(Icons.Outlined.CheckBox, contentDescription = stringResource(R.string.add_task))
                }

                IconButton(onClick = onPickImage) {
                    Icon(Icons.Outlined.Image, contentDescription = stringResource(R.string.add_image))
                }

                IconButton(onClick = onPickAttachment) {
                    Icon(Icons.Outlined.Attachment, contentDescription = stringResource(R.string.attachment))
                }

                IconButton(onClick = onTakePhoto) {
                    Icon(Icons.Outlined.PhotoCamera, contentDescription = stringResource(R.string.take_photo))
                }

                Spacer(modifier = Modifier.size(4.dp))

                FormattingButtons(onFormat = onFormat)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun MemoInputEditor(
    modifier: Modifier = Modifier,
    text: TextFieldValue,
    onTextChange: (TextFieldValue) -> Unit,
    focusRequester: FocusRequester,
    validMimeTypePrefixes: Set<String>,
    onDroppedText: (String) -> Unit,
    uploadResources: List<ResourceEntity>,
    inputViewModel: MemoInputViewModel,
    uploadTasks: List<UploadTaskState>,
    onDismissUploadTask: (String) -> Unit
) {
    val imageResources = remember(uploadResources) {
        uploadResources.filter { it.mimeType?.startsWith("image/") == true }
    }
    val attachmentResources = remember(uploadResources) {
        uploadResources.filterNot { it.mimeType?.startsWith("image/") == true }
    }

    Column(
        modifier
            .fillMaxHeight()
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
        OutlinedTextField(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, bottom = 20.dp)
                .weight(1f)
                .focusRequester(focusRequester),
            value = text,
            label = { Text(R.string.any_thoughts.string) },
            onValueChange = onTextChange,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences)
        )

        if (uploadTasks.isNotEmpty()) {
            LazyRow(
                modifier = Modifier
                    .padding(start = 15.dp, end = 15.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(uploadTasks, key = { it.id }) { task ->
                    UploadTaskItem(task = task, onDismiss = { onDismissUploadTask(task.id) })
                }
            }
        }

        if (imageResources.isNotEmpty()) {
            LazyRow(
                modifier = Modifier
                    .height(80.dp)
                    .padding(
                        start = 15.dp,
                        end = 15.dp,
                        bottom = if (attachmentResources.isEmpty()) 15.dp else 8.dp
                    ),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(imageResources, key = { it.identifier }) { resource ->
                    InputImage(resource = resource, inputViewModel = inputViewModel)
                }
            }
        }

        if (attachmentResources.isNotEmpty()) {
            LazyRow(
                modifier = Modifier
                    .padding(start = 15.dp, end = 15.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(attachmentResources, key = { it.identifier }) { resource ->
                    Attachment(
                        resource = resource,
                        onRemove = { inputViewModel.deleteResource(resource.identifier) }
                    )
                }
            }
        }
    }
}

@Composable
private fun UploadTaskItem(
    task: UploadTaskState,
    onDismiss: () -> Unit
) {
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
                if (task.status == UploadTaskStatus.FAILED) {
                    IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = stringResource(R.string.close)
                        )
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
        title = { Text("Save Changes?") },
        text = { Text("Do you want to save changes before exiting?") },
        confirmButton = {
            Button(onClick = onSave) {
                Text("Save")
            }
        },
        dismissButton = {
            Button(onClick = onDiscard) {
                Text("Discard")
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
