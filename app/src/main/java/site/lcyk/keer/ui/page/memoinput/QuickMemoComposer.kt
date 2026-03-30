package site.lcyk.keer.ui.page.memoinput

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Looper
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.CaptureVideo
import androidx.activity.result.contract.ActivityResultContracts.OpenDocument
import androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions
import androidx.activity.result.contract.ActivityResultContracts.TakePicture
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.skydoves.sandwich.suspendOnSuccess
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import site.lcyk.keer.KeerFileProvider
import site.lcyk.keer.R
import site.lcyk.keer.ext.string
import site.lcyk.keer.ext.suspendOnErrorMessage
import site.lcyk.keer.ui.component.SurfaceHydrationLine
import site.lcyk.keer.util.isCollaboratorTag
import site.lcyk.keer.util.isQuoteTag
import site.lcyk.keer.util.mergeTagsWithCollaboratorsAndQuote
import site.lcyk.keer.util.normalizeCollaboratorId
import site.lcyk.keer.util.normalizeTagList
import site.lcyk.keer.util.normalizeTagName
import site.lcyk.keer.viewmodel.LocalMemos
import site.lcyk.keer.viewmodel.LocalUserState
import site.lcyk.keer.viewmodel.MemoInputViewModel
import site.lcyk.keer.viewmodel.buildMemoEditorCanSubmit
import site.lcyk.keer.viewmodel.buildDraftEditorScreenState
import site.lcyk.keer.viewmodel.MemoEditorWorkflowCleanupState
import site.lcyk.keer.viewmodel.buildMemoEditorCompletionWorkflowState
import site.lcyk.keer.viewmodel.buildMemoEditorBaseline
import site.lcyk.keer.viewmodel.buildMemoEditorDismissWorkflowState
import site.lcyk.keer.viewmodel.buildMemoEditorDirtyState
import site.lcyk.keer.viewmodel.buildMemoEditorRestoreWorkflowState
import site.lcyk.keer.viewmodel.buildMemoEditorUploadWorkflowState
import kotlin.coroutines.resume

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
private const val quickComposerMinEditorLines = 4
private val quickComposerShape = RoundedCornerShape(16.dp)

data class QuickMemoSubmitRequest(
    val content: String,
    val tags: List<String>,
    val resourceIdentifiers: List<String>,
    val latitude: Double?,
    val longitude: Double?,
)

@Composable
fun QuickMemoComposer(
    visible: Boolean,
    onDismissRequest: () -> Unit,
    forcedTags: List<String> = emptyList(),
    availableTags: List<String>? = null,
    enableLocation: Boolean = true,
    persistDraft: Boolean = true,
    onSubmitRequest: (suspend (QuickMemoSubmitRequest) -> String?)? = null,
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
    val locationPermissions = remember {
        arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
    }
    var pendingSubmitAfterLocationPermission by remember { mutableStateOf(false) }
    val normalizedForcedTags = remember(forcedTags) { normalizeTagList(forcedTags) }
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
        inputViewModel.imageUploadSectionExpanded,
        inputViewModel.attachmentUploadSectionExpanded,
        inputViewModel.taskUploadSectionExpanded,
    ) {
        buildMemoEditorUploadWorkflowState(
            memoIdentifier = null,
            uploadResources = uploadResources,
            uploadTasks = uploadTasks,
            highlightedResourceIdentifiers = recentlyUploadedResourceIdentifiers,
            focusDelayMillis = 120L,
            showKeyboardAfterUpload = true,
            imageSectionExpanded = inputViewModel.imageUploadSectionExpanded,
            attachmentSectionExpanded = inputViewModel.attachmentUploadSectionExpanded,
            taskSectionExpanded = inputViewModel.taskUploadSectionExpanded,
        )
    }
    MemoUploadFeedbackSnackbarEffect(
        hostState = snackbarState,
        feedbackState = uploadWorkflowState.uploads.feedback,
    )
    val validMimeTypePrefixes = remember { setOf("text/") }
    val applyFieldsState: (site.lcyk.keer.viewmodel.MemoEditorFieldsState) -> Unit = { fieldsState ->
        text = TextFieldValue(fieldsState.content, TextRange(fieldsState.content.length))
        selectedTags = fieldsState.selectedTags
        selectedCollaborators = fieldsState.selectedCollaborators
        editorBaseline = fieldsState.baseline
    }
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
            pendingSubmitAfterLocationPermission = false
        }
        if (cleanup.refreshLocalSnapshot) {
            coroutineScope.launch {
                memosViewModel.refreshLocalSnapshot()
            }
        }
        if (cleanup.hideKeyboard) {
            keyboardController?.hide()
        }
    }

    fun startLocationPrefetch(force: Boolean = false) {
        if (!enableLocation) {
            return
        }
        if (!hasLocationPermission(context)) {
            return
        }

        if (force) {
            stopLocationTracking?.invoke()
            stopLocationTracking = null
        } else if (stopLocationTracking != null) {
            return
        }

        val stopCallbacks = mutableListOf<() -> Unit>()
        startPlatformLocationTracking(context) { candidate ->
            if (isLocationFresh(candidate)) {
                prefetchedLocation = pickMoreAccurateLocation(prefetchedLocation, candidate)
            }
        }?.let(stopCallbacks::add)

        startGnssLocationTracking(context) { candidate ->
            if (isLocationFresh(candidate)) {
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
                    maxWaitMillis = QUICK_PREFETCH_LOCATION_TIMEOUT_MILLIS
                )
                if (location != null && isQualifiedLocation(location)) {
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
            onDismissRequest()
            return
        }

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
            onDismissRequest()
        }
    }

    fun submit(collectCoordinates: Boolean = true) = coroutineScope.launch {
        if (uploadWorkflowState.uploads.hasActiveUpload) {
            snackbarState.showSnackbar(R.string.upload_in_progress_wait.string)
            return@launch
        }

        val payload = text.text.trim()
        if (payload.isBlank() && uploadWorkflowState.uploads.resources.isEmpty()) {
            return@launch
        }

        val mergedTags = mergeTagsWithCollaboratorsAndQuote(
            normalizeTagList(normalizedForcedTags + normalizedSelectedTags),
            normalizedSelectedCollaborators,
            quoteDescriptor = null,
        )

        val location = if (enableLocation && collectCoordinates && hasLocationPermission(context)) {
            val cached = prefetchedLocation?.takeIf(::isQualifiedLocation)
            cached ?: getCurrentLocationBestEffort(
                context = context,
                maxWaitMillis = QUICK_SUBMIT_LOCATION_TIMEOUT_MILLIS
            )
                ?.takeIf(::isQualifiedLocation)
                ?.also { fresh ->
                    prefetchedLocation = pickMoreAccurateLocation(prefetchedLocation, fresh)
                }
        } else {
            null
        }

        val request = QuickMemoSubmitRequest(
            content = payload,
            tags = mergedTags,
            resourceIdentifiers = uploadWorkflowState.uploads.resourceIdentifiers,
            latitude = location?.latitude,
            longitude = location?.longitude,
        )

        suspend fun clearComposerStateAfterSuccess() {
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
                refreshLocalSnapshot = true,
                hideKeyboard = true,
            )
            applyFieldsState(workflowState.completion.fields)
            workflowState.completion.draftPersistenceValue?.let(inputViewModel::updateDraft)
            applyWorkflowCleanup(workflowState.cleanup)
            onDismissRequest()
        }

        val submitOverride = onSubmitRequest
        if (submitOverride != null) {
            val errorMessage = submitOverride(request)
            if (errorMessage == null) {
                clearComposerStateAfterSuccess()
            } else {
                snackbarState.showSnackbar(errorMessage)
            }
            return@launch
        }

        inputViewModel.createMemo(
            content = request.content,
            visibility = site.lcyk.keer.data.model.MemoVisibility.PRIVATE,
            tags = request.tags,
            latitude = request.latitude,
            longitude = request.longitude,
        ).suspendOnSuccess {
            clearComposerStateAfterSuccess()
        }.suspendOnErrorMessage { message ->
            snackbarState.showSnackbar(message)
        }
    }

    val requestLocationPermissions = rememberLauncherForActivityResult(RequestMultiplePermissions()) { _ ->
        if (hasLocationPermission(context)) {
            startLocationPrefetch(force = true)
        }
        if (pendingSubmitAfterLocationPermission) {
            pendingSubmitAfterLocationPermission = false
            submit()
        }
    }

    fun attemptSubmit() {
        if (!enableLocation) {
            submit(collectCoordinates = false)
            return
        }
        if (!hasLocationPermission(context)) {
            pendingSubmitAfterLocationPermission = true
            requestLocationPermissions.launch(locationPermissions)
        } else {
            submit()
        }
    }

    fun uploadResource(uri: Uri) = coroutineScope.launch {
        inputViewModel.upload(uri, memoIdentifier = uploadWorkflowState.entry.targetMemoIdentifier).suspendOnSuccess {
            delay(uploadWorkflowState.entry.focusDelayMillis)
            focusRequester.requestFocus()
            if (uploadWorkflowState.entry.showKeyboardAfterUpload) {
                keyboardController?.show()
            }
        }.suspendOnErrorMessage { message ->
            snackbarState.showSnackbar(message)
        }
    }

    val pickImage = rememberLauncherForActivityResult(OpenDocument()) { uri ->
        uri?.let(::uploadResource)
    }

    var photoImageUri by remember { mutableStateOf<Uri?>(null) }
    val takePhoto = rememberLauncherForActivityResult(TakePicture()) { success ->
        if (success) {
            photoImageUri?.let(::uploadResource)
        }
    }

    fun launchTakePhoto() {
        try {
            val uri = KeerFileProvider.getImageUri(context)
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
            videoUri?.let(::uploadResource)
        }
    }

    fun launchCaptureVideo() {
        try {
            val uri = KeerFileProvider.getVideoUri(context)
            videoUri = uri
            captureVideo.launch(uri)
        } catch (e: ActivityNotFoundException) {
            coroutineScope.launch {
                snackbarState.showSnackbar(e.localizedMessage ?: R.string.unable_to_record_video.string)
            }
        }
    }

    val pickAttachment = rememberLauncherForActivityResult(OpenDocument()) { uri ->
        uri?.let(::uploadResource)
    }

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
            modifier = Modifier
                .fillMaxSize()
                .imePadding(),
            contentAlignment = Alignment.BottomCenter
        ) {
            val density = LocalDensity.current
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
                    .navigationBarsPadding(),
                shape = quickComposerShape,
                tonalElevation = 6.dp,
                shadowElevation = 16.dp,
                color = MaterialTheme.colorScheme.surfaceContainerHigh
            ) {
                Column(
                    modifier = Modifier
                        .animateContentSize(
                            animationSpec = tween(durationMillis = 150, easing = quickComposerEasing)
                        )
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
                        onClearImageUploadResources = inputViewModel::clearImageUploadResources,
                        onClearAttachmentUploadResources = inputViewModel::clearAttachmentUploadResources,
                        onToggleImageUploadSection = inputViewModel::updateImageUploadSectionExpanded,
                        onToggleAttachmentUploadSection = inputViewModel::updateAttachmentUploadSectionExpanded,
                        onCancelUploadTask = inputViewModel::cancelUploadTask,
                        onCancelActiveUploadTasks = inputViewModel::cancelActiveUploadTasks,
                        onRetryFailedUploadTasks = inputViewModel::retryFailedUploadTasks,
                        onClearFailedUploadTasks = inputViewModel::clearFailedUploadTasks,
                        onRetryUploadTask = inputViewModel::retryUploadTask,
                        onDismissUploadTask = inputViewModel::dismissUploadTask,
                        onToggleTaskUploadSection = inputViewModel::updateTaskUploadSectionExpanded,
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
                        onPickImage = {
                            pickImage.launch(arrayOf("image/*", "video/*"))
                        },
                        onPickAttachment = {
                            pickAttachment.launch(arrayOf("*/*"))
                        },
                        onTakePhoto = ::launchTakePhoto,
                        onTakeVideo = ::launchCaptureVideo,
                        onFormat = { format ->
                            text = applyMarkdownFormatToText(text, format)
                        },
                        compact = true,
                        trailingAction = {
                            IconButton(
                                enabled = buildMemoEditorCanSubmit(
                                    content = text.text,
                                    uploadsState = uploadWorkflowState.uploads,
                                ),
                                onClick = { attemptSubmit() },
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
                attemptSubmit()
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
                    hideKeyboard = true,
                )
            )
            return@LaunchedEffect
        }

        val restoreWorkflowState = buildMemoEditorRestoreWorkflowState(
            restoreState = draftScreenState.initialRestoreState,
        )
        applyWorkflowCleanup(restoreWorkflowState.cleanup)
        memosViewModel.loadTags()
        userStateViewModel.refreshFriends()
        startLocationPrefetch(force = true)
        applyFieldsState(restoreWorkflowState.fields)
        withFrameNanos { }
        withFrameNanos { }
        focusRequester.requestFocus()
        keyboardController?.show()
    }
}

private fun hasLocationPermission(context: Context): Boolean {
    val fineLocationGranted = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED
    val coarseLocationGranted = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_COARSE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED
    return fineLocationGranted || coarseLocationGranted
}

@SuppressLint("MissingPermission")
private fun startPlatformLocationTracking(
    context: Context,
    onLocation: (Location) -> Unit
): (() -> Unit)? {
    val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        ?: return null
    val providers = resolveRealtimeTrackingProviders(locationManager)
    if (providers.isEmpty()) {
        return null
    }

    return runCatching<() -> Unit> {
        val listener = LocationListener { location ->
            if (isLocationFresh(location)) {
                onLocation(location)
            }
        }

        providers.forEach { provider ->
            locationManager.requestLocationUpdates(
                provider,
                QUICK_NETWORK_TRACKING_MIN_TIME_MILLIS,
                QUICK_NETWORK_TRACKING_MIN_DISTANCE_METERS,
                listener,
                Looper.getMainLooper()
            )
            runCatching {
                locationManager.getLastKnownLocation(provider)
            }.getOrNull()?.let { candidate ->
                if (isLocationFresh(candidate)) {
                    onLocation(candidate)
                }
            }
        }

        val stopTracking: () -> Unit = {
            locationManager.removeUpdates(listener)
        }
        stopTracking
    }.getOrNull()
}

@SuppressLint("MissingPermission")
private fun startGnssLocationTracking(
    context: Context,
    onLocation: (Location) -> Unit
): (() -> Unit)? {
    if (!hasPreciseLocationPermission(context)) {
        return null
    }
    val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        ?: return null
    val gpsEnabled = runCatching {
        locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
    }.getOrDefault(false)
    if (!gpsEnabled) {
        return null
    }

    return runCatching<() -> Unit> {
        val listener = LocationListener { location ->
            if (isLocationFresh(location)) {
                onLocation(location)
            }
        }

        locationManager.requestLocationUpdates(
            LocationManager.GPS_PROVIDER,
            QUICK_GNSS_TRACKING_MIN_TIME_MILLIS,
            QUICK_GNSS_TRACKING_MIN_DISTANCE_METERS,
            listener,
            Looper.getMainLooper()
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                locationManager.getCurrentLocation(
                    LocationManager.GPS_PROVIDER,
                    null,
                    context.mainExecutor
                ) { location ->
                    if (location != null && isLocationFresh(location)) {
                        onLocation(location)
                    }
                }
            }
        }

        val stopTracking: () -> Unit = {
            locationManager.removeUpdates(listener)
        }
        stopTracking
    }.getOrNull()
}

@SuppressLint("MissingPermission")
private suspend fun getCurrentLocationBestEffort(
    context: Context,
    maxWaitMillis: Long
): Location? {
    val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        ?: return null
    val preferPreciseProvider = hasPreciseLocationPermission(context)
    val deadlineMillis = System.currentTimeMillis() + maxWaitMillis

    var bestLocation = getBestLastKnownLocation(locationManager, preferPreciseProvider)
    if (bestLocation != null && shouldStopSearching(bestLocation, preferPreciseProvider)) {
        return bestLocation.takeIf(::isQualifiedLocation)
    }

    val providers = resolveLocationProviders(
        locationManager = locationManager,
        preferPreciseProvider = preferPreciseProvider,
        fastFirst = true
    )
    for (provider in providers) {
        val remainingMillis = remainingMillis(deadlineMillis)
        if (remainingMillis <= 0L) {
            break
        }

        val current = withTimeoutOrNull(minOf(providerTimeoutMillis(provider), remainingMillis)) {
            getCurrentLocationFromProvider(context, locationManager, provider)
        } ?: runCatching {
            locationManager.getLastKnownLocation(provider)
        }.getOrNull()

        if (current == null || !isLocationFresh(current)) {
            continue
        }
        bestLocation = pickMoreAccurateLocation(bestLocation, current)
        if (shouldStopSearching(bestLocation, preferPreciseProvider)) {
            break
        }
    }
    return bestLocation?.takeIf(::isQualifiedLocation)
}

@SuppressLint("MissingPermission")
private fun getBestLastKnownLocation(
    locationManager: LocationManager,
    preferPreciseProvider: Boolean
): Location? {
    var bestLocation: Location? = null
    val providers = resolveLocationProviders(
        locationManager = locationManager,
        preferPreciseProvider = preferPreciseProvider,
        fastFirst = true
    )
    for (provider in providers) {
        val candidate = runCatching {
            locationManager.getLastKnownLocation(provider)
        }.getOrNull() ?: continue
        if (!isLocationFresh(candidate)) {
            continue
        }
        bestLocation = pickMoreAccurateLocation(bestLocation, candidate)
    }
    return bestLocation
}

private fun resolveRealtimeTrackingProviders(locationManager: LocationManager): List<String> {
    val preferredOrder = listOf(
        LocationManager.NETWORK_PROVIDER,
        LocationManager.PASSIVE_PROVIDER
    )
    val enabledProviders = preferredOrder.filter { provider ->
        runCatching { locationManager.isProviderEnabled(provider) }.getOrDefault(false)
    }
    if (enabledProviders.isNotEmpty()) {
        return enabledProviders
    }
    return preferredOrder.filter { provider ->
        runCatching { locationManager.allProviders.contains(provider) }.getOrDefault(false)
    }
}

private fun resolveLocationProviders(
    locationManager: LocationManager,
    preferPreciseProvider: Boolean,
    fastFirst: Boolean = false
): List<String> {
    val preferredOrder = when {
        preferPreciseProvider && fastFirst -> listOf(
            LocationManager.NETWORK_PROVIDER,
            LocationManager.GPS_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        )
        preferPreciseProvider -> listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        )
        else -> listOf(
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        )
    }
    val enabledProviders = preferredOrder.filter { provider ->
        runCatching { locationManager.isProviderEnabled(provider) }.getOrDefault(false)
    }
    if (enabledProviders.isNotEmpty()) {
        return enabledProviders
    }
    return preferredOrder.filter { provider ->
        runCatching { locationManager.allProviders.contains(provider) }.getOrDefault(false)
    }
}

@SuppressLint("MissingPermission")
private suspend fun getCurrentLocationFromProvider(
    context: Context,
    locationManager: LocationManager,
    provider: String
): Location? {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        return suspendCancellableCoroutine { continuation ->
            runCatching {
                locationManager.getCurrentLocation(provider, null, context.mainExecutor) { location ->
                    if (continuation.isActive) {
                        continuation.resume(location)
                    }
                }
            }.onFailure {
                if (continuation.isActive) {
                    continuation.resume(null)
                }
            }
        }
    }
    return runCatching {
        locationManager.getLastKnownLocation(provider)
    }.getOrNull()
}

private fun providerTimeoutMillis(provider: String): Long {
    return when (provider) {
        LocationManager.GPS_PROVIDER -> 4_000L
        LocationManager.NETWORK_PROVIDER -> 2_000L
        else -> 1_200L
    }
}

private fun remainingMillis(deadlineMillis: Long): Long {
    return (deadlineMillis - System.currentTimeMillis()).coerceAtLeast(0L)
}

private fun pickMoreAccurateLocation(currentBest: Location?, candidate: Location): Location {
    val best = currentBest ?: return candidate
    val candidateAccuracy = if (candidate.accuracy > 0f) candidate.accuracy else Float.MAX_VALUE
    val bestAccuracy = if (best.accuracy > 0f) best.accuracy else Float.MAX_VALUE
    return when {
        candidateAccuracy + 12f < bestAccuracy -> candidate
        candidate.time > best.time + 45_000L && candidateAccuracy <= bestAccuracy + 20f -> candidate
        else -> best
    }
}

private fun hasPreciseLocationPermission(context: Context): Boolean {
    return ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED
}

private fun shouldStopSearching(location: Location, preferPreciseProvider: Boolean): Boolean {
    if (!location.hasAccuracy()) {
        return false
    }
    val target = if (preferPreciseProvider) {
        QUICK_TARGET_PRECISE_LOCATION_ACCURACY_METERS
    } else {
        QUICK_TARGET_COARSE_LOCATION_ACCURACY_METERS
    }
    return location.accuracy <= target
}

private fun isLocationFresh(location: Location): Boolean {
    if (location.time <= 0L) {
        return false
    }
    val ageMillis = System.currentTimeMillis() - location.time
    return ageMillis in 0..QUICK_MAX_LOCATION_AGE_MILLIS
}

private fun isQualifiedLocation(location: Location): Boolean {
    return location.hasAccuracy() &&
        location.accuracy <= QUICK_MAX_ACCEPTABLE_LOCATION_ACCURACY_METERS &&
        isLocationFresh(location)
}

private const val QUICK_TARGET_PRECISE_LOCATION_ACCURACY_METERS = 25f
private const val QUICK_TARGET_COARSE_LOCATION_ACCURACY_METERS = 80f
private const val QUICK_MAX_ACCEPTABLE_LOCATION_ACCURACY_METERS = 100f
private const val QUICK_MAX_LOCATION_AGE_MILLIS = 2 * 60 * 1000L
private const val QUICK_SUBMIT_LOCATION_TIMEOUT_MILLIS = 650L
private const val QUICK_PREFETCH_LOCATION_TIMEOUT_MILLIS = 9_000L
private const val QUICK_NETWORK_TRACKING_MIN_TIME_MILLIS = 1_500L
private const val QUICK_NETWORK_TRACKING_MIN_DISTANCE_METERS = 0f
private const val QUICK_GNSS_TRACKING_MIN_TIME_MILLIS = 800L
private const val QUICK_GNSS_TRACKING_MIN_DISTANCE_METERS = 0f
