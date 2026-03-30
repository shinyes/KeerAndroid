package site.lcyk.keer.ui.page.group

import android.content.ActivityNotFoundException
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.CaptureVideo
import androidx.activity.result.contract.ActivityResultContracts.OpenDocument
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.activity.result.contract.ActivityResultContracts.TakePicture
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavHostController
import com.skydoves.sandwich.suspendOnSuccess
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import site.lcyk.keer.KeerFileProvider
import site.lcyk.keer.R
import site.lcyk.keer.data.local.entity.ResourceEntity
import site.lcyk.keer.data.model.Memo
import site.lcyk.keer.ext.popBackStackIfLifecycleIsResumed
import site.lcyk.keer.ext.string
import site.lcyk.keer.ext.suspendOnErrorMessage
import site.lcyk.keer.ui.component.MemoQuoteReferenceCard
import site.lcyk.keer.ui.component.SurfaceHydrationLine
import site.lcyk.keer.ui.page.memoinput.MarkdownFormat
import site.lcyk.keer.ui.page.memoinput.MemoCollaboratorDialog
import site.lcyk.keer.ui.page.memoinput.MemoInputBottomBar
import site.lcyk.keer.ui.page.memoinput.MemoInputEditor
import site.lcyk.keer.ui.page.memoinput.MemoInputTopBar
import site.lcyk.keer.ui.page.memoinput.MemoUploadFeedbackSnackbarEffect
import site.lcyk.keer.ui.page.memoinput.MemoTagSelectorDialog
import site.lcyk.keer.ui.page.memoinput.SaveChangesDialog
import site.lcyk.keer.ui.page.memoinput.applyMarkdownFormatToText
import site.lcyk.keer.ui.page.memoinput.handleEnterInText
import site.lcyk.keer.ui.page.memoinput.toggleTodoItemInText
import site.lcyk.keer.ui.page.common.LocalRootNavController
import site.lcyk.keer.ui.page.common.navigateToMemoDetailPage
import site.lcyk.keer.util.mergeTagsWithCollaboratorsAndQuote
import site.lcyk.keer.util.normalizeCollaboratorId
import site.lcyk.keer.util.normalizeTagList
import site.lcyk.keer.util.toMemoEntityForCard
import site.lcyk.keer.viewmodel.GroupChatViewModel
import site.lcyk.keer.viewmodel.LocalMemos
import site.lcyk.keer.viewmodel.LocalUserState
import site.lcyk.keer.viewmodel.buildActiveMemoQuoteDescriptor
import site.lcyk.keer.viewmodel.buildMemoEditorCanSubmit
import site.lcyk.keer.viewmodel.buildMemoEditorCompletionWorkflowState
import site.lcyk.keer.viewmodel.buildMemoEditorDismissWorkflowState
import site.lcyk.keer.viewmodel.buildMemoEditorDirtyState
import site.lcyk.keer.viewmodel.buildMemoEditorResourceIdentifiers
import site.lcyk.keer.viewmodel.buildMemoEditorResolvedScreenState
import site.lcyk.keer.viewmodel.buildMemoEditorRestoreState
import site.lcyk.keer.viewmodel.buildMemoEditorRestoreWorkflowState
import site.lcyk.keer.viewmodel.buildMemoEditorUploadWorkflowState
import site.lcyk.keer.viewmodel.buildRequestedLocalQuoteDescriptor
import site.lcyk.keer.viewmodel.MemoInputViewModel

private const val NEW_GROUP_MEMO_EDITOR_SEED = "__new__"

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
    var editorSeed by rememberSaveable(groupId, memoId, quoteSourceMemoIdentifier) { mutableStateOf<String?>(null) }
    val hydrationState = editorScreenState.hydrationState
    val applyFieldsState: (site.lcyk.keer.viewmodel.MemoEditorFieldsState) -> Unit = { fieldsState ->
        text = TextFieldValue(fieldsState.content, TextRange(fieldsState.content.length))
        selectedTags = fieldsState.selectedTags
        selectedCollaborators = fieldsState.selectedCollaborators
        editorBaseline = fieldsState.baseline
    }
    val applyWorkflowCleanup: (site.lcyk.keer.viewmodel.MemoEditorWorkflowCleanupState) -> Unit = { cleanup ->
        if (cleanup.clearUploads) {
            inputViewModel.clearUploadResources()
        }
        if (cleanup.clearUploadTasks) {
            inputViewModel.clearUploadTasks()
        }
    }

    fun handleExit() {
        if (uploadWorkflowState.uploads.hasActiveUpload) {
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
            applyWorkflowCleanup(workflowState.cleanup)
            navController.popBackStackIfLifecycleIsResumed(lifecycleOwner)
        }
    }

    fun submit() = coroutineScope.launch {
        if (uploadWorkflowState.uploads.hasActiveUpload) {
            snackbarState.showSnackbar(R.string.upload_in_progress_wait.string)
            return@launch
        }

        val mergedTags = mergeTagsWithCollaboratorsAndQuote(
            normalizedSelectedTags,
            normalizedSelectedCollaborators,
            quoteDescriptorForSubmit
        )
        val plainTags = normalizeTagList(normalizedSelectedTags)
        val existingSet = groupTags.map { it.trim().lowercase() }.toSet()
        val missingGroupTags = plainTags.filterNot { it.lowercase() in existingSet }
        for (tag in missingGroupTags) {
            groupViewModel.addGroupTag(groupId, tag)
        }

        val payload = text.text.trim()
        val currentResourceIdentifiers = uploadWorkflowState.uploads.resourceIdentifiers
        if (payload.isBlank() && currentResourceIdentifiers.isEmpty()) {
            return@launch
        }

        val saved = if (isEditMode) {
            groupViewModel.updateGroupMemo(
                groupId = groupId,
                memoRemoteId = memoId.orEmpty(),
                content = payload,
                tags = mergedTags,
                resourceIdentifiers = currentResourceIdentifiers
            )
        } else {
            groupViewModel.sendGroupMemo(
                groupId = groupId,
                content = payload,
                tags = mergedTags,
                resourceIdentifiers = currentResourceIdentifiers
            )
        }
        if (!saved) {
            snackbarState.showSnackbar(errorMessage ?: R.string.sync_failed.string)
            return@launch
        }

        val workflowState = buildMemoEditorCompletionWorkflowState(
            sessionState = editorSession,
            persistDraft = false,
            currentContent = text.text,
            clearDraft = true,
            clearUploads = true,
            clearUploadTasks = true,
        )
        applyFieldsState(workflowState.completion.fields)
        applyWorkflowCleanup(workflowState.cleanup)
        navController.popBackStackIfLifecycleIsResumed(lifecycleOwner)
    }

    fun uploadResource(uri: Uri) = coroutineScope.launch {
        inputViewModel.upload(uri, memoIdentifier = uploadWorkflowState.entry.targetMemoIdentifier).suspendOnSuccess {
            delay(uploadWorkflowState.entry.focusDelayMillis)
            focusRequester.requestFocus()
        }.suspendOnErrorMessage { message ->
            snackbarState.showSnackbar(message)
        }
    }

    val pickImage = rememberLauncherForActivityResult(PickVisualMedia()) { uri ->
        uri?.let { uploadResource(it) }
    }

    var photoImageUri by remember { mutableStateOf<Uri?>(null) }
    val takePhoto = rememberLauncherForActivityResult(TakePicture()) { success ->
        if (success) {
            photoImageUri?.let { uploadResource(it) }
        }
    }

    fun launchTakePhoto() {
        try {
            val uri = KeerFileProvider.getImageUri(navController.context)
            photoImageUri = uri
            takePhoto.launch(uri)
        } catch (e: ActivityNotFoundException) {
            coroutineScope.launch {
                snackbarState.showSnackbar(e.localizedMessage ?: R.string.unable_to_take_picture.string)
            }
        }
    }

    var videoUri by remember { mutableStateOf<Uri?>(null) }
    val captureVideo = rememberLauncherForActivityResult(CaptureVideo()) { success ->
        if (success) {
            videoUri?.let { uploadResource(it) }
        }
    }

    fun launchCaptureVideo() {
        try {
            val uri = KeerFileProvider.getVideoUri(navController.context)
            videoUri = uri
            captureVideo.launch(uri)
        } catch (e: ActivityNotFoundException) {
            coroutineScope.launch {
                snackbarState.showSnackbar(e.localizedMessage ?: R.string.unable_to_record_video.string)
            }
        }
    }

    val pickAttachment = rememberLauncherForActivityResult(OpenDocument()) { uri ->
        uri?.let { uploadResource(it) }
    }

    BackHandler {
        handleExit()
    }

    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            MemoInputTopBar(
                isEditMode = isEditMode,
                canSubmit = buildMemoEditorCanSubmit(
                    content = text.text,
                    uploadsState = uploadWorkflowState.uploads,
                ),
                onClose = { handleExit() },
                onSubmit = { submit() }
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
                onPickImage = {
                    pickImage.launch(PickVisualMediaRequest(PickVisualMedia.ImageAndVideo))
                },
                onPickAttachment = {
                    pickAttachment.launch(arrayOf("*/*"))
                },
                onTakePhoto = {
                    launchTakePhoto()
                },
                onTakeVideo = {
                    launchCaptureVideo()
                },
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
                onClearImageUploadResources = { inputViewModel.clearImageUploadResources() },
                onClearAttachmentUploadResources = { inputViewModel.clearAttachmentUploadResources() },
                onCancelUploadTask = { taskId -> inputViewModel.cancelUploadTask(taskId) },
                onCancelActiveUploadTasks = { inputViewModel.cancelActiveUploadTasks() },
                onRetryFailedUploadTasks = { inputViewModel.retryFailedUploadTasks() },
                onClearFailedUploadTasks = { inputViewModel.clearFailedUploadTasks() },
                onRetryUploadTask = { taskId -> inputViewModel.retryUploadTask(taskId) },
                onDismissUploadTask = { taskId ->
                    inputViewModel.dismissUploadTask(taskId)
                }
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
                submit()
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
                applyWorkflowCleanup(workflowState.cleanup)
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
        userStateViewModel.refreshFriends()
        groupViewModel.loadGroupTags(groupId)
        if (isEditMode) {
            groupViewModel.loadGroupMemos(groupId, forceSync = false)
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
                    restoreState = buildMemoEditorRestoreState(
                        memo = targetMemo,
                        resourceIdentifiers = buildMemoEditorResourceIdentifiers(restoredUploadResources),
                    ),
                    uploadResources = restoredUploadResources,
                )
                applyWorkflowCleanup(restoreWorkflowState.cleanup)
                inputViewModel.uploadResources.addAll(restoreWorkflowState.uploadResources)
                applyFieldsState(restoreWorkflowState.fields)
                editorSeed = nextSeed
            }

            editorSeed != NEW_GROUP_MEMO_EDITOR_SEED -> {
                val restoreWorkflowState = buildMemoEditorRestoreWorkflowState(
                    restoreState = buildMemoEditorRestoreState(
                        content = "",
                        tags = emptyList(),
                        collaboratorIds = emptyList(),
                    ),
                )
                applyWorkflowCleanup(restoreWorkflowState.cleanup)
                applyFieldsState(restoreWorkflowState.fields)
                editorSeed = NEW_GROUP_MEMO_EDITOR_SEED
            }
        }
        delay(300)
        focusRequester.requestFocus()
    }

    DisposableEffect(Unit) {
        onDispose {
            inputViewModel.clearUploadTasks()
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
