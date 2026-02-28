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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
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
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavHostController
import com.skydoves.sandwich.suspendOnSuccess
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import site.lcyk.keer.KeerFileProvider
import site.lcyk.keer.R
import site.lcyk.keer.data.local.entity.ResourceEntity
import site.lcyk.keer.ext.popBackStackIfLifecycleIsResumed
import site.lcyk.keer.ext.string
import site.lcyk.keer.ext.suspendOnErrorMessage
import site.lcyk.keer.ui.page.memoinput.MarkdownFormat
import site.lcyk.keer.ui.page.memoinput.MemoCollaboratorDialog
import site.lcyk.keer.ui.page.memoinput.MemoInputBottomBar
import site.lcyk.keer.ui.page.memoinput.MemoInputEditor
import site.lcyk.keer.ui.page.memoinput.MemoInputTopBar
import site.lcyk.keer.ui.page.memoinput.MemoTagSelectorDialog
import site.lcyk.keer.ui.page.memoinput.SaveChangesDialog
import site.lcyk.keer.ui.page.memoinput.applyMarkdownFormatToText
import site.lcyk.keer.ui.page.memoinput.handleEnterInText
import site.lcyk.keer.ui.page.memoinput.toggleTodoItemInText
import site.lcyk.keer.util.mergeTagsWithCollaborators
import site.lcyk.keer.util.normalizeCollaboratorId
import site.lcyk.keer.util.normalizeTagList
import site.lcyk.keer.viewmodel.GroupChatViewModel
import site.lcyk.keer.viewmodel.MemoInputViewModel

@Composable
fun GroupMemoInputPage(
    navController: NavHostController,
    groupId: String,
    groupViewModel: GroupChatViewModel = hiltViewModel(),
    inputViewModel: MemoInputViewModel = hiltViewModel()
) {
    val focusRequester = remember { FocusRequester() }
    val coroutineScope = rememberCoroutineScope()
    val snackbarState = remember { SnackbarHostState() }
    val lifecycleOwner = LocalLifecycleOwner.current
    val groupTags by groupViewModel.groupTags.collectAsState()
    val errorMessage by groupViewModel.errorMessage.collectAsState()

    var initialContent by remember { mutableStateOf("") }
    var initialTags by remember { mutableStateOf(emptyList<String>()) }
    var initialCollaborators by remember { mutableStateOf(emptyList<String>()) }

    var text by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue("", TextRange(0)))
    }
    var selectedTags by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var selectedCollaborators by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var showTagSelector by remember { mutableStateOf(false) }
    var showCollaboratorSelector by remember { mutableStateOf(false) }
    var showExitConfirmation by remember { mutableStateOf(false) }

    val validMimeTypePrefixes = remember { setOf("text/") }
    val normalizedSelectedTags = remember(selectedTags) { normalizeTagList(selectedTags) }
    val normalizedSelectedCollaborators = remember(selectedCollaborators) {
        selectedCollaborators
            .map(::normalizeCollaboratorId)
            .filter { it.isNotEmpty() }
            .distinct()
    }

    fun handleExit() {
        if (inputViewModel.hasActiveUpload()) {
            coroutineScope.launch {
                snackbarState.showSnackbar(R.string.upload_in_progress_wait.string)
            }
            return
        }
        if (
            text.text != initialContent ||
            normalizedSelectedTags != initialTags ||
            normalizedSelectedCollaborators != initialCollaborators ||
            inputViewModel.uploadResources.isNotEmpty()
        ) {
            showExitConfirmation = true
        } else {
            navController.popBackStackIfLifecycleIsResumed(lifecycleOwner)
        }
    }

    fun submit() = coroutineScope.launch {
        if (inputViewModel.hasActiveUpload()) {
            snackbarState.showSnackbar(R.string.upload_in_progress_wait.string)
            return@launch
        }

        val mergedTags = mergeTagsWithCollaborators(
            normalizedSelectedTags,
            normalizedSelectedCollaborators
        )
        val plainTags = normalizeTagList(normalizedSelectedTags)
        val existingSet = groupTags.map { it.trim().lowercase() }.toSet()
        val missingGroupTags = plainTags.filterNot { it.lowercase() in existingSet }
        for (tag in missingGroupTags) {
            groupViewModel.addGroupTag(groupId, tag)
        }

        val payload = buildGroupMemoContent(
            text.text,
            inputViewModel.uploadResources.toList()
        )
        if (payload.isBlank()) {
            return@launch
        }

        val sent = groupViewModel.sendGroupMemo(groupId, payload, mergedTags)
        if (!sent) {
            snackbarState.showSnackbar(errorMessage ?: R.string.sync_failed.string)
            return@launch
        }

        text = TextFieldValue("", TextRange(0))
        selectedTags = emptyList()
        selectedCollaborators = emptyList()
        inputViewModel.uploadResources.clear()
        inputViewModel.uploadTasks.clear()
        navController.popBackStackIfLifecycleIsResumed(lifecycleOwner)
    }

    fun uploadResource(uri: Uri) = coroutineScope.launch {
        inputViewModel.upload(uri, memoIdentifier = null).suspendOnSuccess {
            delay(300)
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
                snackbarState.showSnackbar(e.localizedMessage ?: "Unable to take picture.")
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
                isEditMode = false,
                canSubmit = (text.text.isNotEmpty() || inputViewModel.uploadResources.isNotEmpty()) && !inputViewModel.hasActiveUpload(),
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
        MemoInputEditor(
            modifier = Modifier.padding(innerPadding),
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
            validMimeTypePrefixes = validMimeTypePrefixes,
            onDroppedText = { droppedText ->
                text = text.copy(text = text.text + droppedText)
            },
            uploadResources = inputViewModel.uploadResources.toList(),
            inputViewModel = inputViewModel,
            uploadTasks = inputViewModel.uploadTasks.toList(),
            onDismissUploadTask = { taskId ->
                inputViewModel.dismissUploadTask(taskId)
            }
        )
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
                inputViewModel.uploadResources.clear()
                inputViewModel.uploadTasks.clear()
                navController.popBackStackIfLifecycleIsResumed(lifecycleOwner)
            },
            onDismiss = {
                showExitConfirmation = false
            }
        )
    }

    LaunchedEffect(groupId) {
        inputViewModel.uploadResources.clear()
        inputViewModel.uploadTasks.clear()
        groupViewModel.loadGroupTags(groupId)
        initialContent = ""
        initialTags = emptyList()
        initialCollaborators = emptyList()
        delay(300)
        focusRequester.requestFocus()
    }

    DisposableEffect(Unit) {
        onDispose {
            inputViewModel.uploadTasks.clear()
        }
    }
}

private fun buildGroupMemoContent(
    content: String,
    resources: List<ResourceEntity>
): String {
    val trimmed = content.trim()
    if (resources.isEmpty()) {
        return trimmed
    }

    val links = resources.mapNotNull { resource ->
        val uri = resource.uri.trim()
        if (uri.isEmpty()) {
            null
        } else if (resource.mimeType?.startsWith("image/") == true) {
            "![]($uri)"
        } else {
            val label = resource.filename.ifBlank { uri }
            "[$label]($uri)"
        }
    }
    if (links.isEmpty()) {
        return trimmed
    }

    return buildString {
        if (trimmed.isNotEmpty()) {
            append(trimmed)
            append("\n\n")
        }
        append(links.joinToString("\n"))
    }.trim()
}
