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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavHostController
import kotlinx.coroutines.launch
import site.lcyk.keer.R
import site.lcyk.keer.data.local.entity.MemoEntity
import site.lcyk.keer.data.model.Account
import site.lcyk.keer.data.model.Settings
import site.lcyk.keer.ext.popBackStackIfLifecycleIsResumed
import site.lcyk.keer.ext.settingsDataStore
import site.lcyk.keer.ext.string
import site.lcyk.keer.ui.component.CollaboratorAvatarStack
import site.lcyk.keer.ui.component.CollaboratorListDialog
import site.lcyk.keer.ui.component.KeerTagChip
import site.lcyk.keer.ui.component.MemoContent
import site.lcyk.keer.ui.component.MemoQuoteReferenceCard
import site.lcyk.keer.ui.component.MemosCardActionButton
import site.lcyk.keer.ui.page.common.navigateToMemoDetailPage
import site.lcyk.keer.ui.page.common.navigateToTagPage
import site.lcyk.keer.util.extractCollaboratorIds
import site.lcyk.keer.util.isCollaboratorTag
import site.lcyk.keer.util.isQuoteTag
import site.lcyk.keer.util.normalizeTagList
import site.lcyk.keer.util.resolveMemoByIdentifier
import site.lcyk.keer.util.resolveMemoFromQuoteDescriptor
import site.lcyk.keer.util.resolveMemoQuoteDescriptor
import site.lcyk.keer.util.storedMemoQuotePreviewOrNull
import site.lcyk.keer.util.toMemoQuotePreview
import site.lcyk.keer.viewmodel.LocalMemos
import site.lcyk.keer.viewmodel.LocalUserState
import site.lcyk.keer.viewmodel.MemoDetailViewModel

private const val EXPLORE_MEMO_PREFIX = "explore:"
private const val GROUP_MEMO_PREFIX = "group:"

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
    val detailViewModel: MemoDetailViewModel = hiltViewModel()
    val currentAccount by userStateViewModel.currentAccount.collectAsState()
    val collaboratorProfiles by userStateViewModel.collaboratorProfiles.collectAsState()
    val settings by context.settingsDataStore.data.collectAsState(initial = Settings())
    val scope = rememberCoroutineScope()
    val memoSnapshot = remember(memosViewModel.memos) { memosViewModel.memos.toList() }
    val localMemo = remember(memoSnapshot, memoIdentifier) {
        memosViewModel.getMemoForDetail(memoIdentifier)
    }
    val fallbackMemo by produceState<MemoEntity?>(
        initialValue = null,
        memoIdentifier,
        currentAccount?.accountKey()
    ) {
        value = detailViewModel.resolveFallbackMemoEntity(
            accountKey = currentAccount?.accountKey().orEmpty(),
            memoIdentifier = memoIdentifier
        )
    }
    val memo = remember(
        localMemo,
        fallbackMemo,
        memoSnapshot,
        memoIdentifier,
        settings.currentUser,
        settings.usersList
    ) {
        localMemo
            ?: fallbackMemo
            ?: resolveMemoByIdentifier(
                memoIdentifier = memoIdentifier,
                memos = memoSnapshot,
                settings = settings
            )
    }
    val readOnlyMemoDetail = remember(memoIdentifier) {
        memoIdentifier.startsWith(EXPLORE_MEMO_PREFIX) || memoIdentifier.startsWith(GROUP_MEMO_PREFIX)
    }
    val collaboratorIds = remember(memo?.tags) { extractCollaboratorIds(memo?.tags.orEmpty()) }
    val displayTags = remember(memo?.tags) {
        normalizeTagList(
            memo?.tags
                .orEmpty()
                .filterNot(::isCollaboratorTag)
                .filterNot(::isQuoteTag)
        )
    }
    val quoteDescriptor = remember(memo?.quoteSourceKind, memo?.quoteSource, memo?.tags) {
        memo?.resolveMemoQuoteDescriptor()
    }
    val quotedFallbackMemo by produceState<MemoEntity?>(
        initialValue = null,
        memo?.identifier,
        quoteDescriptor,
        currentAccount?.accountKey()
    ) {
        val descriptor = quoteDescriptor
        val accountKey = currentAccount?.accountKey().orEmpty()
        if (descriptor == null || accountKey.isBlank()) {
            value = null
            return@produceState
        }
        val candidateIdentifier = buildQuoteCandidateIdentifier(
            currentMemoIdentifier = memo?.identifier.orEmpty(),
            descriptor = descriptor
        )
        value = if (candidateIdentifier == null) {
            null
        } else {
            detailViewModel.resolveFallbackMemoEntity(
                accountKey = accountKey,
                memoIdentifier = candidateIdentifier,
            )
        }
    }
    val quoteSearchSpace = remember(memoSnapshot, quotedFallbackMemo) {
        listOfNotNull(quotedFallbackMemo) + memoSnapshot
    }
    val quotedMemo = remember(
        quoteDescriptor,
        quoteSearchSpace,
        settings.currentUser,
        settings.usersList,
    ) {
        val descriptor = quoteDescriptor ?: return@remember null
        memosViewModel.getMemoForDetail(descriptor.source)
            ?: quotedFallbackMemo
            ?: resolveMemoFromQuoteDescriptor(
                descriptor = descriptor,
                memos = quoteSearchSpace,
                settings = settings
            )
    }
    val quotePreview = remember(
        quotedMemo?.content,
        quotedMemo?.resources,
        memo?.quoteStatus,
        memo?.quoteContentPreview,
        memo?.quoteDate,
        memo?.quoteHasAttachments,
    ) {
        quotedMemo?.toMemoQuotePreview() ?: memo?.storedMemoQuotePreviewOrNull()
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
                title = { Text(text = R.string.memo_detail.string) },
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

            if (quoteDescriptor != null) {
                MemoQuoteReferenceCard(
                    quotedMemo = quotePreview,
                    modifier = Modifier
                        .padding(start = 15.dp, end = 15.dp, bottom = 10.dp),
                    onClick = quotedMemo?.let { source ->
                        {
                            memosViewModel.cacheMemoForDetail(source)
                            navController.navigateToMemoDetailPage(source.identifier)
                        }
                    }
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
}

private fun buildQuoteCandidateIdentifier(
    currentMemoIdentifier: String,
    descriptor: site.lcyk.keer.util.MemoQuoteDescriptor,
): String? {
    val normalizedCurrentIdentifier = currentMemoIdentifier.trim()
    return when {
        descriptor.sourceKind == site.lcyk.keer.util.MemoQuoteSourceKind.LOCAL -> descriptor.source
        normalizedCurrentIdentifier.startsWith(GROUP_MEMO_PREFIX) -> {
            val payload = normalizedCurrentIdentifier.removePrefix(GROUP_MEMO_PREFIX)
            val separatorIndex = payload.indexOf(':')
            if (separatorIndex <= 0) {
                null
            } else {
                "group:${payload.substring(0, separatorIndex)}:${descriptor.source}"
            }
        }
        normalizedCurrentIdentifier.startsWith(EXPLORE_MEMO_PREFIX) -> {
            "explore:${descriptor.source}"
        }
        else -> descriptor.source
    }
}
