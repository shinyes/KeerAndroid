package site.lcyk.keer.ui.page.memoinput

import android.location.Location
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.compose.runtime.withFrameNanos
import site.lcyk.keer.R
import site.lcyk.keer.ext.popBackStackIfLifecycleIsResumed
import site.lcyk.keer.ext.string
import site.lcyk.keer.ui.component.MemoQuoteReferenceCard
import site.lcyk.keer.ui.component.SurfaceHydrationLine
import site.lcyk.keer.ui.page.common.LocalRootNavController
import site.lcyk.keer.ui.page.common.navigateToMemoDetailPage
import site.lcyk.keer.util.isCollaboratorTag
import site.lcyk.keer.util.isQuoteTag
import site.lcyk.keer.util.normalizeCollaboratorId
import site.lcyk.keer.util.normalizeTagList
import site.lcyk.keer.util.normalizeTagName
import site.lcyk.keer.util.resolveMemoByIdentifier
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

private const val NEW_MEMO_EDITOR_SEED = "__new__"

@Composable
fun MemoInputPage(
    viewModel: MemoInputViewModel = hiltViewModel(),
    memoIdentifier: String? = null,
    quoteSourceMemoIdentifier: String? = null
) {
    val focusRequester = remember { FocusRequester() }
    val coroutineScope = rememberCoroutineScope()
    val snackbarState = remember { SnackbarHostState() }
    val navController = LocalRootNavController.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val memosViewModel = LocalMemos.current
    val userStateViewModel = LocalUserState.current
    val friends by userStateViewModel.friends.collectAsState()
    val memoSnapshot = memosViewModel.memos
    val memo = remember(memoIdentifier, memoSnapshot) {
        if (memoIdentifier.isNullOrBlank()) {
            null
        } else {
            memosViewModel.getMemoForDetail(memoIdentifier)
                ?: resolveMemoByIdentifier(
                    memoIdentifier = memoIdentifier,
                    memos = memoSnapshot,
                )
        }
    }
    var retainedMemo by remember(memoIdentifier) { mutableStateOf<site.lcyk.keer.data.local.entity.MemoEntity?>(null) }
    val displayMemo = memo ?: retainedMemo
    val requestedQuoteDescriptor = remember(quoteSourceMemoIdentifier) {
        buildRequestedLocalQuoteDescriptor(quoteSourceMemoIdentifier)
    }
    val editorSessionKey = remember(memoIdentifier, quoteSourceMemoIdentifier) {
        if (memoIdentifier.isNullOrBlank()) {
            buildMemoEditorSessionKey("memo", "new", quoteSourceMemoIdentifier)
        } else {
            buildMemoEditorSessionKey("memo", "edit", memoIdentifier)
        }
    }
    val persistedContentState = remember(
        viewModel.persistedEditorSessionKey,
        viewModel.persistedEditorContent,
        viewModel.persistedEditorSelectedTags,
        viewModel.persistedEditorSelectedCollaborators,
    ) {
        buildMemoEditorPersistedContentState(
            sessionKey = viewModel.persistedEditorSessionKey,
            content = viewModel.persistedEditorContent,
            selectedTags = viewModel.persistedEditorSelectedTags,
            selectedCollaborators = viewModel.persistedEditorSelectedCollaborators,
        )
    }
    val editorResolvedScreenState = remember(
        memoIdentifier,
        memo,
        displayMemo?.quoteSourceKind,
        displayMemo?.quoteSource,
        displayMemo?.quoteStatus,
        displayMemo?.quoteContentPreview,
        displayMemo?.quoteDate,
        displayMemo?.quoteHasAttachments,
        requestedQuoteDescriptor,
        memoSnapshot,
    ) {
        buildMemoEditorResolvedScreenState(
            hasTarget = !memoIdentifier.isNullOrBlank(),
            liveValueAvailable = memo != null,
            displayMemo = displayMemo,
            requestedQuoteDescriptor = requestedQuoteDescriptor,
            primaryQuotedMemo = buildActiveMemoQuoteDescriptor(
                displayMemo = displayMemo,
                requestedQuoteDescriptor = requestedQuoteDescriptor,
            )?.source?.let(memosViewModel::getMemoForDetail),
            memos = memoSnapshot,
        )
    }
    val quotedMemo = editorResolvedScreenState.lookup.quotedMemo
    val editorScreenState = editorResolvedScreenState.screen
    val quotePreview = editorScreenState.quotePreview
    val quoteDescriptorForSubmit = editorScreenState.quoteDescriptorForSubmit
    val editorSession = editorScreenState.session
    val initialFields = editorSession.initialFields
    val uploadResources = viewModel.uploadResources.toList()
    val uploadTasks = viewModel.uploadTasks.toList()
    val recentlyUploadedResourceIdentifiers = viewModel.recentlyUploadedResourceIdentifiers.toList()
    val uploadWorkflowState = remember(
        memoIdentifier,
        displayMemo?.identifier,
        uploadResources,
        uploadTasks,
        recentlyUploadedResourceIdentifiers,
    ) {
        buildMemoEditorUploadWorkflowState(
            memoIdentifier = memoIdentifier,
            displayMemo = displayMemo,
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
    var selectedTags by rememberSaveable {
        mutableStateOf(initialFields.selectedTags)
    }
    var selectedCollaborators by rememberSaveable {
        mutableStateOf(initialFields.selectedCollaborators)
    }
    var showTagSelector by remember { mutableStateOf(false) }
    var showCollaboratorSelector by remember { mutableStateOf(false) }
    var showExitConfirmation by remember { mutableStateOf(false) }
    var prefetchedLocation by remember { mutableStateOf<Location?>(null) }
    var isLocationPrefetching by remember { mutableStateOf(false) }
    var stopLocationTracking by remember { mutableStateOf<(() -> Unit)?>(null) }
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
    val locationConfig = remember { MemoEditorLocationConfig() }
    val locationState = remember(
        memoIdentifier,
        displayMemo?.identifier,
        hasLocationPermission(navController.context),
    ) {
        buildMemoEditorLocationState(
            enabled = memoIdentifier.isNullOrBlank() && displayMemo == null,
            hasPermission = hasLocationPermission(navController.context),
        )
    }
    var editorSeed by rememberSaveable(memoIdentifier, quoteSourceMemoIdentifier) { mutableStateOf<String?>(null) }
    val hydrationState = editorScreenState.hydrationState
    val applyFieldsState: (site.lcyk.keer.viewmodel.MemoEditorFieldsState) -> Unit = { fieldsState ->
        text = TextFieldValue(fieldsState.content, TextRange(fieldsState.content.length))
        selectedTags = fieldsState.selectedTags
        selectedCollaborators = fieldsState.selectedCollaborators
        editorBaseline = fieldsState.baseline
    }
    var resetPendingLocationPermissionRequest: () -> Unit = {}
    val applyWorkflowCleanup: (site.lcyk.keer.viewmodel.MemoEditorWorkflowCleanupState) -> Unit = { cleanup ->
        if (cleanup.clearUploads) {
            viewModel.clearUploadResources()
        }
        if (cleanup.clearUploadTasks) {
            viewModel.clearUploadTasks()
        }
        if (cleanup.stopLocationTracking) {
            stopLocationTracking?.invoke()
            stopLocationTracking = null
        }
        if (cleanup.clearPrefetchedLocation) {
            prefetchedLocation = null
        }
        if (cleanup.resetPendingLocationPermission) {
            resetPendingLocationPermissionRequest()
        }
        if (cleanup.refreshLocalSnapshot) {
            coroutineScope.launch {
                memosViewModel.refreshLocalSnapshot()
            }
        }
    }
    val quotePreviewContent: (@Composable () -> Unit)? = if (quoteDescriptorForSubmit == null) {
        null
    } else {
        {
            MemoQuoteReferenceCard(
                quotedMemo = quotePreview,
                onClick = quotedMemo?.let { source ->
                    {
                        memosViewModel.cacheMemoForDetail(source)
                        navController.navigateToMemoDetailPage(source.identifier)
                    }
                }
            )
        }
    }

    val resolvedVisibility = displayMemo?.visibility ?: site.lcyk.keer.data.model.MemoVisibility.PRIVATE

    val validMimeTypePrefixes = remember {
        setOf("text/")
    }

    fun startLocationPrefetch(force: Boolean = false) {
        if (!locationState.canPrefetch) {
            return
        }

        if (force) {
            stopLocationTracking?.invoke()
            stopLocationTracking = null
        } else if (stopLocationTracking != null) {
            return
        }

        val stopCallbacks = mutableListOf<() -> Unit>()
        startPlatformLocationTracking(navController.context, locationConfig) { candidate ->
            if (isLocationFresh(candidate, locationConfig)) {
                prefetchedLocation = pickMoreAccurateLocation(prefetchedLocation, candidate)
            }
        }?.let { stopCallbacks.add(it) }

        startGnssLocationTracking(navController.context, locationConfig) { candidate ->
            if (isLocationFresh(candidate, locationConfig)) {
                prefetchedLocation = pickMoreAccurateLocation(prefetchedLocation, candidate)
            }
        }?.let { stopCallbacks.add(it) }

        stopLocationTracking = if (stopCallbacks.isEmpty()) {
            null
        } else {
            {
                stopCallbacks.forEach { stop -> stop() }
            }
        }

        if (isLocationPrefetching) {
            return
        }

        isLocationPrefetching = true
        coroutineScope.launch {
            try {
                val location = getCurrentLocationBestEffort(
                    context = navController.context,
                    config = locationConfig,
                    maxWaitMillis = locationConfig.prefetchLocationTimeoutMillis,
                )
                if (location != null && isQualifiedLocation(location, locationConfig)) {
                    prefetchedLocation = pickMoreAccurateLocation(prefetchedLocation, location)
                }
            } finally {
                isLocationPrefetching = false
            }
        }
    }

    fun submit(collectCoordinates: Boolean = true) = coroutineScope.launch {
        val editingMemo = displayMemo
        if (!memoIdentifier.isNullOrBlank()) {
            val targetMemo = editingMemo ?: run {
                snackbarState.showSnackbar(R.string.memo_not_found.string)
                return@launch
            }

            when (
                val submitResult = executeMemoEditorSubmitWorkflow(
                    submitState = submitState,
                    sessionState = editorSession,
                    currentContent = text.text,
                    persistDraft = false,
                    refreshLocalSnapshot = true,
                    executor = { request ->
                        viewModel.submitEditorRequest(
                            memoIdentifier = targetMemo.identifier,
                            visibility = resolvedVisibility,
                            request = request,
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
                    applyWorkflowCleanup(submitResult.workflowState.cleanup)
                    viewModel.clearPersistedEditorContent(editorSessionKey)
                    navController.popBackStack()
                }
            }
            return@launch
        }

        val location = if (collectCoordinates && locationState.shouldCollectCoordinatesOnSubmit) {
            val cached = prefetchedLocation?.takeIf { location ->
                isQualifiedLocation(location, locationConfig)
            }
            cached ?: getCurrentLocationBestEffort(
                context = navController.context,
                config = locationConfig,
                maxWaitMillis = locationConfig.submitLocationTimeoutMillis,
            )
                ?.takeIf { location ->
                    isQualifiedLocation(location, locationConfig)
                }
                ?.also { fresh ->
                    prefetchedLocation = pickMoreAccurateLocation(prefetchedLocation, fresh)
                }
        } else {
            null
        }
        when (
            val submitResult = executeMemoEditorSubmitWorkflow(
                submitState = submitState,
                sessionState = editorSession,
                currentContent = text.text,
                persistDraft = true,
                latitude = location?.latitude,
                longitude = location?.longitude,
                clearDraft = true,
                clearUploads = true,
                clearUploadTasks = true,
                stopLocationTracking = true,
                clearPrefetchedLocation = true,
                resetPendingLocationPermission = true,
                refreshLocalSnapshot = true,
                executor = { request ->
                    viewModel.submitEditorRequest(
                        memoIdentifier = null,
                        visibility = resolvedVisibility,
                        request = request,
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
                applyWorkflowCleanup(submitResult.workflowState.cleanup)
                submitResult.workflowState.completion.draftPersistenceValue?.let(viewModel::updateDraft)
                viewModel.clearPersistedEditorContent(editorSessionKey)
                navController.popBackStack()
            }
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
            persistDraft = memoIdentifier.isNullOrBlank(),
            currentContent = text.text,
            stopLocationTrackingOnDismiss = true,
            clearPrefetchedLocationOnDismiss = true,
            resetPendingLocationPermissionOnDismiss = true,
        )
        if (workflowState.dismiss.shouldShowDiscardConfirmation) {
            showExitConfirmation = true
        } else if (workflowState.dismiss.shouldDismiss) {
            workflowState.dismiss.draftPersistenceValue?.let(viewModel::updateDraft)
            applyWorkflowCleanup(workflowState.cleanup)
            viewModel.clearPersistedEditorContent(editorSessionKey)
            navController.popBackStackIfLifecycleIsResumed(lifecycleOwner)
        }
    }

    val importWorkflow = rememberMemoEditorImportWorkflowState(
        context = navController.context,
        inputViewModel = viewModel,
        uploadEntryState = uploadWorkflowState.entry,
        snackbarHostState = snackbarState,
        focusRequester = focusRequester,
    )

    val locationPermissionWorkflow = rememberMemoEditorLocationPermissionWorkflowState(
        context = navController.context,
        locationState = locationState,
        onPermissionGranted = {
            startLocationPrefetch(force = true)
        },
        onSubmit = { collectCoordinates ->
            submit(collectCoordinates = collectCoordinates)
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
                isEditMode = !memoIdentifier.isNullOrBlank(),
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
                onTagSelectorClick = {
                    showTagSelector = true
                },
                onTagRemove = { tagToRemove ->
                    val normalizedTagToRemove = normalizeTagName(tagToRemove)
                    selectedTags = normalizeTagList(
                        selectedTags.filterNot { normalizeTagName(it) == normalizedTagToRemove }
                    )
                },
                onCollaboratorSelectorClick = {
                    showCollaboratorSelector = true
                },
                onCollaboratorRemove = { collaboratorId ->
                    val normalizedCollaboratorId = normalizeCollaboratorId(collaboratorId)
                    selectedCollaborators = selectedCollaborators
                        .map(::normalizeCollaboratorId)
                        .filter { it.isNotEmpty() }
                        .filterNot { it == normalizedCollaboratorId }
                        .distinct()
                },
                onToggleTodoItem = {
                    text = toggleTodoItemInText(text)
                },
                onPickImage = importWorkflow.pickVisualMedia,
                onPickAttachment = importWorkflow.pickAttachments,
                onTakePhoto = importWorkflow.takePhoto,
                onTakeVideo = importWorkflow.captureVideo,
                onFormat = { format ->
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
                focusRequester = focusRequester,
                quotePreview = quotePreviewContent,
                validMimeTypePrefixes = validMimeTypePrefixes,
                onDroppedText = { droppedText ->
                    text = text.copy(text = text.text + droppedText)
                },
                uploadsState = uploadWorkflowState.uploads,
                onRemoveUploadResource = { resource -> viewModel.removeResourceFromDraft(resource) },
                onCancelUploadTask = { taskId -> viewModel.cancelUploadTask(taskId) },
                onCancelActiveUploadTasks = { viewModel.cancelActiveUploadTasks() },
                onRetryFailedUploadTasks = { viewModel.retryFailedUploadTasks() },
                onClearFailedUploadTasks = { viewModel.clearFailedUploadTasks() },
                onRetryUploadTask = { taskId -> viewModel.retryUploadTask(taskId) },
                onDismissUploadTask = { taskId -> viewModel.dismissUploadTask(taskId) },
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
            availableTags = memosViewModel.tags
                .filterNot(::isCollaboratorTag)
                .filterNot(::isQuoteTag),
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
                    persistDraft = memoIdentifier.isNullOrBlank(),
                    currentContent = text.text,
                    clearDraft = true,
                    clearUploads = true,
                    clearUploadTasks = true,
                )
                applyFieldsState(workflowState.completion.fields)
                applyWorkflowCleanup(workflowState.cleanup)
                workflowState.completion.draftPersistenceValue?.let(viewModel::updateDraft)
                viewModel.clearPersistedEditorContent(editorSessionKey)
                navController.popBackStackIfLifecycleIsResumed(lifecycleOwner)
            },
            onDismiss = {
                showExitConfirmation = false
            }
        )
    }

    LaunchedEffect(memo?.identifier, memo?.content, memo?.date, memo?.lastModified) {
        if (memo != null) {
            retainedMemo = memo
        }
    }

    LaunchedEffect(memoIdentifier, quoteSourceMemoIdentifier, displayMemo?.identifier, displayMemo?.lastModified) {
        memosViewModel.loadTags()
        coroutineScope.launch {
            userStateViewModel.refreshFriends()
        }
        when {
            !memoIdentifier.isNullOrBlank() -> {
                val targetMemo = displayMemo ?: return@LaunchedEffect
                val nextSeed = "${targetMemo.identifier}:${targetMemo.lastModified.toEpochMilli()}"
                if (editorSeed == nextSeed) {
                    return@LaunchedEffect
                }
                val restoreState = buildMemoEditorRestoreState(
                    memo = targetMemo,
                    resourceIdentifiers = buildMemoEditorResourceIdentifiers(targetMemo.resources),
                )
                val effectiveRestoreState = buildMemoEditorEffectiveRestoreState(
                    baseRestoreState = restoreState,
                    persistedContentState = persistedContentState,
                    expectedSessionKey = editorSessionKey,
                    hasPersistedWorkflowPayload = uploadResources.isNotEmpty() || uploadTasks.isNotEmpty(),
                )
                val restoreWorkflowState = buildMemoEditorRestoreWorkflowState(
                    restoreState = effectiveRestoreState,
                    uploadResources = if (persistedContentState.sessionKey == editorSessionKey) {
                        uploadResources
                    } else {
                        targetMemo.resources
                    },
                )
                applyWorkflowCleanup(restoreWorkflowState.cleanup)
                viewModel.uploadResources.addAll(restoreWorkflowState.uploadResources)
                applyFieldsState(restoreWorkflowState.fields)
                editorSeed = nextSeed
            }

            editorSeed != NEW_MEMO_EDITOR_SEED -> {
                val restoredDraft = viewModel.draft.first().orEmpty()
                val restoreState = buildMemoEditorRestoreState(
                    content = restoredDraft,
                    tags = emptyList(),
                    collaboratorIds = emptyList(),
                )
                val effectiveRestoreState = buildMemoEditorEffectiveRestoreState(
                    baseRestoreState = restoreState,
                    persistedContentState = persistedContentState,
                    expectedSessionKey = editorSessionKey,
                    hasPersistedWorkflowPayload = uploadResources.isNotEmpty() || uploadTasks.isNotEmpty(),
                )
                val restoreWorkflowState = buildMemoEditorRestoreWorkflowState(
                    restoreState = effectiveRestoreState,
                    uploadResources = if (persistedContentState.sessionKey == editorSessionKey) {
                        uploadResources
                    } else {
                        emptyList()
                    },
                )
                applyWorkflowCleanup(restoreWorkflowState.cleanup)
                applyFieldsState(restoreWorkflowState.fields)
                editorSeed = NEW_MEMO_EDITOR_SEED
            }
        }
        withFrameNanos { }
        withFrameNanos { }
        focusRequester.requestFocus()
        coroutineScope.launch {
            delay(120)
            startLocationPrefetch(force = true)
        }
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
        viewModel.updatePersistedEditorContent(
            sessionKey = editorSessionKey,
            content = text.text,
            selectedTags = normalizedSelectedTags,
            selectedCollaborators = normalizedSelectedCollaborators,
        )
    }

    DisposableEffect(Unit) {
        onDispose {
            stopLocationTracking?.invoke()
            if (memoIdentifier.isNullOrBlank()) {
                buildMemoEditorDismissWorkflowState(
                    isDirty = false,
                    persistDraft = true,
                    currentContent = text.text,
                ).dismiss.draftPersistenceValue?.let(viewModel::updateDraft)
            }
        }
    }
}
