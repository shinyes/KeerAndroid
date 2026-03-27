package site.lcyk.keer.ui.page.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import site.lcyk.keer.ui.component.ReorderableSettingsList
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
    val scope = rememberCoroutineScope()
    val memosViewModel = LocalMemos.current
    val userStateViewModel = LocalUserState.current
    val generalSettings by userStateViewModel.generalSettings.collectAsState()
    val columns = generalSettings.memoColumns
    val rawTags by memosViewModel.visibleTags.collectAsState()
    val availableTags = remember(rawTags) {
        rawTags
            .filterNot(::isCollaboratorTag)
            .filterNot(::isQuoteTag)
    }
    val columnNameRequiredMessage = stringResource(R.string.column_name_required)
    val columnTagsRequiredMessage = stringResource(R.string.column_tags_required)

    var editingColumnId by rememberSaveable { mutableStateOf<String?>(null) }
    var draftName by rememberSaveable { mutableStateOf("") }
    var draftTags by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var draftVisibleInDrawer by rememberSaveable { mutableStateOf(true) }
    var showColumnEditor by rememberSaveable { mutableStateOf(false) }
    var showTagSelector by rememberSaveable { mutableStateOf(false) }
    var editorError by rememberSaveable { mutableStateOf<String?>(null) }
    var actionColumnId by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteColumnId by rememberSaveable { mutableStateOf<String?>(null) }
    var sortMode by rememberSaveable { mutableStateOf(false) }
    var draftColumns by remember { mutableStateOf(columns) }

    fun openEditor(column: MemoColumnConfig?) {
        editingColumnId = column?.id
        draftName = column?.name.orEmpty()
        draftTags = normalizeTagList(column?.requiredTags.orEmpty())
        draftVisibleInDrawer = column?.visibleInDrawer ?: true
        editorError = null
        showColumnEditor = true
    }

    fun exitSortMode() {
        draftColumns = columns
        sortMode = false
    }

    fun enterSortMode() {
        showColumnEditor = false
        showTagSelector = false
        actionColumnId = null
        deleteColumnId = null
        editorError = null
        draftColumns = columns
        sortMode = true
    }

    suspend fun saveDraftColumnOrder() {
        userStateViewModel.updateMemoColumns(draftColumns)
        sortMode = false
    }

    LaunchedEffect(columns, sortMode) {
        if (!sortMode) {
            draftColumns = columns
        }
    }

    BackHandler(enabled = sortMode) {
        exitSortMode()
    }

    PageScaffold(
        title = R.string.column_config.string,
        onBack = {
            if (sortMode) {
                exitSortMode()
            } else {
                navController.popBackStackIfLifecycleIsResumed(lifecycleOwner)
            }
        },
        actions = {
            if (!sortMode) {
                IconButton(onClick = { openEditor(column = null) }) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = R.string.add_column.string
                    )
                }
            }
        },
        floatingActionButton = {
            if (columns.isEmpty() && !sortMode) {
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
            } else {
                ExtendedFloatingActionButton(
                    onClick = {
                        scope.launch {
                            if (sortMode) {
                                saveDraftColumnOrder()
                            } else {
                                enterSortMode()
                            }
                        }
                    },
                    text = {
                        Text(
                            if (sortMode) {
                                R.string.save_order.string
                            } else {
                                R.string.sort_order.string
                            }
                        )
                    },
                    icon = {
                        Icon(
                            imageVector = if (sortMode) {
                                Icons.Outlined.Check
                            } else {
                                Icons.Rounded.DragHandle
                            },
                            contentDescription = if (sortMode) {
                                R.string.save_order.string
                            } else {
                                R.string.sort_order.string
                            }
                        )
                    }
                )
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            Text(
                text = if (sortMode) {
                    R.string.reorder_columns_hint.string
                } else {
                    R.string.column_config_hint.string
                },
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (columns.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = R.string.no_columns.string,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else if (sortMode) {
                ReorderableSettingsList(
                    modifier = Modifier.fillMaxSize(),
                    items = draftColumns,
                    key = { it.id },
                    contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp),
                    onMove = { fromIndex, toIndex ->
                        draftColumns = draftColumns.moveItem(fromIndex, toIndex)
                    },
                ) { column, _ ->
                    MemoColumnItem(
                        column = column,
                        enabled = false,
                        showDragHandle = true,
                        dragHandleModifier = with(this) { Modifier.draggableHandle() }
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(vertical = 4.dp)
                ) {
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
                                editorError = columnNameRequiredMessage
                            }
                            normalizedTags.isEmpty() -> {
                                editorError = columnTagsRequiredMessage
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
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    onLongPress: (() -> Unit)? = null,
    onVisibleChange: ((Boolean) -> Unit)? = null,
    showDragHandle: Boolean = false,
    dragHandleModifier: Modifier = Modifier,
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
                    enabled = enabled && (onClick != null || onLongPress != null),
                    onClick = { onClick?.invoke() },
                    onLongClick = { onLongPress?.invoke() }
                )
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Outlined.Bookmarks,
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
            if (showDragHandle) {
                IconButton(
                    modifier = dragHandleModifier,
                    onClick = {}
                ) {
                    Icon(
                        imageVector = Icons.Rounded.DragHandle,
                        contentDescription = R.string.sort_order.string,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                Switch(
                    checked = column.visibleInDrawer,
                    onCheckedChange = if (enabled) onVisibleChange else null
                )
            }
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

private fun List<MemoColumnConfig>.moveItem(fromIndex: Int, toIndex: Int): List<MemoColumnConfig> {
    if (fromIndex == toIndex || fromIndex !in indices || toIndex !in indices) {
        return this
    }
    return toMutableList().apply {
        add(toIndex, removeAt(fromIndex))
    }
}
