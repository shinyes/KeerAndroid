package site.lcyk.keer.ui.page.group

import android.content.ActivityNotFoundException
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.CaptureVideo
import androidx.activity.result.contract.ActivityResultContracts.OpenDocument
import androidx.activity.result.contract.ActivityResultContracts.PickMultipleVisualMedia
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
import site.lcyk.keer.data.model.Memo
import site.lcyk.keer.ext.popBackStackIfLifecycleIsResumed
import site.lcyk.keer.ext.string
import site.lcyk.keer.ext.suspendOnErrorMessage
import site.lcyk.keer.ui.component.MemoQuoteReferenceCard
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
import site.lcyk.keer.ui.page.common.LocalRootNavController
import site.lcyk.keer.ui.page.common.navigateToMemoDetailPage
import site.lcyk.keer.util.MemoQuoteDescriptor
import site.lcyk.keer.util.MemoQuoteSourceKind
import site.lcyk.keer.util.buildMemoQuoteDescriptor
import site.lcyk.keer.util.extractCollaboratorIds
import site.lcyk.keer.util.mergeTagsWithCollaboratorsAndQuote
import site.lcyk.keer.util.normalizeCollaboratorId
import site.lcyk.keer.util.normalizeTagList
import site.lcyk.keer.util.resolveMemoFromQuoteDescriptor
import site.lcyk.keer.util.resolveMemoQuoteDescriptor
import site.lcyk.keer.util.storedMemoQuotePreviewOrNull
import site.lcyk.keer.util.stripCollaboratorTags
import site.lcyk.keer.util.stripQuoteTags
import site.lcyk.keer.util.toMemoEntityForCard
import site.lcyk.keer.util.toMemoQuotePreview
import site.lcyk.keer.viewmodel.GroupChatViewModel
import site.lcyk.keer.viewmodel.LocalMemos
import site.lcyk.keer.viewmodel.LocalUserState
import site.lcyk.keer.viewmodel.MemoInputViewModel

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

    var initialContent by remember { mutableStateOf("") }
    var initialTags by remember { mutableStateOf(emptyList<String>()) }
    var initialCollaborators by remember { mutableStateOf(emptyList<String>()) }
    var initialResourceIdentifiers by remember { mutableStateOf(emptyList<String>()) }
    var currentMemo by remember { mutableStateOf<site.lcyk.keer.data.local.entity.MemoEntity?>(null) }

    var text by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue("", TextRange(0)))
    }
    var selectedTags by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var selectedCollaborators by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var showTagSelector by remember { mutableStateOf(false) }
    var showCollaboratorSelector by remember { mutableStateOf(false) }
    var showExitConfirmation by remember { mutableStateOf(false) }
    val isEditMode = !memoId.isNullOrBlank()
    val existingQuoteDescriptor = remember(
        currentMemo?.quoteSourceKind,
        currentMemo?.quoteSource,
        currentMemo?.tags,
    ) {
        currentMemo?.resolveMemoQuoteDescriptor()
    }
    val requestedQuoteDescriptor = remember(quoteSourceMemoIdentifier) {
        val source = quoteSourceMemoIdentifier?.trim().orEmpty()
        if (source.isEmpty()) {
            null
        } else {
            MemoQuoteDescriptor(
                sourceKind = MemoQuoteSourceKind.LOCAL,
                source = source
            )
        }
    }
    val activeQuoteDescriptor = if (currentMemo != null) {
        existingQuoteDescriptor
    } else {
        requestedQuoteDescriptor
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
    val quotedMemo = remember(activeQuoteDescriptor, quoteMemoCandidates) {
        val descriptor = activeQuoteDescriptor ?: return@remember null
        memosViewModel.getMemoForDetail(descriptor.source)
            ?: resolveMemoFromQuoteDescriptor(
                descriptor = descriptor,
                memos = quoteMemoCandidates,
            )
    }
    val quotePreview = remember(
        quotedMemo?.content,
        quotedMemo?.resources,
        currentMemo?.quoteStatus,
        currentMemo?.quoteContentPreview,
        currentMemo?.quoteDate,
        currentMemo?.quoteHasAttachments,
    ) {
        quotedMemo?.toMemoQuotePreview() ?: currentMemo?.storedMemoQuotePreviewOrNull()
    }
    val quoteDescriptorForSubmit = remember(activeQuoteDescriptor, quotedMemo) {
        quotedMemo?.let(::buildMemoQuoteDescriptor) ?: activeQuoteDescriptor
    }

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
            inputViewModel.uploadResources.map { resource -> resource.remoteId ?: resource.identifier }.distinct() != initialResourceIdentifiers
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
        val currentResourceIdentifiers = inputViewModel.uploadResources
            .map { resource -> resource.remoteId ?: resource.identifier }
            .filter { identifier -> identifier.isNotBlank() }
            .distinct()
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

        text = TextFieldValue("", TextRange(0))
        selectedTags = emptyList()
        selectedCollaborators = emptyList()
        inputViewModel.uploadResources.clear()
        inputViewModel.uploadTasks.clear()
        navController.popBackStackIfLifecycleIsResumed(lifecycleOwner)
    }

    fun uploadResources(uris: List<Uri>) = coroutineScope.launch {
        var uploadedAny = false
        uris.forEach { uri ->
            inputViewModel.upload(uri, memoIdentifier = null).suspendOnSuccess {
                uploadedAny = true
            }.suspendOnErrorMessage { message ->
                snackbarState.showSnackbar(message)
            }
        }
        if (uploadedAny) {
            delay(300)
            focusRequester.requestFocus()
        }
    }

    fun uploadResource(uri: Uri) {
        uploadResources(listOf(uri))
    }

    val pickMedia = rememberLauncherForActivityResult(PickMultipleVisualMedia()) { uris ->
        if (uris.isNotEmpty()) {
            uploadResources(uris)
        }
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
                    pickMedia.launch(PickVisualMediaRequest(PickVisualMedia.ImageAndVideo))
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
            quotePreview = quoteDescriptorForSubmit?.let {
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
            },
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
                inputViewModel.uploadResources.clear()
                inputViewModel.uploadTasks.clear()
                navController.popBackStackIfLifecycleIsResumed(lifecycleOwner)
            },
            onDismiss = {
                showExitConfirmation = false
            }
        )
    }

    LaunchedEffect(groupId, memoId, quoteSourceMemoIdentifier) {
        inputViewModel.uploadResources.clear()
        inputViewModel.uploadTasks.clear()
        currentMemo = null
        userStateViewModel.refreshFriends()
        groupViewModel.loadGroupTags(groupId)
        if (isEditMode) {
            groupViewModel.loadGroupMemos(groupId, forceSync = false)
            val targetMemo = groupViewModel.findGroupMemo(groupId, memoId.orEmpty())
            if (targetMemo != null) {
                val memoEntity = targetMemo.toEditableGroupMemoEntity(groupId = groupId)
                currentMemo = memoEntity
                val collaborators = extractCollaboratorIds(targetMemo.tags)
                val tags = normalizeTagList(
                    stripQuoteTags(stripCollaboratorTags(targetMemo.tags))
                )
                initialContent = targetMemo.content
                initialTags = tags
                initialCollaborators = collaborators
                inputViewModel.uploadResources.addAll(
                    targetMemo.resources.map { resource ->
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
                    }
                )
                initialResourceIdentifiers = inputViewModel.uploadResources
                    .map { resource -> resource.remoteId ?: resource.identifier }
                    .distinct()
                text = TextFieldValue(targetMemo.content, TextRange(targetMemo.content.length))
                selectedTags = tags
                selectedCollaborators = collaborators
            } else {
                currentMemo = null
                initialContent = ""
                initialTags = emptyList()
                initialCollaborators = emptyList()
                initialResourceIdentifiers = emptyList()
                text = TextFieldValue("", TextRange(0))
                selectedTags = emptyList()
                selectedCollaborators = emptyList()
            }
        } else {
            currentMemo = null
            initialContent = ""
            initialTags = emptyList()
            initialCollaborators = emptyList()
            initialResourceIdentifiers = emptyList()
            text = TextFieldValue("", TextRange(0))
            selectedTags = emptyList()
            selectedCollaborators = emptyList()
        }
        delay(300)
        focusRequester.requestFocus()
    }

    DisposableEffect(Unit) {
        onDispose {
            inputViewModel.uploadTasks.clear()
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
