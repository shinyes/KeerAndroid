package site.lcyk.keer.ui.page.memos

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavHostController
import kotlinx.coroutines.launch
import site.lcyk.keer.R
import site.lcyk.keer.data.local.entity.MemoEntity
import site.lcyk.keer.data.model.Account
import site.lcyk.keer.data.model.CachedMemoItem
import site.lcyk.keer.data.model.MemoVisibility
import site.lcyk.keer.data.model.PendingGroupMemo
import site.lcyk.keer.data.model.Settings
import site.lcyk.keer.data.model.toMemo
import site.lcyk.keer.ext.popBackStackIfLifecycleIsResumed
import site.lcyk.keer.ext.settingsDataStore
import site.lcyk.keer.ext.string
import site.lcyk.keer.ui.component.CollaboratorAvatarStack
import site.lcyk.keer.ui.component.CollaboratorListDialog
import site.lcyk.keer.ui.component.KeerTagChip
import site.lcyk.keer.ui.component.MemoContent
import site.lcyk.keer.ui.component.MemosCardActionButton
import site.lcyk.keer.ui.page.common.navigateToTagPage
import site.lcyk.keer.ui.page.common.RouteName
import site.lcyk.keer.util.extractCollaboratorIds
import site.lcyk.keer.util.isCollaboratorTag
import site.lcyk.keer.util.normalizeTagList
import site.lcyk.keer.viewmodel.LocalMemos
import site.lcyk.keer.viewmodel.LocalUserState
import java.net.URLEncoder
import java.time.Instant

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoDetailPage(
    navController: NavHostController,
    memoIdentifier: String
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val memosViewModel = LocalMemos.current
    val userStateViewModel = LocalUserState.current
    val currentAccount by userStateViewModel.currentAccount.collectAsState()
    val collaboratorProfiles by userStateViewModel.collaboratorProfiles.collectAsState()
    val settings by context.settingsDataStore.data.collectAsState(initial = Settings())
    val scope = rememberCoroutineScope()
    val localMemo = remember(memosViewModel.memos.toList(), memoIdentifier) {
        memosViewModel.memos.firstOrNull { it.identifier == memoIdentifier }
    }
    val fallbackMemo = remember(memoIdentifier, settings.currentUser, settings.usersList) {
        resolveFallbackMemoEntity(
            settings = settings,
            memoIdentifier = memoIdentifier
        )
    }
    val memo = localMemo ?: fallbackMemo
    val readOnlyMemoDetail = remember(memoIdentifier) {
        memoIdentifier.startsWith(EXPLORE_MEMO_PREFIX) || memoIdentifier.startsWith(GROUP_MEMO_PREFIX)
    }
    val collaboratorIds = remember(memo?.tags) { extractCollaboratorIds(memo?.tags.orEmpty()) }
    val displayTags = remember(memo?.tags) {
        normalizeTagList(memo?.tags.orEmpty().filterNot(::isCollaboratorTag))
    }
    var hadMemo by rememberSaveable(memoIdentifier) { mutableStateOf(false) }
    var showCollaboratorDialog by remember { mutableStateOf(false) }

    LaunchedEffect(memo?.identifier) {
        when {
            memo != null -> hadMemo = true
            hadMemo -> navController.popBackStackIfLifecycleIsResumed(lifecycleOwner)
        }
    }

    LaunchedEffect(collaboratorIds) {
        if (collaboratorIds.isNotEmpty()) {
            userStateViewModel.prefetchCollaboratorAvatars(collaboratorIds)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = R.string.memo.string) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStackIfLifecycleIsResumed(lifecycleOwner) }) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = R.string.back.string)
                    }
                },
                actions = {
                    if (!readOnlyMemoDetail) {
                        memo?.let { MemosCardActionButton(it) }
                    }
                }
            )
        }
    ) { innerPadding ->
        if (memo == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                Text(text = R.string.memo_not_found.string)
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            Row(
                modifier = Modifier
                    .padding(start = 15.dp, top = 10.dp, end = 15.dp)
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
                                onClick = {
                                    navController.navigateToTagPage(tag)
                                }
                            )
                        }
                    }
                } else {
                    Spacer(modifier = Modifier.weight(1f))
                }
                if (currentAccount !is Account.Local && memo.needsSync) {
                    Icon(
                        imageVector = Icons.Outlined.CloudOff,
                        contentDescription = R.string.memo_sync_pending.string,
                        modifier = Modifier
                            .padding(start = 5.dp)
                            .size(20.dp),
                    )
                }
            }

            MemoContent(
                memo = memo,
                selectable = true,
                checkboxChange = { checked, startOffset, endOffset ->
                    if (!readOnlyMemoDetail) {
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
                    }
                }
            )
        }

        if (showCollaboratorDialog) {
            CollaboratorListDialog(
                collaboratorIds = collaboratorIds,
                collaboratorProfiles = collaboratorProfiles,
                onDismiss = { showCollaboratorDialog = false }
            )
        }
    }
}

private data class ParsedGroupMemoIdentifier(
    val groupId: String,
    val memoRemoteId: String
)

private fun resolveFallbackMemoEntity(
    settings: Settings,
    memoIdentifier: String
): MemoEntity? {
    val userSettings = settings.usersList
        .firstOrNull { user -> user.accountKey == settings.currentUser }
        ?.settings
        ?: return null
    val accountKey = settings.currentUser.ifBlank { "cached" }

    if (memoIdentifier.startsWith(EXPLORE_MEMO_PREFIX)) {
        val remoteId = memoIdentifier.removePrefix(EXPLORE_MEMO_PREFIX).trim()
        if (remoteId.isEmpty()) {
            return null
        }
        return userSettings.cachedExploreMemos
            .firstOrNull { memo -> memo.remoteId == remoteId }
            ?.toMemoEntity(
                identifier = memoIdentifier,
                accountKey = accountKey
            )
    }

    if (memoIdentifier.startsWith(GROUP_MEMO_PREFIX)) {
        val parsed = parseGroupMemoIdentifier(memoIdentifier) ?: return null
        val pinned = userSettings.pinnedGroupMemoKeys.contains(
            groupMemoKey(parsed.groupId, parsed.memoRemoteId)
        )

        if (parsed.memoRemoteId.startsWith(LOCAL_GROUP_MESSAGE_PREFIX)) {
            val localId = parsed.memoRemoteId.removePrefix(LOCAL_GROUP_MESSAGE_PREFIX).trim()
            if (localId.isNotEmpty()) {
                userSettings.pendingGroupMemos
                    .firstOrNull { memo ->
                        memo.groupId == parsed.groupId && memo.localId == localId
                    }
                    ?.let { pendingMemo ->
                        return pendingMemo.toMemoEntity(
                            identifier = memoIdentifier,
                            remoteId = parsed.memoRemoteId,
                            accountKey = accountKey,
                            pinned = pinned
                        )
                    }
            }
        }

        return userSettings.cachedGroupMemos
            .firstOrNull { memo ->
                memo.groupId == parsed.groupId && memo.remoteId == parsed.memoRemoteId
            }
            ?.toMemoEntity(
                identifier = memoIdentifier,
                accountKey = accountKey,
                pinnedOverride = pinned
            )
    }

    return null
}

private fun parseGroupMemoIdentifier(memoIdentifier: String): ParsedGroupMemoIdentifier? {
    if (!memoIdentifier.startsWith(GROUP_MEMO_PREFIX)) {
        return null
    }
    val payload = memoIdentifier.removePrefix(GROUP_MEMO_PREFIX)
    val parts = payload.split(":", limit = 2)
    if (parts.size != 2) {
        return null
    }
    val groupId = parts[0].trim()
    val memoRemoteId = parts[1].trim()
    if (groupId.isEmpty() || memoRemoteId.isEmpty()) {
        return null
    }
    return ParsedGroupMemoIdentifier(
        groupId = groupId,
        memoRemoteId = memoRemoteId
    )
}

private fun groupMemoKey(groupId: String, memoRemoteId: String): String {
    return "$groupId|$memoRemoteId"
}

private fun CachedMemoItem.toMemoEntity(
    identifier: String,
    accountKey: String,
    pinnedOverride: Boolean? = null
): MemoEntity {
    val memo = toMemo()
    val syncedAt = memo.updatedAt ?: memo.date
    val entity = MemoEntity(
        identifier = identifier,
        remoteId = memo.remoteId,
        accountKey = accountKey,
        content = memo.content,
        date = memo.date,
        visibility = memo.visibility,
        pinned = pinnedOverride ?: memo.pinned,
        archived = memo.archived,
        latitude = memo.latitude,
        longitude = memo.longitude,
        needsSync = false,
        isDeleted = false,
        lastModified = syncedAt,
        lastSyncedAt = syncedAt
    )
    entity.tags = memo.tags
    return entity
}

private fun PendingGroupMemo.toMemoEntity(
    identifier: String,
    remoteId: String,
    accountKey: String,
    pinned: Boolean
): MemoEntity {
    val createdAt = Instant.ofEpochMilli(createdAtEpochMillis)
    val entity = MemoEntity(
        identifier = identifier,
        remoteId = remoteId,
        accountKey = accountKey,
        content = content,
        date = createdAt,
        visibility = MemoVisibility.PROTECTED,
        pinned = pinned,
        archived = false,
        needsSync = true,
        isDeleted = false,
        lastModified = createdAt,
        lastSyncedAt = null
    )
    entity.tags = normalizeTagList(tags)
    return entity
}

private const val EXPLORE_MEMO_PREFIX = "explore:"
private const val GROUP_MEMO_PREFIX = "group:"
private const val LOCAL_GROUP_MESSAGE_PREFIX = "local:"
