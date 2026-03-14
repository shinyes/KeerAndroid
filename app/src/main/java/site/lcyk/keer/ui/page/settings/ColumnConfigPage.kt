package site.lcyk.keer.ui.page.settings

import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavHostController
import kotlinx.coroutines.launch
import site.lcyk.keer.R
import site.lcyk.keer.data.model.MemoColumnConfig
import site.lcyk.keer.ext.popBackStackIfLifecycleIsResumed
import site.lcyk.keer.ext.string
import site.lcyk.keer.ui.component.KeerRemovableTagChip
import site.lcyk.keer.ui.page.common.PageScaffold
import site.lcyk.keer.ui.page.memoinput.MemoTagSelectorDialog
import site.lcyk.keer.util.isCollaboratorTag
import site.lcyk.keer.util.isQuoteTag
import site.lcyk.keer.util.normalizeTagList
import site.lcyk.keer.viewmodel.LocalMemos
import site.lcyk.keer.viewmodel.LocalUserState
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ColumnConfigPage(
    navController: NavHostController,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val memosViewModel = LocalMemos.current
    val userStateViewModel = LocalUserState.current
    val generalSettings by userStateViewModel.generalSettings.collectAsState()
    val columns = generalSettings.memoColumns
    val rawTags = memosViewModel.tags.toList()
    val availableTags = remember(rawTags) {
        rawTags
            .filterNot(::isCollaboratorTag)
            .filterNot(::isQuoteTag)
    }

    var editingColumnId by rememberSaveable { mutableStateOf<String?>(null) }
    var draftName by rememberSaveable { mutableStateOf("") }
    var draftTags by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var draftVisibleInDrawer by rememberSaveable { mutableStateOf(true) }
    var showColumnEditor by rememberSaveable { mutableStateOf(false) }
    var showTagSelector by rememberSaveable { mutableStateOf(false) }
    var editorError by rememberSaveable { mutableStateOf<String?>(null) }
    var actionColumnId by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteColumnId by rememberSaveable { mutableStateOf<String?>(null) }

    fun openEditor(column: MemoColumnConfig?) {
        editingColumnId = column?.id
        draftName = column?.name.orEmpty()
        draftTags = normalizeTagList(column?.requiredTags.orEmpty())
        draftVisibleInDrawer = column?.visibleInDrawer ?: true
        editorError = null
        showColumnEditor = true
    }

    PageScaffold(
        title = R.string.column_config.string,
        onBack = { navController.popBackStackIfLifecycleIsResumed(lifecycleOwner) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { openEditor(column = null) },
                text = { Text(R.string.add_column.string) },
                icon = {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = R.string.add_column.string
                    )
                }
            )
        }
    ) { innerPadding ->
        if (columns.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = R.string.no_columns.string,
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
                        text = R.string.column_config_hint.string,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                items(columns, key = { it.id }) { column ->
                    MemoColumnItem(
                        column = column,
                        onClick = { openEditor(column) },
                        onLongPress = { actionColumnId = column.id },
                        onVisibleChange = { visible ->
                            scope.launch {
                                userStateViewModel.updateMemoColumns(
                                    columns.withColumnVisibility(column.id, visible)
                                )
                            }
                        }
                    )
                }
            }
        }
    }

    if (showColumnEditor) {
        AlertDialog(
            onDismissRequest = {
                showColumnEditor = false
                showTagSelector = false
            },
            title = {
                Text(
                    if (editingColumnId == null) {
                        R.string.add_column.string
                    } else {
                        R.string.edit_column.string
                    }
                )
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedTextField(
                        value = draftName,
                        onValueChange = {
                            draftName = it
                            editorError = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(R.string.column_name.string) },
                        singleLine = true
                    )
                    Surface(
                        onClick = { showTagSelector = true },
                        shape = RoundedCornerShape(16.dp),
                        tonalElevation = 1.dp
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = R.string.column_tags.string,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (draftTags.isEmpty()) {
                                Text(
                                    text = R.string.select_column_tags.string,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else {
                                LazyRow(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    items(draftTags, key = { it }) { tag ->
                                        KeerRemovableTagChip(
                                            tag = tag,
                                            onRemove = {
                                                draftTags = normalizeTagList(
                                                    draftTags.filterNot { existing -> existing == tag }
                                                )
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = R.string.show_in_drawer.string,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Switch(
                            checked = draftVisibleInDrawer,
                            onCheckedChange = { checked -> draftVisibleInDrawer = checked }
                        )
                    }
                    editorError?.let { message ->
                        Text(
                            text = message,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val normalizedName = draftName.trim()
                        val normalizedTags = normalizeTagList(draftTags)
                        when {
                            normalizedName.isBlank() -> {
                                editorError = context.getString(R.string.column_name_required)
                            }
                            normalizedTags.isEmpty() -> {
                                editorError = context.getString(R.string.column_tags_required)
                            }
                            else -> {
                                scope.launch {
                                    val savedColumn = MemoColumnConfig(
                                        id = editingColumnId ?: UUID.randomUUID().toString(),
                                        name = normalizedName,
                                        requiredTags = normalizedTags,
                                        visibleInDrawer = draftVisibleInDrawer
                                    )
                                    userStateViewModel.updateMemoColumns(
                                        columns.upsertColumn(savedColumn)
                                    )
                                }
                                showColumnEditor = false
                                showTagSelector = false
                            }
                        }
                    }
                ) {
                    Text(R.string.confirm.string)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showColumnEditor = false
                        showTagSelector = false
                    }
                ) {
                    Text(R.string.cancel.string)
                }
            }
        )
    }

    if (showColumnEditor && showTagSelector) {
        MemoTagSelectorDialog(
            availableTags = availableTags,
            selectedTags = draftTags,
            onSelectedTagsChange = { draftTags = normalizeTagList(it) },
            onDismiss = { showTagSelector = false }
        )
    }

    actionColumnId?.let { columnId ->
        val actionColumn = columns.firstOrNull { it.id == columnId }
        if (actionColumn != null) {
            val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
            ModalBottomSheet(
                onDismissRequest = { actionColumnId = null },
                sheetState = sheetState
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = actionColumn.name,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Surface(
                        onClick = {
                            deleteColumnId = actionColumn.id
                            actionColumnId = null
                        },
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.28f)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Delete,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error
                            )
                            Spacer(modifier = Modifier.size(10.dp))
                            Text(
                                text = R.string.delete_column.string,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }
                    TextButton(
                        onClick = { actionColumnId = null },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(R.string.cancel.string)
                    }
                }
            }
        }
    }

    deleteColumnId?.let { columnId ->
        val deleteColumn = columns.firstOrNull { it.id == columnId }
        if (deleteColumn != null) {
            AlertDialog(
                onDismissRequest = { deleteColumnId = null },
                title = { Text(R.string.delete_column.string) },
                text = {
                    Text(
                        text = stringResource(
                            R.string.delete_column_confirm,
                            deleteColumn.name
                        )
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            scope.launch {
                                userStateViewModel.updateMemoColumns(
                                    columns.removeColumn(deleteColumn.id)
                                )
                            }
                            deleteColumnId = null
                        }
                    ) {
                        Text(R.string.delete.string)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { deleteColumnId = null }) {
                        Text(R.string.cancel.string)
                    }
                }
            )
        }
    }

    LaunchedEffect(Unit) {
        memosViewModel.loadTags()
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MemoColumnItem(
    column: MemoColumnConfig,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    onVisibleChange: (Boolean) -> Unit,
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
                    onClick = onClick,
                    onLongClick = onLongPress
                )
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Outlined.GridView,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.size(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = column.name,
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.size(4.dp))
                Text(
                    text = column.requiredTags.joinToString("  ") { "#$it" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = column.visibleInDrawer,
                onCheckedChange = onVisibleChange
            )
        }
    }
}

private fun List<MemoColumnConfig>.withColumnVisibility(
    columnId: String,
    visible: Boolean
): List<MemoColumnConfig> {
    return map { column ->
        if (column.id == columnId) {
            column.copy(visibleInDrawer = visible)
        } else {
            column
        }
    }
}

private fun List<MemoColumnConfig>.upsertColumn(
    column: MemoColumnConfig
): List<MemoColumnConfig> {
    val index = indexOfFirst { it.id == column.id }
    return if (index == -1) {
        this + column
    } else {
        mapIndexed { itemIndex, existing ->
            if (itemIndex == index) column else existing
        }
    }
}

private fun List<MemoColumnConfig>.removeColumn(columnId: String): List<MemoColumnConfig> {
    return filterNot { it.id == columnId }
}
