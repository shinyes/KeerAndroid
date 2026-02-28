package site.lcyk.keer.ui.component

import android.text.format.DateUtils
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import site.lcyk.keer.data.model.Memo
import site.lcyk.keer.util.extractCollaboratorIds
import site.lcyk.keer.util.isCollaboratorTag
import site.lcyk.keer.util.normalizeTagList
import site.lcyk.keer.viewmodel.LocalUserState

@Composable
fun ExploreMemoCard(
    memo: Memo,
    onTagClick: ((String) -> Unit)? = null
) {
    val userStateViewModel = LocalUserState.current
    val collaboratorProfiles by userStateViewModel.collaboratorProfiles.collectAsState()
    val displayTags = remember(memo.tags) {
        normalizeTagList(memo.tags.filterNot(::isCollaboratorTag))
    }
    val collaboratorIds = remember(memo.tags) { extractCollaboratorIds(memo.tags) }
    var showCollaboratorDialog by remember { mutableStateOf(false) }

    LaunchedEffect(collaboratorIds) {
        if (collaboratorIds.isNotEmpty()) {
            userStateViewModel.prefetchCollaboratorAvatars(collaboratorIds)
        }
    }

    Card(
        modifier = Modifier
            .padding(horizontal = 15.dp, vertical = 10.dp)
            .fillMaxWidth(),
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
                    text = DateUtils.getRelativeTimeSpanString(
                        memo.date.toEpochMilli(),
                        System.currentTimeMillis(),
                        DateUtils.SECOND_IN_MILLIS
                    ).toString(),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.outline
                )

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

                val creatorName = memo.creator?.name.orEmpty()
                if (creatorName.isNotBlank()) {
                    Text(
                        text = "@$creatorName",
                        modifier = Modifier.padding(start = 10.dp, end = 15.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }

            MemoContent(
                memo = memo,
                previewMode = false,
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
