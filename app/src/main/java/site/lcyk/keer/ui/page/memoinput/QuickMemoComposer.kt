package site.lcyk.keer.ui.page.memoinput

import android.location.Location
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import site.lcyk.keer.R
import site.lcyk.keer.ext.string
import site.lcyk.keer.ui.component.SurfaceHydrationLine
import site.lcyk.keer.util.isCollaboratorTag
import site.lcyk.keer.util.isQuoteTag
import site.lcyk.keer.util.normalizeCollaboratorId
import site.lcyk.keer.util.normalizeTagList
import site.lcyk.keer.util.normalizeTagName
import site.lcyk.keer.viewmodel.LocalMemos
import site.lcyk.keer.viewmodel.LocalUserState
import site.lcyk.keer.viewmodel.MemoInputViewModel
import site.lcyk.keer.viewmodel.buildDraftEditorScreenState
import site.lcyk.keer.viewmodel.MemoEditorWorkflowCleanupState
import site.lcyk.keer.viewmodel.buildMemoEditorCompletionWorkflowState
import site.lcyk.keer.viewmodel.buildMemoEditorBaseline
import site.lcyk.keer.viewmodel.buildMemoEditorDismissWorkflowState
import site.lcyk.keer.viewmodel.buildMemoEditorDirtyState
import site.lcyk.keer.viewmodel.buildMemoEditorEffectiveRestoreState
import site.lcyk.keer.viewmodel.buildMemoEditorLocationState
import site.lcyk.keer.viewmodel.buildMemoEditorPersistedContentState
import site.lcyk.keer.viewmodel.buildMemoEditorRestoreWorkflowState
import site.lcyk.keer.viewmodel.buildMemoEditorSessionKey
import site.lcyk.keer.viewmodel.buildMemoEditorSubmitState
import site.lcyk.keer.viewmodel.buildMemoEditorUploadWorkflowState
import site.lcyk.keer.viewmodel.executeMemoEditorSubmitWorkflow
import site.lcyk.keer.viewmodel.MemoEditorSubmitRequest

private val quickComposerEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
private val quickComposerEditorPadding = PaddingValues(
    start = 16.dp,
    top = 16.dp,
    end = 16.dp,
    bottom = 8.dp
)
private val quickComposerSurfaceVerticalPadding = 20.dp
private val quickComposerCompactContainerHeight = 152.dp
private val quickComposerDefaultMinContainerHeight = 196.dp
private val quickComposerCompactEditorHeight = 96.dp
private val quickComposerDefaultMinEditorHeight = 132.dp
private val quickComposerBottomBarHeight = 60.dp
private val quickComposerFallbackLineHeight = 20.dp
private const val quickComposerKeyboardHideDelayMillis = 120L
private const val quickComposerMinEditorLines = 4
private val quickComposerShape = RoundedCornerShape(16.dp)

@Composable
fun QuickMemoComposer(
    visible: Boolean,
    onDismissRequest: () -> Unit,
    forcedTags: List<String> = emptyList(),
    availableTags: List<String>? = null,
    enableLocation: Boolean = true,
    persistDraft: Boolean = true,
    onSubmitRequest: (suspend (MemoEditorSubmitRequest) -> String?)? = null,
    inputViewModel: MemoInputViewModel = hiltViewModel(),
) {
    val focusRequester = remember { FocusRequester() }
    val snackbarState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val memosViewModel = LocalMemos.current
    val userStateViewModel = LocalUserState.current
    val friends by userStateViewModel.friends.collectAsState()
    val persistedDraft by if (persistDraft) {
        inputViewModel.draft.collectAsState(initial = null)
    } else {
        remember { mutableStateOf<String?>(null) }
    }

    var editorBaseline by remember {
        mutableStateOf(buildMemoEditorBaseline(content = "", tags = emptyList(), collaboratorIds = emptyList()))
    }
    var text by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue("", TextRange(0)))
    }
    var selectedTags by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var selectedCollaborators by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var showTagSelector by remember { mutableStateOf(false) }
    var showCollaboratorSelector by remember { mutableStateOf(false) }
    var showExitConfirmation by remember { mutableStateOf(false) }
    var prefetchedLocation by remember { mutableStateOf<Location?>(null) }
    var isLocationPrefetching by remember { mutableStateOf(false) }
    var stopLocationTracking by remember { mutableStateOf<(() -> Unit)?>(null) }
    val locationConfig = remember { MemoEditorLocationConfig() }
    val locationState = remember(enableLocation, hasLocationPermission(context)) {
        buildMemoEditorLocationState(
            enabled = enableLocation,
            hasPermission = hasLocationPermission(context),
        )
    }
    val normalizedForcedTags = remember(forcedTags) { normalizeTagList(forcedTags) }
    val editorSessionKey = remember(normalizedForcedTags) {
        buildMemoEditorSessionKey("quick", normalizedForcedTags.joinToString("|"))
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
    val normalizedSelectedTags = remember(selectedTags) { normalizeTagList(selectedTags) }
    val normalizedSelectedCollaborators = remember(selectedCollaborators) {
        selectedCollaborators
            .map(::normalizeCollaboratorId)
            .filter { it.isNotEmpty() }
            .distinct()
    }
    val draftScreenState = remember(
        visible,
        persistDraft,
        persistedDraft,
        normalizedForcedTags,
        text.text,
    ) {
        buildDraftEditorScreenState(
            persistedDraft = persistedDraft,
            forcedTags = normalizedForcedTags,
            hasLiveDraft = visible && persistDraft && persistedDraft != null,
            currentContent = text.text,
        )
    }
    val sessionState = draftScreenState.session
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
            focusDelayMillis = 120L,
            showKeyboardAfterUpload = true,
        )
    }
    MemoUploadFeedbackSnackbarEffect(
        hostState = snackbarState,
        feedbackState = uploadWorkflowState.uploads.feedback,
    )
    val submitState = remember(
        text.text,
        normalizedForcedTags,
        normalizedSelectedTags,
        normalizedSelectedCollaborators,
        uploadWorkflowState.uploads,
    ) {
        buildMemoEditorSubmitState(
            content = text.text,
            selectedTags = normalizedForcedTags + normalizedSelectedTags,
            selectedCollaborators = normalizedSelectedCollaborators,
            quoteDescriptor = null,
            uploadsState = uploadWorkflowState.uploads,
        )
    }
    val validMimeTypePrefixes = remember { setOf("text/") }
    var shouldHideKeyboardAfterDismiss by remember { mutableStateOf(false) }
    val applyFieldsState: (site.lcyk.keer.viewmodel.MemoEditorFieldsState) -> Unit = { fieldsState ->
        text = TextFieldValue(fieldsState.content, TextRange(fieldsState.content.length))
        selectedTags = fieldsState.selectedTags
        selectedCollaborators = fieldsState.selectedCollaborators
        editorBaseline = fieldsState.baseline
    }
    var resetPendingLocationPermissionRequest: () -> Unit = {}
    val applyWorkflowCleanup: (MemoEditorWorkflowCleanupState) -> Unit = { cleanup ->
        if (cleanup.clearUploads) {
            inputViewModel.clearUploadResources()
        }
        if (cleanup.clearUploadTasks) {
            inputViewModel.clearUploadTasks()
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
        if (cleanup.hideKeyboard) {
            if (visible) {
                shouldHideKeyboardAfterDismiss = true
            } else {
                keyboardController?.hide()
                shouldHideKeyboardAfterDismiss = false
            }
        }
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
        startPlatformLocationTracking(context, locationConfig) { candidate ->
            if (isLocationFresh(candidate, locationConfig)) {
                prefetchedLocation = pickMoreAccurateLocation(prefetchedLocation, candidate)
            }
        }?.let(stopCallbacks::add)

        startGnssLocationTracking(context, locationConfig) { candidate ->
            if (isLocationFresh(candidate, locationConfig)) {
                prefetchedLocation = pickMoreAccurateLocation(prefetchedLocation, candidate)
            }
        }?.let(stopCallbacks::add)

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
                    context = context,
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

    fun dismissComposer(forceDiscard: Boolean = false) {
        if (forceDiscard) {
            val workflowState = buildMemoEditorCompletionWorkflowState(
                sessionState = sessionState,
                persistDraft = persistDraft,
                currentContent = text.text,
                clearDraft = true,
                clearUploads = true,
                clearUploadTasks = true,
                stopLocationTracking = true,
                clearPrefetchedLocation = true,
                resetPendingLocationPermission = true,
                hideKeyboard = true,
            )
            applyFieldsState(workflowState.completion.fields)
            workflowState.completion.draftPersistenceValue?.let(inputViewModel::updateDraft)
            applyWorkflowCleanup(workflowState.cleanup)
            inputViewModel.clearPersistedEditorContent(editorSessionKey)
            onDismissRequest()
            return
        }

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
                selectedTags = normalizeTagList(normalizedForcedTags + normalizedSelectedTags),
                selectedCollaborators = normalizedSelectedCollaborators,
                resourceIdentifiers = uploadWorkflowState.uploads.resourceIdentifiers,
            ),
            persistDraft = persistDraft,
            currentContent = text.text,
            hideKeyboardOnDismiss = true,
        )
        if (workflowState.dismiss.shouldShowDiscardConfirmation) {
            showExitConfirmation = true
        } else if (workflowState.dismiss.shouldDismiss) {
            workflowState.dismiss.draftPersistenceValue?.let(inputViewModel::updateDraft)
            applyWorkflowCleanup(workflowState.cleanup)
            inputViewModel.clearPersistedEditorContent(editorSessionKey)
            onDismissRequest()
        }
    }

    fun submit(collectCoordinates: Boolean = true) = coroutineScope.launch {
        val location = if (collectCoordinates && locationState.shouldCollectCoordinatesOnSubmit) {
            val cached = prefetchedLocation?.takeIf { location ->
                isQualifiedLocation(location, locationConfig)
            }
            cached ?: getCurrentLocationBestEffort(
                context = context,
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

        val submitResult = executeMemoEditorSubmitWorkflow(
            submitState = submitState,
            sessionState = sessionState,
            currentContent = text.text,
            persistDraft = persistDraft,
            latitude = location?.latitude,
            longitude = location?.longitude,
            clearDraft = true,
            clearUploads = true,
            clearUploadTasks = true,
            stopLocationTracking = true,
            clearPrefetchedLocation = true,
            resetPendingLocationPermission = true,
            refreshLocalSnapshot = true,
            hideKeyboard = true,
        ) { request ->
            val submitOverride = onSubmitRequest
            if (submitOverride != null) {
                submitOverride(request)
            } else {
                inputViewModel.submitEditorRequest(
                    memoIdentifier = null,
                    visibility = site.lcyk.keer.data.model.MemoVisibility.PRIVATE,
                    request = request,
                )
            }
        }

        when (submitResult) {
            is site.lcyk.keer.viewmodel.MemoEditorSubmitWorkflowResult.Blocked -> {
                snackbarState.showSnackbar(submitResult.messageResId.string)
            }
            is site.lcyk.keer.viewmodel.MemoEditorSubmitWorkflowResult.Failed -> {
                snackbarState.showSnackbar(submitResult.message)
            }
            site.lcyk.keer.viewmodel.MemoEditorSubmitWorkflowResult.Skipped -> Unit
            is site.lcyk.keer.viewmodel.MemoEditorSubmitWorkflowResult.Succeeded -> {
                applyFieldsState(submitResult.workflowState.completion.fields)
                submitResult.workflowState.completion.draftPersistenceValue?.let(inputViewModel::updateDraft)
                applyWorkflowCleanup(submitResult.workflowState.cleanup)
                inputViewModel.clearPersistedEditorContent(editorSessionKey)
                onDismissRequest()
            }
        }
    }

    val locationPermissionWorkflow = rememberMemoEditorLocationPermissionWorkflowState(
        context = context,
        locationState = locationState,
        onPermissionGranted = {
            startLocationPrefetch(force = true)
        },
        onSubmit = { collectCoordinates ->
            if (!enableLocation) {
                submit(collectCoordinates = false)
            } else {
                submit(collectCoordinates = collectCoordinates)
            }
        },
    )
    resetPendingLocationPermissionRequest = locationPermissionWorkflow.resetPendingSubmitRequest

    val importWorkflow = rememberMemoEditorImportWorkflowState(
        context = context,
        inputViewModel = inputViewModel,
        uploadEntryState = uploadWorkflowState.entry,
        snackbarHostState = snackbarState,
        focusRequester = focusRequester,
        keyboardController = keyboardController,
    )

    BackHandler(enabled = visible) {
        dismissComposer()
    }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = tween(durationMillis = 90, easing = quickComposerEasing)),
        exit = fadeOut(animationSpec = tween(durationMillis = 80, easing = quickComposerEasing)),
        modifier = Modifier.fillMaxSize()
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.24f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) {
                    dismissComposer()
                }
        )
    }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = tween(durationMillis = 110, easing = quickComposerEasing)) +
            slideInVertically(
                animationSpec = tween(durationMillis = 145, easing = quickComposerEasing),
                initialOffsetY = { fullHeight -> fullHeight / 3 }
            ),
        exit = fadeOut(animationSpec = tween(durationMillis = 90, easing = quickComposerEasing)) +
            slideOutVertically(
                animationSpec = tween(durationMillis = 120, easing = quickComposerEasing),
                targetOffsetY = { fullHeight -> fullHeight / 3 }
            ),
        modifier = Modifier.fillMaxSize()
    ) {
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.BottomCenter
        ) {
            val density = LocalDensity.current
            val imeBottomInsetPx = WindowInsets.ime.getBottom(density)
            val availableContainerHeight = maxHeight - quickComposerSurfaceVerticalPadding
            val composerMaxHeight = availableContainerHeight
                .coerceAtLeast(quickComposerCompactContainerHeight)
            val composerMinHeight = minOf(quickComposerDefaultMinContainerHeight, composerMaxHeight)

            val editorVerticalPadding = quickComposerEditorPadding.calculateTopPadding() +
                quickComposerEditorPadding.calculateBottomPadding()
            val editorHeightBudget = composerMaxHeight - quickComposerBottomBarHeight - editorVerticalPadding
            val editorMaxHeight = editorHeightBudget
                .coerceAtLeast(quickComposerCompactEditorHeight)
            val editorMinHeight = minOf(quickComposerDefaultMinEditorHeight, editorMaxHeight)

            val lineHeight = MaterialTheme.typography.bodyLarge.lineHeight
            val editorLineHeight = if (lineHeight.isSpecified) {
                with(density) { lineHeight.toDp() }
            } else {
                quickComposerFallbackLineHeight
            }.coerceAtLeast(quickComposerFallbackLineHeight)
            val maxEditorLines = (editorMaxHeight / editorLineHeight)
                .toInt()
                .coerceAtLeast(quickComposerMinEditorLines)

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 10.dp)
                    .offset { IntOffset(x = 0, y = -imeBottomInsetPx) }
                    .navigationBarsPadding(),
                shape = quickComposerShape,
                tonalElevation = 6.dp,
                shadowElevation = 16.dp,
                color = MaterialTheme.colorScheme.surfaceContainerHigh
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = composerMinHeight, max = composerMaxHeight)
                        .clip(quickComposerShape)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) {}
                ) {
                    SurfaceHydrationLine(
                        hydrationState = draftScreenState.hydrationState,
                        modifier = Modifier.fillMaxWidth()
                    )
                    MemoInputEditor(
                        modifier = Modifier.fillMaxWidth(),
                        text = text,
                        onTextChange = { updated ->
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
                                    return@MemoInputEditor
                                }
                            }
                            text = updated
                        },
                        focusRequester = focusRequester,
                        editorPadding = quickComposerEditorPadding,
                        fillAvailableHeight = false,
                        editorMinHeight = editorMinHeight,
                        editorMaxHeight = editorMaxHeight,
                        minLines = quickComposerMinEditorLines,
                        maxLines = maxEditorLines,
                        validMimeTypePrefixes = validMimeTypePrefixes,
                        onDroppedText = { droppedText ->
                            text = text.copy(text = text.text + droppedText)
                        },
                        uploadsState = uploadWorkflowState.uploads,
                        onRemoveUploadResource = { resource -> inputViewModel.removeResourceFromDraft(resource) },
                        onCancelUploadTask = inputViewModel::cancelUploadTask,
                        onCancelActiveUploadTasks = inputViewModel::cancelActiveUploadTasks,
                        onRetryFailedUploadTasks = inputViewModel::retryFailedUploadTasks,
                        onClearFailedUploadTasks = inputViewModel::clearFailedUploadTasks,
                        onRetryUploadTask = inputViewModel::retryUploadTask,
                        onDismissUploadTask = inputViewModel::dismissUploadTask,
                    )
                    MemoInputBottomBar(
                        selectedTags = selectedTags,
                        selectedTagCount = normalizedSelectedTags.size,
                        selectedCollaborators = selectedCollaborators,
                        onTagSelectorClick = { showTagSelector = true },
                        onTagRemove = { tagToRemove ->
                            if (normalizedForcedTags.any { it == normalizeTagName(tagToRemove) }) {
                                return@MemoInputBottomBar
                            }
                            selectedTags = normalizeTagList(
                                selectedTags.filterNot { existing ->
                                    normalizeTagName(existing) == normalizeTagName(tagToRemove)
                                }
                            )
                        },
                        onCollaboratorSelectorClick = { showCollaboratorSelector = true },
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
                        },
                        compact = true,
                        trailingAction = {
                            IconButton(
                                enabled = submitState.canSubmit,
                                onClick = { locationPermissionWorkflow.attemptSubmit() },
                                modifier = Modifier.size(40.dp)
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.Send,
                                    contentDescription = R.string.post.string
                                )
                            }
                        },
                    )
                }
            }
            SnackbarHost(
                hostState = snackbarState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 16.dp, vertical = 20.dp)
            )
        }
    }

    if (visible && showTagSelector) {
        val selectorTags = availableTags
            ?: memosViewModel.tags
                .toList()
                .filterNot(::isCollaboratorTag)
                .filterNot(::isQuoteTag)
        MemoTagSelectorDialog(
            availableTags = selectorTags,
            selectedTags = selectedTags,
            onSelectedTagsChange = { selectedTags = normalizeTagList(normalizedForcedTags + it) },
            onDismiss = { showTagSelector = false }
        )
    }

    if (visible && showCollaboratorSelector) {
        MemoCollaboratorDialog(
            availableCollaborators = friends,
            selectedCollaborators = selectedCollaborators,
            onSelectedCollaboratorsChange = { selectedCollaborators = it },
            onDismiss = { showCollaboratorSelector = false }
        )
    }

    if (visible && showExitConfirmation) {
        SaveChangesDialog(
            onSave = {
                showExitConfirmation = false
                locationPermissionWorkflow.attemptSubmit()
            },
            onDiscard = {
                showExitConfirmation = false
                dismissComposer(forceDiscard = true)
            },
            onDismiss = {
                showExitConfirmation = false
            }
        )
    }

    LaunchedEffect(visible) {
        if (!visible) {
            showTagSelector = false
            showCollaboratorSelector = false
            showExitConfirmation = false
            applyWorkflowCleanup(
                MemoEditorWorkflowCleanupState(
                    stopLocationTracking = true,
                    clearPrefetchedLocation = true,
                    resetPendingLocationPermission = true,
                )
            )
            if (shouldHideKeyboardAfterDismiss) {
                delay(quickComposerKeyboardHideDelayMillis)
                keyboardController?.hide()
                shouldHideKeyboardAfterDismiss = false
            } else {
                keyboardController?.hide()
            }
            return@LaunchedEffect
        }

        val restoreWorkflowState = buildMemoEditorRestoreWorkflowState(
            restoreState = buildMemoEditorEffectiveRestoreState(
                baseRestoreState = draftScreenState.initialRestoreState,
                persistedContentState = persistedContentState,
                expectedSessionKey = editorSessionKey,
                hasPersistedWorkflowPayload = uploadResources.isNotEmpty() || uploadTasks.isNotEmpty(),
            ),
            uploadResources = if (persistedContentState.sessionKey == editorSessionKey) {
                uploadResources
            } else {
                emptyList()
            },
        )
        applyWorkflowCleanup(restoreWorkflowState.cleanup)
        memosViewModel.loadTags()
        coroutineScope.launch {
            userStateViewModel.refreshFriends()
        }
        applyFieldsState(restoreWorkflowState.fields)
        withFrameNanos { }
        withFrameNanos { }
        focusRequester.requestFocus()
        keyboardController?.show()
        delay(quickComposerKeyboardHideDelayMillis)
        startLocationPrefetch(force = true)
    }

    LaunchedEffect(
        visible,
        text.text,
        normalizedForcedTags,
        normalizedSelectedTags,
        normalizedSelectedCollaborators,
    ) {
        if (!visible) {
            return@LaunchedEffect
        }
        inputViewModel.updatePersistedEditorContent(
            sessionKey = editorSessionKey,
            content = text.text,
            selectedTags = normalizedForcedTags + normalizedSelectedTags,
            selectedCollaborators = normalizedSelectedCollaborators,
        )
    }
}
