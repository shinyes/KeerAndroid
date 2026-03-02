package site.lcyk.keer.ui.component

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import com.skydoves.sandwich.suspendOnSuccess
import kotlinx.coroutines.launch
import site.lcyk.keer.R
import site.lcyk.keer.data.local.entity.MemoEntity
import site.lcyk.keer.ext.string
import site.lcyk.keer.util.normalizeTagList
import site.lcyk.keer.viewmodel.LocalArchivedMemos
import site.lcyk.keer.viewmodel.LocalMemos

@Composable
fun ArchivedMemoCard(
    memo: MemoEntity
) {
    val displayTags = remember(memo.tags) { normalizeTagList(memo.tags) }

    Card(
        modifier = Modifier
            .padding(horizontal = 15.dp, vertical = 10.dp)
            .fillMaxWidth()
    ) {
        Column {
            Row(
                modifier = Modifier.padding(start = 15.dp).fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    DateUtils.getRelativeTimeSpanString(memo.date.toEpochMilli(), System.currentTimeMillis(), DateUtils.SECOND_IN_MILLIS).toString(),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.outline
                )
                if (displayTags.isNotEmpty()) {
                    LazyRow(
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 8.dp, end = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        items(displayTags, key = { it }) { tag ->
                            KeerTagChip(tag = tag)
                        }
                    }
                } else {
                    Spacer(modifier = Modifier.weight(1f))
                }
                ArchivedMemosCardActionButton(memo)
            }

            MemoContent(memo, previewMode = false)
        }
    }
}

@Composable
fun ArchivedMemosCardActionButton(
    memo: MemoEntity
) {
    val scope = rememberCoroutineScope()
    val archivedMemoListViewModel = LocalArchivedMemos.current
    val memosViewModel = LocalMemos.current
    val hapticFeedback = LocalHapticFeedback.current
    val actions = buildList {
        add(
            MemoMenuAction(
                key = "restore",
                label = R.string.restore.string,
                icon = Icons.Outlined.Restore,
                onSelected = {
                    scope.launch {
                        archivedMemoListViewModel.restoreMemo(memo.identifier).suspendOnSuccess {
                            memosViewModel.loadMemos()
                        }
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
                    hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                    scope.launch {
                        archivedMemoListViewModel.deleteMemo(memo.identifier)
                    }
                }
            )
        )
    }
    Box(
        modifier = Modifier
            .fillMaxSize(),
        contentAlignment = Alignment.TopEnd
    ) {
        MemoActionMenuButton(actions = actions)
    }
}
