package site.lcyk.keer.ui.page.group

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavHostController
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import site.lcyk.keer.R
import site.lcyk.keer.data.local.entity.ResourceEntity
import site.lcyk.keer.data.model.Memo
import site.lcyk.keer.ext.popBackStackIfLifecycleIsResumed
import site.lcyk.keer.ext.string
import site.lcyk.keer.ui.component.MemoQuoteReferenceCard
import site.lcyk.keer.ui.component.SurfaceHydrationLine
import site.lcyk.keer.ui.page.memoinput.MarkdownFormat
import site.lcyk.keer.ui.page.memoinput.MemoCollaboratorDialog
import site.lcyk.keer.ui.page.memoinput.MemoInputBottomBar
import site.lcyk.keer.ui.page.memoinput.MemoInputEditor
import site.lcyk.keer.ui.page.memoinput.rememberMemoEditorImportWorkflowState
import site.lcyk.keer.ui.page.memoinput.rememberMemoEditorLocationPermissionWorkflowState
import site.lcyk.keer.ui.page.memoinput.MemoInputTopBar
import site.lcyk.keer.ui.page.memoinput.MemoUploadFeedbackSnackbarEffect
import site.lcyk.keer.ui.page.memoinput.MemoTagSelectorDialog
import site.lcyk.keer.ui.page.memoinput.SaveChangesDialog
import site.lcyk.keer.ui.page.memoinput.applyMarkdownFormatToText
import site.lcyk.keer.ui.page.memoinput.handleEnterInText
import site.lcyk.keer.ui.page.memoinput.toggleTodoItemInText
import site.lcyk.keer.ui.page.common.LocalRootNavController
import site.lcyk.keer.ui.page.common.navigateToMemoDetailPage
import site.lcyk.keer.util.normalizeCollaboratorId
import site.lcyk.keer.util.normalizeTagList
import site.lcyk.keer.util.toMemoEntityForCard
import site.lcyk.keer.viewmodel.GroupChatViewModel
import site.lcyk.keer.viewmodel.LocalMemos
import site.lcyk.keer.viewmodel.LocalUserState
import site.lcyk.keer.viewmodel.buildActiveMemoQuoteDescriptor
import site.lcyk.keer.viewmodel.buildMemoEditorCompletionWorkflowState
import site.lcyk.keer.viewmodel.buildMemoEditorDismissWorkflowState
import site.lcyk.keer.viewmodel.buildMemoEditorDirtyState
import site.lcyk.keer.viewmodel.buildMemoEditorEffectiveRestoreState
import site.lcyk.keer.viewmodel.buildMemoEditorLocationState
import site.lcyk.keer.viewmodel.buildMemoEditorPersistedContentState
import site.lcyk.keer.viewmodel.buildMemoEditorResourceIdentifiers
import site.lcyk.keer.viewmodel.buildMemoEditorResolvedScreenState
import site.lcyk.keer.viewmodel.buildMemoEditorRestoreState
import site.lcyk.keer.viewmodel.buildMemoEditorRestoreWorkflowState
import site.lcyk.keer.viewmodel.buildMemoEditorSessionKey
import site.lcyk.keer.viewmodel.buildMemoEditorSubmitState
import site.lcyk.keer.viewmodel.buildMemoEditorUploadWorkflowState
import site.lcyk.keer.viewmodel.buildRequestedLocalQuoteDescriptor
import site.lcyk.keer.viewmodel.executeMemoEditorSubmitWorkflow
import site.lcyk.keer.viewmodel.MemoInputViewModel

private const val NEW_GROUP_MEMO_EDITOR_SEED = "__new__"
private const val groupMemoEditorDeferredPostFocusWorkDelayMillis = 180L

@Composable
fun GroupMemoInputPage(
    navController: NavHostController,
    groupId: String,
    memoId: String? = null,
    quoteSourceMemoIdentifier: String? = null,
    groupViewModel: GroupChatViewModel = hiltViewModel(),
    inputViewModel: MemoInputViewModel = hiltViewModel()
) {
    val focusRequester = remember { FocusRequester() }
    val coroutineScope = rememberCoroutineScope()
    val snackbarState = remember { SnackbarHostState() }
    val lifecycleOwner = LocalLifecycleOwner.current
    val rootNavController = LocalRootNavController.current
    val memosViewModel = LocalMemos.current
    val groupTags by groupViewModel.groupTags.collectAsState()
    val errorMessage by groupViewModel.errorMessage.collectAsState()
    val userStateViewModel = LocalUserState.current
    val currentAccount by userStateViewModel.currentAccount.collectAsState()
    val friends by userStateViewModel.friends.collectAsState()
    val groupMemos by groupViewModel.memos.collectAsState()
    val accountKey = currentAccount?.accountKey().orEmpty()

    val liveMemo = remember(groupId, memoId, groupMemos, accountKey) {
        if (memoId.isNullOrBlank() || accountKey.isBlank()) {
            null
        } else {
            groupMemos.firstOrNull { memo -> memo.remoteId == memoId }
                ?.toEditableGroupMemoEntity(groupId = groupId)
        }
    }
    var retainedMemo by remember(memoId) { mutableStateOf<site.lcyk.keer.data.local.entity.MemoEntity?>(null) }
    val displayMemo = liveMemo ?: retainedMemo
    val isEditMode = !memoId.isNullOrBlank()
    val requestedQuoteDescriptor = remember(quoteSourceMemoIdentifier) {
        buildRequestedLocalQuoteDescriptor(quoteSourceMemoIdentifier)
    }
    val editorSessionKey = remember(groupId, memoId, quoteSourceMemoIdentifier) {
        if (isEditMode) {
            buildMemoEditorSessionKey("group", groupId, "edit", memoId)
        } else {
            buildMemoEditorSessionKey("group", groupId, "new", quoteSourceMemoIdentifier)
        }
    }
    val persistedContentState = remember(
        inputViewModel.persistedEditorSessionKey,
        inputViewModel.persistedEditorContent,
        inputViewModel.persistedEditorSelectedTags,
        inputViewModel.persistedEditorSelectedCollaborators,
    ) {
        buildMemoEditorPersistedContentState(
            sessionKey = inputViewModel.persistedEditorSessionKey,
            content = inputViewModel.persistedEditorContent,
            selectedTags = inputViewModel.persistedEditorSelectedTags,
            selectedCollaborators = inputViewModel.persistedEditorSelectedCollaborators,
        )
    }
    val quoteMemoCandidates = remember(groupMemos, accountKey, groupId) {
        if (accountKey.isBlank()) {
            emptyList()
        } else {
            groupMemos.map { memo ->
                memo.toMemoEntityForCard(
                    identifier = "group:$groupId:${memo.remoteId}",
                    accountKey = accountKey,
                    needsSync = memo.remoteId.startsWith("local:"),
                )
            }
        }
    }
    val editorResolvedScreenState = remember(
        isEditMode,
        liveMemo,
        displayMemo?.quoteSourceKind,
        displayMemo?.quoteSource,
        displayMemo?.quoteStatus,
        displayMemo?.quoteContentPreview,
        displayMemo?.quoteDate,
        displayMemo?.quoteHasAttachments,
        requestedQuoteDescriptor,
        quoteMemoCandidates,
    ) {
        buildMemoEditorResolvedScreenState(
            hasTarget = isEditMode,
            liveValueAvailable = liveMemo != null,
            displayMemo = displayMemo,
            requestedQuoteDescriptor = requestedQuoteDescriptor,
            primaryQuotedMemo = buildActiveMemoQuoteDescriptor(
                displayMemo = displayMemo,
                requestedQuoteDescriptor = requestedQuoteDescriptor,
            )?.source?.let(memosViewModel::getMemoForDetail),
            memos = quoteMemoCandidates,
        )
    }
    val quotedMemo = editorResolvedScreenState.lookup.quotedMemo
    val editorScreenState = editorResolvedScreenState.screen
    val quotePreview = editorScreenState.quotePreview
    val quoteDescriptorForSubmit = editorScreenState.quoteDescriptorForSubmit
    val editorSession = editorScreenState.session
    val initialFields = editorSession.initialFields
    val uploadResources = inputViewModel.uploadResources.toList()
    val uploadTasks = inputViewModel.uploadTasks.toList()
    val recentlyUploadedResourceIdentifiers = inputViewModel.recentlyUploadedResourceIdentifiers.toList()
    val uploadWorkflowState = remember(
        uploadResources,
        uploadTasks,
        recentlyUploadedResourceIdentifiers,
    ) {
        buildMemoEditorUploadWorkflowState(
            memoIdentifier = null,
            uploadResources = uploadResources,
            uploadTasks = uploadTasks,
            highlightedResourceIdentifiers = recentlyUploadedResourceIdentifiers,
            focusDelayMillis = 300L,
        )
    }
    MemoUploadFeedbackSnackbarEffect(
        hostState = snackbarState,
        feedbackState = uploadWorkflowState.uploads.feedback,
    )
    var editorBaseline by remember {
        mutableStateOf(initialFields.baseline)
    }

    var text by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(initialFields.content, TextRange(initialFields.content.length)))
    }
    var selectedTags by rememberSaveable { mutableStateOf(initialFields.selectedTags) }
    var selectedCollaborators by rememberSaveable { mutableStateOf(initialFields.selectedCollaborators) }
    var showTagSelector by remember { mutableStateOf(false) }
    var showCollaboratorSelector by remember { mutableStateOf(false) }
    var showExitConfirmation by remember { mutableStateOf(false) }
    val quotePreviewContent: (@Composable () -> Unit)? = if (quoteDescriptorForSubmit == null) {
        null
    } else {
        {
            MemoQuoteReferenceCard(
                quotedMemo = quotePreview,
                onClick = quotedMemo?.let { source ->
                    {
                        memosViewModel.cacheMemoForDetail(source)
                        rootNavController.navigateToMemoDetailPage(source.identifier)
                    }
                }
            )
        }
    }

    val validMimeTypePrefixes = remember { setOf("text/") }
    val normalizedSelectedTags = remember(selectedTags) { normalizeTagList(selectedTags) }
    val normalizedSelectedCollaborators = remember(selectedCollaborators) {
        selectedCollaborators
            .map(::normalizeCollaboratorId)
            .filter { it.isNotEmpty() }
            .distinct()
    }
    val submitState = remember(
        text.text,
        normalizedSelectedTags,
        normalizedSelectedCollaborators,
        quoteDescriptorForSubmit,
        uploadWorkflowState.uploads,
    ) {
        buildMemoEditorSubmitState(
            content = text.text,
            selectedTags = normalizedSelectedTags,
            selectedCollaborators = normalizedSelectedCollaborators,
            quoteDescriptor = quoteDescriptorForSubmit,
            uploadsState = uploadWorkflowState.uploads,
        )
    }
    val locationState = remember {
        buildMemoEditorLocationState(
            enabled = false,
            hasPermission = false,
        )
    }
    var editorSeed by rememberSaveable(groupId, memoId, quoteSourceMemoIdentifier) { mutableStateOf<String?>(null) }
    val hydrationState = editorScreenState.hydrationState
    val applyFieldsState: (site.lcyk.keer.viewmodel.MemoEditorFieldsState) -> Unit = { fieldsState ->
        text = TextFieldValue(fieldsState.content, TextRange(fieldsState.content.length))
        selectedTags = fieldsState.selectedTags
        selectedCollaborators = fieldsState.selectedCollaborators
        editorBaseline = fieldsState.baseline
    }
    var pendingDisposeCleanup by remember { mutableStateOf<site.lcyk.keer.viewmodel.MemoEditorWorkflowCleanupState?>(null) }
    var clearPersistedEditorContentOnDispose by remember { mutableStateOf(false) }
    var resetPendingLocationPermissionRequest: () -> Unit = {}
    val applyWorkflowCleanup: (site.lcyk.keer.viewmodel.MemoEditorWorkflowCleanupState) -> Unit = { cleanup ->
        if (cleanup.clearUploads) {
            inputViewModel.clearUploadResources()
        }
        if (cleanup.clearUploadTasks) {
            inputViewModel.clearUploadTasks()
        }
        if (cleanup.resetPendingLocationPermission) {
            resetPendingLocationPermissionRequest()
        }
    }
    fun scheduleDisposeCleanup(
        cleanup: site.lcyk.keer.viewmodel.MemoEditorWorkflowCleanupState,
        clearPersistedEditorContent: Boolean = false,
    ) {
        pendingDisposeCleanup = cleanup
        if (clearPersistedEditorContent) {
            clearPersistedEditorContentOnDispose = true
        }
    }

    fun handleExit() {
        if (submitState.hasBlockingUpload) {
            coroutineScope.launch {
                snackbarState.showSnackbar(R.string.upload_in_progress_wait.string)
            }
            return
        }
        val workflowState = buildMemoEditorDismissWorkflowState(
            isDirty = buildMemoEditorDirtyState(
                baseline = editorBaseline,
                content = text.text,
                selectedTags = normalizedSelectedTags,
                selectedCollaborators = normalizedSelectedCollaborators,
                resourceIdentifiers = uploadWorkflowState.uploads.resourceIdentifiers,
            ),
            persistDraft = false,
            currentContent = text.text,
        )
        if (workflowState.dismiss.shouldShowDiscardConfirmation) {
            showExitConfirmation = true
        } else if (workflowState.dismiss.shouldDismiss) {
            scheduleDisposeCleanup(
                cleanup = workflowState.cleanup,
                clearPersistedEditorContent = true,
            )
            navController.popBackStackIfLifecycleIsResumed(lifecycleOwner)
        }
    }

    fun submit() = coroutineScope.launch {
        when (
            val submitResult = executeMemoEditorSubmitWorkflow(
                submitState = submitState,
                sessionState = editorSession,
                currentContent = text.text,
                persistDraft = false,
                clearDraft = true,
                clearUploads = true,
                clearUploadTasks = true,
                executor = { request ->
                    groupViewModel.submitEditorMemoRequest(
                        groupId = groupId,
                        memoRemoteId = memoId,
                        request = request,
                        existingTags = groupTags,
                    )
                },
            )
        ) {
            is site.lcyk.keer.viewmodel.MemoEditorSubmitWorkflowResult.Blocked -> {
                snackbarState.showSnackbar(submitResult.messageResId.string)
            }
            is site.lcyk.keer.viewmodel.MemoEditorSubmitWorkflowResult.Failed -> {
                snackbarState.showSnackbar(submitResult.message)
            }
            site.lcyk.keer.viewmodel.MemoEditorSubmitWorkflowResult.Skipped -> Unit
            is site.lcyk.keer.viewmodel.MemoEditorSubmitWorkflowResult.Succeeded -> {
                applyFieldsState(submitResult.workflowState.completion.fields)
                scheduleDisposeCleanup(
                    cleanup = submitResult.workflowState.cleanup,
                    clearPersistedEditorContent = true,
                )
                navController.popBackStackIfLifecycleIsResumed(lifecycleOwner)
            }
        }
    }

    val importWorkflow = rememberMemoEditorImportWorkflowState(
        context = navController.context,
        inputViewModel = inputViewModel,
        uploadEntryState = uploadWorkflowState.entry,
        snackbarHostState = snackbarState,
        focusRequester = focusRequester,
    )
    val locationPermissionWorkflow = rememberMemoEditorLocationPermissionWorkflowState(
        context = navController.context,
        locationState = locationState,
        onPermissionGranted = {},
        onSubmit = { _ ->
            submit()
        },
    )
    resetPendingLocationPermissionRequest = locationPermissionWorkflow.resetPendingSubmitRequest

    BackHandler {
        handleExit()
    }

    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            MemoInputTopBar(
                isEditMode = isEditMode,
                canSubmit = submitState.canSubmit,
                onClose = { handleExit() },
                onSubmit = { locationPermissionWorkflow.attemptSubmit() }
            )
        },
        bottomBar = {
            MemoInputBottomBar(
                selectedTags = selectedTags,
                selectedTagCount = normalizedSelectedTags.size,
                selectedCollaborators = selectedCollaborators,
                onTagSelectorClick = { showTagSelector = true },
                onTagRemove = { tagToRemove ->
                    selectedTags = normalizeTagList(
                        selectedTags.filterNot { it.equals(tagToRemove, ignoreCase = true) }
                    )
                },
                onCollaboratorSelectorClick = { showCollaboratorSelector = true },
                onCollaboratorRemove = { collaboratorId ->
                    val normalized = normalizeCollaboratorId(collaboratorId)
                    selectedCollaborators = selectedCollaborators
                        .map(::normalizeCollaboratorId)
                        .filter { it.isNotEmpty() }
                        .filterNot { it == normalized }
                        .distinct()
                },
                onToggleTodoItem = {
                    text = toggleTodoItemInText(text)
                },
                onPickImage = importWorkflow.pickVisualMedia,
                onPickAttachment = importWorkflow.pickAttachments,
                onTakePhoto = importWorkflow.takePhoto,
                onTakeVideo = importWorkflow.captureVideo,
                onFormat = { format: MarkdownFormat ->
                    text = applyMarkdownFormatToText(text, format)
                }
            )
        },
        snackbarHost = {
            SnackbarHost(hostState = snackbarState)
        }
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding)) {
            MemoInputEditor(
                text = text,
                onTextChange = onTextChange@{ updated ->
                    if (
                        text.text != updated.text &&
                        updated.selection.start == updated.selection.end &&
                        updated.text.length == text.text.length + 1 &&
                        updated.selection.start > 0 &&
                        updated.text[updated.selection.start - 1] == '\n'
                    ) {
                        val handled = handleEnterInText(text)
                        if (handled != null) {
                            text = handled
                            return@onTextChange
                        }
                    }
                    text = updated
                },
                modifier = Modifier,
                focusRequester = focusRequester,
                quotePreview = quotePreviewContent,
                validMimeTypePrefixes = validMimeTypePrefixes,
                onDroppedText = { droppedText ->
                    text = text.copy(text = text.text + droppedText)
                },
                uploadsState = uploadWorkflowState.uploads,
                onRemoveUploadResource = { resource -> inputViewModel.removeResourceFromDraft(resource) },
                onCancelUploadTask = { taskId -> inputViewModel.cancelUploadTask(taskId) },
                onCancelActiveUploadTasks = { inputViewModel.cancelActiveUploadTasks() },
                onRetryFailedUploadTasks = { inputViewModel.retryFailedUploadTasks() },
                onClearFailedUploadTasks = { inputViewModel.clearFailedUploadTasks() },
                onRetryUploadTask = { taskId -> inputViewModel.retryUploadTask(taskId) },
                onDismissUploadTask = { taskId ->
                    inputViewModel.dismissUploadTask(taskId)
                },
            )
            SurfaceHydrationLine(
                hydrationState = hydrationState,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
    }

    if (showTagSelector) {
        MemoTagSelectorDialog(
            availableTags = groupTags,
            selectedTags = selectedTags,
            onSelectedTagsChange = { selectedTags = normalizeTagList(it) },
            onDismiss = { showTagSelector = false }
        )
    }

    if (showCollaboratorSelector) {
        MemoCollaboratorDialog(
            availableCollaborators = friends,
            selectedCollaborators = selectedCollaborators,
            onSelectedCollaboratorsChange = { selectedCollaborators = it },
            onDismiss = { showCollaboratorSelector = false }
        )
    }

    if (showExitConfirmation) {
        SaveChangesDialog(
            onSave = {
                showExitConfirmation = false
                locationPermissionWorkflow.attemptSubmit()
            },
            onDiscard = {
                showExitConfirmation = false
                val workflowState = buildMemoEditorCompletionWorkflowState(
                    sessionState = editorSession,
                    persistDraft = false,
                    currentContent = text.text,
                    clearDraft = true,
                    clearUploads = true,
                    clearUploadTasks = true,
                )
                applyFieldsState(workflowState.completion.fields)
                scheduleDisposeCleanup(
                    cleanup = workflowState.cleanup,
                    clearPersistedEditorContent = true,
                )
                navController.popBackStackIfLifecycleIsResumed(lifecycleOwner)
            },
            onDismiss = {
                showExitConfirmation = false
            }
        )
    }

    LaunchedEffect(liveMemo?.identifier, liveMemo?.content, liveMemo?.date, liveMemo?.lastModified) {
        if (liveMemo != null) {
            retainedMemo = liveMemo
        }
    }

    LaunchedEffect(groupId) {
        coroutineScope.launch {
            delay(groupMemoEditorDeferredPostFocusWorkDelayMillis)
            userStateViewModel.refreshFriends()
        }
        coroutineScope.launch {
            groupViewModel.loadGroupTags(groupId)
        }
        if (isEditMode) {
            coroutineScope.launch {
                groupViewModel.loadGroupMemos(groupId, forceSync = false)
            }
        }
    }

    LaunchedEffect(groupId, memoId, quoteSourceMemoIdentifier, displayMemo?.identifier, displayMemo?.lastModified) {
        when {
            isEditMode -> {
                val targetMemo = displayMemo ?: return@LaunchedEffect
                val nextSeed = "${targetMemo.identifier}:${targetMemo.lastModified.toEpochMilli()}"
                if (editorSeed == nextSeed) {
                    return@LaunchedEffect
                }
                val targetMemoRemoteId = targetMemo.remoteId
                val targetGroupMemo = groupMemos.firstOrNull { memo ->
                    memo.remoteId == targetMemoRemoteId
                }
                val restoredUploadResources =
                    targetGroupMemo?.resources?.map { resource ->
                        ResourceEntity(
                            identifier = resource.remoteId,
                            remoteId = resource.remoteId,
                            accountKey = "",
                            date = resource.date,
                            filename = resource.filename,
                            uri = resource.uri,
                            localUri = resource.localUri,
                            mimeType = resource.mimeType,
                            encryptionMetadata = resource.encryptionMetadata,
                            thumbnailUri = resource.thumbnailUri,
                            thumbnailLocalUri = resource.thumbnailLocalUri,
                        )
                    } ?: targetMemo.resources.mapNotNull { resource ->
                        val remoteId = resource.remoteId ?: return@mapNotNull null
                        ResourceEntity(
                            identifier = remoteId,
                            remoteId = remoteId,
                            accountKey = resource.accountKey,
                            date = resource.date,
                            filename = resource.filename,
                            uri = resource.uri,
                            localUri = resource.localUri,
                            mimeType = resource.mimeType,
                            encryptionMetadata = resource.encryptionMetadata,
                            thumbnailUri = resource.thumbnailUri,
                            thumbnailLocalUri = resource.thumbnailLocalUri,
                        )
                    }
                val restoreWorkflowState = buildMemoEditorRestoreWorkflowState(
                    restoreState = buildMemoEditorEffectiveRestoreState(
                        baseRestoreState = buildMemoEditorRestoreState(
                            memo = targetMemo,
                            resourceIdentifiers = buildMemoEditorResourceIdentifiers(restoredUploadResources),
                        ),
                        persistedContentState = persistedContentState,
                        expectedSessionKey = editorSessionKey,
                        hasPersistedWorkflowPayload = uploadResources.isNotEmpty() || uploadTasks.isNotEmpty(),
                    ),
                    uploadResources = if (persistedContentState.sessionKey == editorSessionKey) {
                        uploadResources
                    } else {
                        restoredUploadResources
                    },
                )
                applyWorkflowCleanup(restoreWorkflowState.cleanup)
                inputViewModel.uploadResources.addAll(restoreWorkflowState.uploadResources)
                applyFieldsState(restoreWorkflowState.fields)
                editorSeed = nextSeed
            }

            editorSeed != NEW_GROUP_MEMO_EDITOR_SEED -> {
                val restoreWorkflowState = buildMemoEditorRestoreWorkflowState(
                    restoreState = buildMemoEditorEffectiveRestoreState(
                        baseRestoreState = buildMemoEditorRestoreState(
                            content = "",
                            tags = emptyList(),
                            collaboratorIds = emptyList(),
                        ),
                        persistedContentState = persistedContentState,
                        expectedSessionKey = editorSessionKey,
                        hasPersistedWorkflowPayload = uploadResources.isNotEmpty() || uploadTasks.isNotEmpty(),
                    ),
                    uploadResources = if (persistedContentState.sessionKey == editorSessionKey) uploadResources else emptyList(),
                )
                applyWorkflowCleanup(restoreWorkflowState.cleanup)
                applyFieldsState(restoreWorkflowState.fields)
                editorSeed = NEW_GROUP_MEMO_EDITOR_SEED
            }
        }
        withFrameNanos { }
        withFrameNanos { }
        focusRequester.requestFocus()
    }

    LaunchedEffect(
        editorSessionKey,
        editorSeed,
        text.text,
        normalizedSelectedTags,
        normalizedSelectedCollaborators,
    ) {
        if (editorSeed == null) {
            return@LaunchedEffect
        }
        inputViewModel.updatePersistedEditorContent(
            sessionKey = editorSessionKey,
            content = text.text,
            selectedTags = normalizedSelectedTags,
            selectedCollaborators = normalizedSelectedCollaborators,
        )
    }

    DisposableEffect(Unit) {
        onDispose {
            pendingDisposeCleanup?.let(applyWorkflowCleanup)
            if (clearPersistedEditorContentOnDispose) {
                inputViewModel.clearPersistedEditorContent(editorSessionKey)
            } else {
                inputViewModel.clearUploadTasks()
            }
        }
    }
}

private fun Memo.toEditableGroupMemoEntity(groupId: String): site.lcyk.keer.data.local.entity.MemoEntity {
    return toMemoEntityForCard(
        identifier = "group:$groupId:$remoteId",
        accountKey = "",
        needsSync = remoteId.startsWith("local:")
    )
}
