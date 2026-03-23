package site.lcyk.keer.ui.page.settings

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavHostController
import kotlinx.coroutines.launch
import site.lcyk.keer.R
import site.lcyk.keer.data.model.isTagVisibleInDrawer
import site.lcyk.keer.ext.getErrorMessage
import site.lcyk.keer.ext.popBackStackIfLifecycleIsResumed
import site.lcyk.keer.ext.string
import site.lcyk.keer.ui.page.common.PageScaffold
import site.lcyk.keer.util.isCollaboratorTag
import site.lcyk.keer.util.isQuoteTag
import site.lcyk.keer.util.isValidTagName
import site.lcyk.keer.util.normalizeTagList
import site.lcyk.keer.util.normalizeTagName
import site.lcyk.keer.viewmodel.LocalMemos
import site.lcyk.keer.viewmodel.LocalUserState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TagConfigPage(
    navController: NavHostController,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val memosViewModel = LocalMemos.current
    val userStateViewModel = LocalUserState.current
    val generalSettings by userStateViewModel.generalSettings.collectAsState()
    val rawTags by memosViewModel.visibleTags.collectAsState()
    val availableTags = remember(rawTags) {
        normalizeTagList(
            rawTags
                .filterNot(::isCollaboratorTag)
                .filterNot(::isQuoteTag)
        )
    }

    var activeTagActionTarget by remember { mutableStateOf<String?>(null) }
    var renameTargetTag by remember { mutableStateOf<String?>(null) }
    var renameValue by remember { mutableStateOf("") }
    var deleteTargetTag by remember { mutableStateOf<String?>(null) }
    var confirmDeleteAndMemosTargetTag by remember { mutableStateOf<String?>(null) }
    var confirmDeleteAndMemosInput by remember { mutableStateOf("") }
    var tagActionErrorMessage by remember { mutableStateOf<String?>(null) }
    var tagActionInProgress by remember { mutableStateOf(false) }

    PageScaffold(
        title = R.string.tag_config.string,
        onBack = { navController.popBackStackIfLifecycleIsResumed(lifecycleOwner) },
    ) { innerPadding ->
        if (availableTags.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = R.string.no_tags_for_config.string,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    Text(
                        text = R.string.tag_config_hint.string,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                items(availableTags, key = { it }) { tag ->
                    TagConfigItem(
                        tag = tag,
                        visibleInDrawer = generalSettings.isTagVisibleInDrawer(tag),
                        onVisibleChange = { visible ->
                            scope.launch {
                                userStateViewModel.updateTagDrawerVisibility(
                                    tag = tag,
                                    visibleInDrawer = visible,
                                )
                            }
                        },
                        onLongPress = { activeTagActionTarget = tag }
                    )
                }
            }
        }
    }

    activeTagActionTarget?.let { targetTag ->
        val tagMenuSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = {
                if (!tagActionInProgress) {
                    activeTagActionTarget = null
                }
            },
            sheetState = tagMenuSheetState
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = R.string.tag_actions.string,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TagConfigActionMenuItem(
                    title = stringResource(R.string.rename_tag),
                    enabled = !tagActionInProgress,
                    onClick = {
                        renameTargetTag = targetTag
                        renameValue = targetTag
                        activeTagActionTarget = null
                    }
                )
                TagConfigActionMenuItem(
                    title = stringResource(R.string.delete_tag),
                    enabled = !tagActionInProgress,
                    destructive = true,
                    onClick = {
                        deleteTargetTag = targetTag
                        activeTagActionTarget = null
                    }
                )
                TextButton(
                    enabled = !tagActionInProgress,
                    onClick = { activeTagActionTarget = null },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(R.string.cancel.string)
                }
            }
        }
    }

    renameTargetTag?.let { sourceTag ->
        AlertDialog(
            onDismissRequest = {
                if (!tagActionInProgress) {
                    renameTargetTag = null
                }
            },
            title = { Text(R.string.rename_tag.string) },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedTextField(
                        value = renameValue,
                        onValueChange = { renameValue = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(R.string.new_tag_name.string) },
                        singleLine = true,
                        enabled = !tagActionInProgress
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !tagActionInProgress,
                    onClick = {
                        val normalizedSourceTag = normalizeTagName(sourceTag)
                        val normalizedNewTag = normalizeTagName(renameValue)
                        if (normalizedSourceTag.isEmpty() || normalizedNewTag.isEmpty() || !isValidTagName(normalizedNewTag)) {
                            tagActionErrorMessage = R.string.invalid_tag_name.string
                            return@TextButton
                        }
                        scope.launch {
                            tagActionInProgress = true
                            val response = memosViewModel.renameTag(normalizedSourceTag, normalizedNewTag)
                            tagActionInProgress = false
                            if (response is com.skydoves.sandwich.ApiResponse.Success) {
                                renameTargetTag = null
                                renameValue = ""
                            } else {
                                tagActionErrorMessage = response.getErrorMessage()
                            }
                        }
                    }
                ) {
                    Text(R.string.confirm.string)
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !tagActionInProgress,
                    onClick = { renameTargetTag = null }
                ) {
                    Text(R.string.cancel.string)
                }
            }
        )
    }

    deleteTargetTag?.let { targetTag ->
        AlertDialog(
            onDismissRequest = {
                if (!tagActionInProgress) {
                    deleteTargetTag = null
                }
            },
            title = { Text(R.string.delete_tag.string) },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    TagConfigActionMenuItem(
                        title = stringResource(R.string.delete_tag_and_memos),
                        enabled = !tagActionInProgress,
                        destructive = true,
                        onClick = {
                            val normalizedTag = normalizeTagName(targetTag)
                            if (normalizedTag.isEmpty()) {
                                tagActionErrorMessage = R.string.invalid_tag_name.string
                                return@TagConfigActionMenuItem
                            }
                            confirmDeleteAndMemosTargetTag = normalizedTag
                            confirmDeleteAndMemosInput = ""
                            deleteTargetTag = null
                        }
                    )
                    TagConfigActionMenuItem(
                        title = stringResource(R.string.delete_tag_only),
                        enabled = !tagActionInProgress,
                        destructive = false,
                        onClick = {
                            val normalizedTag = normalizeTagName(targetTag)
                            if (normalizedTag.isEmpty()) {
                                tagActionErrorMessage = R.string.invalid_tag_name.string
                                return@TagConfigActionMenuItem
                            }
                            scope.launch {
                                tagActionInProgress = true
                                val response = memosViewModel.deleteTag(normalizedTag, deleteAssociatedMemos = false)
                                tagActionInProgress = false
                                if (response is com.skydoves.sandwich.ApiResponse.Success) {
                                    deleteTargetTag = null
                                } else {
                                    tagActionErrorMessage = response.getErrorMessage()
                                }
                            }
                        }
                    )
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(
                    enabled = !tagActionInProgress,
                    onClick = { deleteTargetTag = null }
                ) {
                    Text(R.string.cancel.string)
                }
            }
        )
    }

    confirmDeleteAndMemosTargetTag?.let { targetTag ->
        val canConfirmDelete = normalizeTagName(confirmDeleteAndMemosInput) == targetTag
        AlertDialog(
            onDismissRequest = {
                if (!tagActionInProgress) {
                    confirmDeleteAndMemosTargetTag = null
                    confirmDeleteAndMemosInput = ""
                }
            },
            title = { Text(R.string.delete_tag_and_memos.string) },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = stringResource(R.string.delete_tag_and_memos_confirm_hint, targetTag),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    OutlinedTextField(
                        value = confirmDeleteAndMemosInput,
                        onValueChange = { confirmDeleteAndMemosInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(R.string.delete_tag_and_memos_confirm_label.string) },
                        singleLine = true,
                        enabled = !tagActionInProgress
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !tagActionInProgress && canConfirmDelete,
                    onClick = {
                        scope.launch {
                            tagActionInProgress = true
                            val response = memosViewModel.deleteTag(targetTag, deleteAssociatedMemos = true)
                            tagActionInProgress = false
                            if (response is com.skydoves.sandwich.ApiResponse.Success) {
                                confirmDeleteAndMemosTargetTag = null
                                confirmDeleteAndMemosInput = ""
                            } else {
                                tagActionErrorMessage = response.getErrorMessage()
                            }
                        }
                    }
                ) {
                    Text(R.string.confirm.string)
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !tagActionInProgress,
                    onClick = {
                        confirmDeleteAndMemosTargetTag = null
                        confirmDeleteAndMemosInput = ""
                    }
                ) {
                    Text(R.string.cancel.string)
                }
            }
        )
    }

    tagActionErrorMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { tagActionErrorMessage = null },
            title = { Text(R.string.tag_action_failed.string) },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { tagActionErrorMessage = null }) {
                    Text(R.string.confirm.string)
                }
            }
        )
    }

    LaunchedEffect(Unit) {
        memosViewModel.loadTags()
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TagConfigItem(
    tag: String,
    visibleInDrawer: Boolean,
    onVisibleChange: (Boolean) -> Unit,
    onLongPress: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(18.dp),
        tonalElevation = 1.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = { onVisibleChange(!visibleInDrawer) },
                    onLongClick = onLongPress
                )
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Outlined.Tag,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.size(14.dp))
            Text(
                text = tag,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge
            )
            Switch(
                checked = visibleInDrawer,
                onCheckedChange = onVisibleChange
            )
        }
    }
}

private val TagConfigActionItemShape = RoundedCornerShape(12.dp)
private const val TagConfigActionItemContainerAlpha = 0.45f

@Composable
private fun TagConfigActionMenuItem(
    title: String,
    enabled: Boolean,
    destructive: Boolean = false,
    onClick: () -> Unit
) {
    val containerColor = if (destructive) {
        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.22f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = TagConfigActionItemContainerAlpha)
    }
    val contentColor = if (destructive) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(TagConfigActionItemShape)
            .background(containerColor)
            .clickable(enabled = enabled, onClick = onClick)
            .heightIn(min = 44.dp)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (destructive) {
            Icon(
                imageVector = Icons.Outlined.Delete,
                contentDescription = null,
                tint = if (enabled) contentColor else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.size(8.dp))
        }
        Text(
            text = title,
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.bodyMedium,
            color = if (enabled) {
                contentColor
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
    }
}
