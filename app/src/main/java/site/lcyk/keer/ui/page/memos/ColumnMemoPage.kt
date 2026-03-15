package site.lcyk.keer.ui.page.memos

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.DrawerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import kotlinx.coroutines.launch
import site.lcyk.keer.R
import site.lcyk.keer.ext.getErrorMessage
import site.lcyk.keer.data.local.entity.MemoEntity
import site.lcyk.keer.data.model.MemoVisibility
import site.lcyk.keer.ext.string
import site.lcyk.keer.ui.component.MemosCardActionButton
import site.lcyk.keer.ui.component.SyncAlertDialog
import site.lcyk.keer.ui.component.SyncAlertState
import site.lcyk.keer.ui.component.processManualSyncResult
import site.lcyk.keer.ui.page.common.PageScaffold
import site.lcyk.keer.ui.page.memoinput.QuickMemoComposer
import site.lcyk.keer.util.normalizeTagName
import site.lcyk.keer.viewmodel.LocalMemos
import site.lcyk.keer.viewmodel.LocalUserState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ColumnMemoPage(
    columnId: String,
    drawerState: DrawerState? = null,
    onMenuButtonOpenRequested: (() -> Unit)? = null,
) {
    val listState = rememberLazyListState()
    val memosViewModel = LocalMemos.current
    val userStateViewModel = LocalUserState.current
    val generalSettings by userStateViewModel.generalSettings.collectAsState()
    val personalMemos by memosViewModel.visibleMemos.collectAsState()
    val column = remember(generalSettings, columnId) {
        generalSettings.memoColumns.firstOrNull { column -> column.id == columnId }
    }
    val scope = rememberCoroutineScope()
    val filteredMemos = remember(personalMemos, column) {
        val requiredTags = column?.requiredTags.orEmpty()
        val pinnedMemoIds = column?.pinnedMemoRemoteIds.orEmpty().toSet()
        personalMemos.filter { memo ->
            memo.visibility == MemoVisibility.PRIVATE &&
                memoMatchesColumn(memo, requiredTags)
        }.map { memo ->
            memo.withColumnPinned(pinnedMemoIds.contains(memo.remoteId))
        }
    }
    val expandedFab by remember {
        derivedStateOf { listState.firstVisibleItemIndex == 0 }
    }
    var syncAlert by remember { mutableStateOf<SyncAlertState?>(null) }
    var showQuickComposer by rememberSaveable { mutableStateOf(false) }

    suspend fun requestManualSync() {
        processManualSyncResult(memosViewModel.refreshMemos()) { alert ->
            syncAlert = alert
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        PageScaffold(
            title = column?.name ?: R.string.memo_column.string,
            drawerState = drawerState,
            onMenuButtonOpenRequested = onMenuButtonOpenRequested,
            floatingActionButton = {
                if (column != null && !showQuickComposer) {
                    ExtendedFloatingActionButton(
                        onClick = { showQuickComposer = true },
                        expanded = expandedFab,
                        text = { Text(R.string.new_memo.string) },
                        icon = {
                            Icon(
                                imageVector = Icons.Filled.Add,
                                contentDescription = R.string.compose.string
                            )
                        }
                    )
                }
            }
        ) { innerPadding ->
            if (column == null) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(R.string.column_not_found.string)
                }
            } else {
                MemosList(
                    contentPadding = innerPadding,
                    lazyListState = listState,
                    memos = filteredMemos,
                    onRefresh = { requestManualSync() },
                    actionButton = { memo ->
                        MemosCardActionButton(
                            memo = memo,
                            showPinAction = !memo.remoteId.isNullOrBlank(),
                            onTogglePin = { selectedMemo ->
                                val remoteId = selectedMemo.remoteId?.trim().orEmpty()
                                if (remoteId.isEmpty()) {
                                    syncAlert = SyncAlertState.Failed(R.string.column_pin_requires_sync.string)
                                    return@MemosCardActionButton
                                }
                                scope.launch {
                                    val nextPinnedIds = column.pinnedMemoRemoteIds.toMutableList().apply {
                                        if (selectedMemo.pinned) {
                                            remove(remoteId)
                                        } else if (!contains(remoteId)) {
                                            add(remoteId)
                                        }
                                    }
                                    when (
                                        val response = userStateViewModel.updateMemoColumns(
                                            generalSettings.memoColumns.map { configuredColumn ->
                                                if (configuredColumn.id == column.id) {
                                                    configuredColumn.copy(pinnedMemoRemoteIds = nextPinnedIds)
                                                } else {
                                                    configuredColumn
                                                }
                                            }
                                        )
                                    ) {
                                        is com.skydoves.sandwich.ApiResponse.Success -> Unit
                                        else -> {
                                            syncAlert = SyncAlertState.Failed(
                                                response.getErrorMessage()
                                            )
                                        }
                                    }
                                }
                            }
                        )
                    }
                )
            }
        }

        QuickMemoComposer(
            visible = showQuickComposer && column != null,
            onDismissRequest = { showQuickComposer = false },
            forcedTags = column?.requiredTags.orEmpty()
        )
    }

    SyncAlertDialog(
        alert = syncAlert,
        onDismiss = { syncAlert = null }
    )
}

private fun MemoEntity.withColumnPinned(columnPinned: Boolean): MemoEntity {
    if (pinned == columnPinned) {
        return this
    }
    return copy(pinned = columnPinned).also { copied ->
        copied.resources = resources
        copied.tags = tags
    }
}

private fun memoMatchesColumn(
    memo: MemoEntity,
    requiredTags: List<String>
): Boolean {
    if (requiredTags.isEmpty()) {
        return true
    }
    return requiredTags.all { rawTag ->
        val requiredTag = normalizeTagName(rawTag)
        requiredTag.isNotEmpty() && memo.tags.any { memoTag ->
            memoTag == requiredTag || memoTag.startsWith("$requiredTag/")
        }
    }
}
