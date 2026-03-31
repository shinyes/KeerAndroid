package site.lcyk.keer.viewmodel

import android.app.Application
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.skydoves.sandwich.ApiResponse
import com.skydoves.sandwich.suspendOnSuccess
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import site.lcyk.keer.R
import site.lcyk.keer.data.local.entity.MemoEntity
import site.lcyk.keer.data.local.entity.ResourceEntity
import site.lcyk.keer.data.model.MemoEditorUploadTaskPersistenceState
import site.lcyk.keer.data.model.MemoEditorWorkflowPersistenceState
import site.lcyk.keer.data.model.MemoVisibility
import site.lcyk.keer.data.service.AccountLocalSettingsStore
import site.lcyk.keer.data.service.MemoService
import site.lcyk.keer.ext.getErrorMessage
import site.lcyk.keer.util.normalizeCollaboratorId
import site.lcyk.keer.util.normalizeTagList
import site.lcyk.keer.widget.WidgetUpdater
import timber.log.Timber
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import kotlinx.coroutines.CancellationException
import javax.inject.Inject

enum class UploadTaskStatus {
    PREPARING,
    UPLOADING,
    FAILED
}

data class UploadTaskState(
    val id: String,
    val sequence: Long,
    val filename: String,
    val uploadedBytes: Long,
    val totalBytes: Long,
    val status: UploadTaskStatus,
    val errorMessage: String? = null,
    val sourceUri: String? = null,
    val targetMemoIdentifier: String? = null,
)

@HiltViewModel
class MemoInputViewModel @Inject constructor(
    @ApplicationContext application: Context,
    private val memoService: MemoService,
    private val accountLocalSettingsStore: AccountLocalSettingsStore,
) : AndroidViewModel(application as Application) {
    private val context = application
    private val uploadTaskSequence = AtomicLong(0L)
    private val uploadTaskJobs = ConcurrentHashMap<String, Job>()
    private var persistEditorWorkflowJob: Job? = null
    private var restoringPersistedWorkflow = false
    val draft = accountLocalSettingsStore.observeCurrentUserSettings().map { settings -> settings?.draft }
    var persistedEditorSessionKey by mutableStateOf("")
        private set
    var persistedEditorContent by mutableStateOf("")
        private set
    var persistedEditorSelectedTags by mutableStateOf(emptyList<String>())
        private set
    var persistedEditorSelectedCollaborators by mutableStateOf(emptyList<String>())
        private set
    var uploadResources = mutableStateListOf<ResourceEntity>()
    var uploadTasks = mutableStateListOf<UploadTaskState>()
    var recentlyUploadedResourceIdentifiers = mutableStateListOf<String>()

    init {
        viewModelScope.launch {
            restorePersistedEditorWorkflowState()
        }
    }

    suspend fun createMemo(
        content: String,
        visibility: MemoVisibility,
        tags: List<String>,
        latitude: Double? = null,
        longitude: Double? = null
    ): ApiResponse<MemoEntity> = withContext(Dispatchers.IO) {
        val resolvedTags = normalizeTagList(tags)
        val response = memoService.getRepository().createMemo(
            content = content,
            visibility = visibility,
            resources = uploadResources,
            tags = resolvedTags,
            latitude = latitude,
            longitude = longitude
        )
        response.suspendOnSuccess {
            WidgetUpdater.updateWidgets(getApplication())
        }
        response
    }

    suspend fun editMemo(
        identifier: String,
        content: String,
        visibility: MemoVisibility,
        tags: List<String>
    ): ApiResponse<MemoEntity> = withContext(Dispatchers.IO) {
        val resolvedTags = normalizeTagList(tags)
        val response = memoService.getRepository().updateMemo(
            identifier,
            content,
            uploadResources,
            visibility,
            resolvedTags
        )
        response.suspendOnSuccess {
            WidgetUpdater.updateWidgets(getApplication())
        }
        response
    }

    suspend fun submitEditorRequest(
        memoIdentifier: String?,
        visibility: MemoVisibility,
        request: MemoEditorSubmitRequest,
    ): String? = withContext(Dispatchers.IO) {
        val response = if (memoIdentifier.isNullOrBlank()) {
            createMemo(
                content = request.content,
                visibility = visibility,
                tags = request.tags,
                latitude = request.latitude,
                longitude = request.longitude,
            )
        } else {
            editMemo(
                identifier = memoIdentifier,
                content = request.content,
                visibility = visibility,
                tags = request.tags,
            )
        }
        when (response) {
            is ApiResponse.Success -> null
            else -> response.getErrorMessage() ?: context.getString(R.string.sync_failed)
        }
    }

    fun updateDraft(content: String) {
        viewModelScope.launch(Dispatchers.IO) {
            accountLocalSettingsStore.updateCurrentUserSettings { settings ->
                settings.copy(draft = content)
            }
        }
    }

    suspend fun upload(uri: Uri, memoIdentifier: String?): ApiResponse<ResourceEntity> = withContext(Dispatchers.IO) {
        val taskId = UUID.randomUUID().toString()
        val taskSequence = uploadTaskSequence.incrementAndGet()
        uploadInternal(
            uri = uri,
            memoIdentifier = memoIdentifier,
            taskId = taskId,
            taskSequence = taskSequence,
        )
    }

    private suspend fun uploadInternal(
        uri: Uri,
        memoIdentifier: String?,
        taskId: String,
        taskSequence: Long,
    ): ApiResponse<ResourceEntity> = withContext(Dispatchers.IO) {
        currentCoroutineContext()[Job]?.let { job ->
            uploadTaskJobs[taskId] = job
        }
        val mimeType = context.contentResolver.getType(uri)
        val extension = mimeType?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
        val filename = queryDisplayName(uri)
            ?: ("attachment_${UUID.randomUUID()}" + if (extension.isNullOrBlank()) "" else ".$extension")
        val size = queryFileSize(uri)
        addOrUpdateUploadTask(
            UploadTaskState(
                id = taskId,
                sequence = taskSequence,
                filename = filename,
                uploadedBytes = 0L,
                totalBytes = size,
                status = UploadTaskStatus.PREPARING,
                sourceUri = uri.toString(),
                targetMemoIdentifier = memoIdentifier,
            )
        )

        try {
            val repository = memoService.getRepository()
            val response = repository.createResource(
                filename = filename,
                type = mimeType?.toMediaTypeOrNull(),
                sourceUri = uri,
                memoIdentifier = memoIdentifier
            ) { uploadedBytes, totalBytes ->
                addOrUpdateUploadTask(
                    UploadTaskState(
                        id = taskId,
                        sequence = taskSequence,
                        filename = filename,
                        uploadedBytes = uploadedBytes,
                        totalBytes = if (totalBytes > 0L) totalBytes else size,
                        status = UploadTaskStatus.UPLOADING,
                        sourceUri = uri.toString(),
                        targetMemoIdentifier = memoIdentifier,
                    )
                )
            }

            if (response is ApiResponse.Success) {
                uploadResources.add(response.data)
                markRecentlyUploadedResource(response.data.identifier)
                removeUploadTask(taskId)
            } else {
                addOrUpdateUploadTask(
                    UploadTaskState(
                        id = taskId,
                        sequence = taskSequence,
                        filename = filename,
                        uploadedBytes = 0L,
                        totalBytes = size,
                        status = UploadTaskStatus.FAILED,
                        errorMessage = response.getErrorMessage(),
                        sourceUri = uri.toString(),
                        targetMemoIdentifier = memoIdentifier,
                    )
                )
            }
            response
        } catch (e: CancellationException) {
            removeUploadTask(taskId)
            throw e
        } catch (e: Exception) {
            addOrUpdateUploadTask(
                UploadTaskState(
                    id = taskId,
                    sequence = taskSequence,
                    filename = filename,
                    uploadedBytes = 0L,
                    totalBytes = size,
                    status = UploadTaskStatus.FAILED,
                    errorMessage = e.localizedMessage ?: e.message,
                    sourceUri = uri.toString(),
                    targetMemoIdentifier = memoIdentifier,
                )
            )
            ApiResponse.Failure.Exception(e)
        } finally {
            uploadTaskJobs.remove(taskId)
        }
    }

    fun hasActiveUpload(): Boolean {
        return uploadTasks.any { it.status == UploadTaskStatus.PREPARING || it.status == UploadTaskStatus.UPLOADING }
    }

    fun dismissUploadTask(taskId: String) {
        removeUploadTask(taskId)
    }

    fun updatePersistedEditorContent(
        sessionKey: String,
        content: String,
        selectedTags: List<String>,
        selectedCollaborators: List<String>,
    ) {
        persistedEditorSessionKey = sessionKey.trim()
        persistedEditorContent = content
        persistedEditorSelectedTags = normalizeTagList(selectedTags)
        persistedEditorSelectedCollaborators = selectedCollaborators
            .map(::normalizeCollaboratorId)
            .filter { it.isNotEmpty() }
            .distinct()
        schedulePersistEditorWorkflowState()
    }

    fun clearPersistedEditorContent(sessionKey: String? = null) {
        val normalizedSessionKey = sessionKey?.trim().orEmpty()
        if (normalizedSessionKey.isNotEmpty() && persistedEditorSessionKey != normalizedSessionKey) {
            return
        }
        persistedEditorSessionKey = ""
        persistedEditorContent = ""
        persistedEditorSelectedTags = emptyList()
        persistedEditorSelectedCollaborators = emptyList()
        schedulePersistEditorWorkflowState()
    }

    fun clearUploadResources() {
        viewModelScope.launch(Dispatchers.Main.immediate) {
            uploadResources.clear()
            recentlyUploadedResourceIdentifiers.clear()
            schedulePersistEditorWorkflowState()
        }
    }

    fun clearUploadTasks(clearFailedOnly: Boolean = false) {
        viewModelScope.launch(Dispatchers.Main.immediate) {
            if (clearFailedOnly) {
                uploadTasks.removeAll { it.status == UploadTaskStatus.FAILED }
            } else {
                uploadTasks.clear()
            }
            schedulePersistEditorWorkflowState()
        }
    }

    fun clearFailedUploadTasks() {
        clearUploadTasks(clearFailedOnly = true)
    }

    fun cancelUploadTask(taskId: String) {
        uploadTaskJobs.remove(taskId)?.cancel()
        removeUploadTask(taskId)
    }

    fun cancelActiveUploadTasks() {
        uploadTasks
            .filter { task ->
                task.status == UploadTaskStatus.PREPARING || task.status == UploadTaskStatus.UPLOADING
            }
            .map { it.id }
            .forEach(::cancelUploadTask)
    }

    fun retryUploadTask(taskId: String) {
        val failedTask = uploadTasks.firstOrNull { it.id == taskId } ?: return
        val sourceUri = failedTask.sourceUri?.takeIf { it.isNotBlank() } ?: return
        removeUploadTask(taskId)
        viewModelScope.launch {
            uploadInternal(
                uri = Uri.parse(sourceUri),
                memoIdentifier = failedTask.targetMemoIdentifier,
                taskId = UUID.randomUUID().toString(),
                taskSequence = uploadTaskSequence.incrementAndGet(),
            )
        }
    }

    fun retryFailedUploadTasks() {
        val failedTaskIds = uploadTasks
            .filter { it.status == UploadTaskStatus.FAILED && !it.sourceUri.isNullOrBlank() }
            .sortedBy { it.sequence }
            .map { it.id }
        failedTaskIds.forEach(::retryUploadTask)
    }

    private fun queryDisplayName(uri: Uri): String? {
        return try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) {
                    return@use null
                }
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index == -1) {
                    null
                } else {
                    cursor.getString(index)
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to query file name for URI: %s", uri)
            null
        }
    }

    private fun queryFileSize(uri: Uri): Long {
        return try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) {
                    return@use -1L
                }
                val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (index == -1) {
                    -1L
                } else {
                    cursor.getLong(index)
                }
            } ?: -1L
        } catch (e: Exception) {
            Timber.w(e, "Failed to query file size for URI: %s", uri)
            -1L
        }
    }

    fun deleteResource(resourceIdentifier: String) = viewModelScope.launch {
        memoService.getRepository().deleteResource(resourceIdentifier).suspendOnSuccess {
            uploadResources.removeIf { it.identifier == resourceIdentifier }
            recentlyUploadedResourceIdentifiers.remove(resourceIdentifier)
            schedulePersistEditorWorkflowState()
        }
    }

    fun removeResourceFromDraft(resource: ResourceEntity) = viewModelScope.launch {
        if (resource.remoteId.isNullOrBlank()) {
            memoService.getRepository().deleteResource(resource.identifier).suspendOnSuccess {
                uploadResources.removeIf { it.identifier == resource.identifier }
                recentlyUploadedResourceIdentifiers.remove(resource.identifier)
                schedulePersistEditorWorkflowState()
            }
            return@launch
        }
        uploadResources.removeIf { it.identifier == resource.identifier }
        recentlyUploadedResourceIdentifiers.remove(resource.identifier)
        schedulePersistEditorWorkflowState()
    }

    private fun addOrUpdateUploadTask(task: UploadTaskState) {
        viewModelScope.launch(Dispatchers.Main.immediate) {
            val index = uploadTasks.indexOfFirst { it.id == task.id }
            if (index >= 0) {
                uploadTasks[index] = task
            } else {
                uploadTasks.add(task)
            }
            schedulePersistEditorWorkflowState()
        }
    }

    private fun removeUploadTask(taskId: String) {
        viewModelScope.launch(Dispatchers.Main.immediate) {
            uploadTasks.removeAll { it.id == taskId }
            schedulePersistEditorWorkflowState()
        }
    }

    private fun markRecentlyUploadedResource(resourceIdentifier: String) {
        viewModelScope.launch(Dispatchers.Main.immediate) {
            recentlyUploadedResourceIdentifiers.remove(resourceIdentifier)
            recentlyUploadedResourceIdentifiers.add(resourceIdentifier)
        }
        viewModelScope.launch {
            delay(1_800L)
            withContext(Dispatchers.Main.immediate) {
                recentlyUploadedResourceIdentifiers.remove(resourceIdentifier)
            }
        }
    }

    private suspend fun restorePersistedEditorWorkflowState() {
        restoringPersistedWorkflow = true
        try {
            val persistedState = accountLocalSettingsStore.currentMemoEditorWorkflowState()
            val repository = memoService.getRepository()
            val restoredResources = buildList {
                persistedState.uploadResourceIdentifiers
                    .asSequence()
                    .map(String::trim)
                    .filter { it.isNotEmpty() }
                    .distinct()
                    .forEach { identifier ->
                        repository.getResourceById(identifier)?.let(::add)
                    }
            }
            val restoredTasks = persistedState.uploadTasks
                .map(::restorePersistedUploadTask)
                .sortedBy { task -> task.sequence }

            withContext(Dispatchers.Main.immediate) {
                persistedEditorSessionKey = persistedState.editorSessionKey
                persistedEditorContent = persistedState.editorContent
                persistedEditorSelectedTags = normalizeTagList(persistedState.editorSelectedTags)
                persistedEditorSelectedCollaborators = persistedState.editorSelectedCollaborators
                    .map(::normalizeCollaboratorId)
                    .filter { it.isNotEmpty() }
                    .distinct()
                uploadResources.clear()
                uploadResources.addAll(restoredResources)
                uploadTasks.clear()
                uploadTasks.addAll(restoredTasks)
                recentlyUploadedResourceIdentifiers.clear()
            }

            val restoredSequence = maxOf(
                persistedState.lastUploadTaskSequence,
                restoredTasks.maxOfOrNull(UploadTaskState::sequence) ?: 0L,
            )
            uploadTaskSequence.set(restoredSequence)
        } finally {
            restoringPersistedWorkflow = false
        }
        persistEditorWorkflowStateNow()
    }

    private fun restorePersistedUploadTask(
        persistedTask: MemoEditorUploadTaskPersistenceState,
    ): UploadTaskState {
        val originalStatus = persistedTask.status
            .trim()
            .takeIf { it.isNotEmpty() }
            ?.let { raw ->
                runCatching { UploadTaskStatus.valueOf(raw) }.getOrNull()
            }
            ?: UploadTaskStatus.FAILED
        val restoredStatus = when (originalStatus) {
            UploadTaskStatus.PREPARING,
            UploadTaskStatus.UPLOADING -> UploadTaskStatus.FAILED
            UploadTaskStatus.FAILED -> UploadTaskStatus.FAILED
        }
        val restoredErrorMessage = when {
            originalStatus == UploadTaskStatus.PREPARING || originalStatus == UploadTaskStatus.UPLOADING ->
                context.getString(site.lcyk.keer.R.string.upload_interrupted_retry)
            else -> persistedTask.errorMessage
        }
        return UploadTaskState(
            id = persistedTask.id.trim().ifEmpty { UUID.randomUUID().toString() },
            sequence = persistedTask.sequence,
            filename = persistedTask.filename.trim().ifEmpty { "attachment" },
            uploadedBytes = persistedTask.uploadedBytes,
            totalBytes = persistedTask.totalBytes,
            status = restoredStatus,
            errorMessage = restoredErrorMessage,
            sourceUri = persistedTask.sourceUri.trim().ifEmpty { null },
            targetMemoIdentifier = persistedTask.targetMemoIdentifier.trim().ifEmpty { null },
        )
    }

    private fun schedulePersistEditorWorkflowState() {
        if (restoringPersistedWorkflow) {
            return
        }
        persistEditorWorkflowJob?.cancel()
        persistEditorWorkflowJob = viewModelScope.launch(Dispatchers.IO) {
            delay(200L)
            persistEditorWorkflowStateNow()
        }
    }

    private suspend fun persistEditorWorkflowStateNow() {
        if (restoringPersistedWorkflow) {
            return
        }
        val snapshot = MemoEditorWorkflowPersistenceState(
            editorSessionKey = persistedEditorSessionKey,
            editorContent = persistedEditorContent,
            editorSelectedTags = persistedEditorSelectedTags,
            editorSelectedCollaborators = persistedEditorSelectedCollaborators,
            uploadResourceIdentifiers = uploadResources
                .map { resource -> resource.identifier.trim() }
                .filter { it.isNotEmpty() }
                .distinct(),
            uploadTasks = uploadTasks.map { task ->
                MemoEditorUploadTaskPersistenceState(
                    id = task.id,
                    sequence = task.sequence,
                    filename = task.filename,
                    uploadedBytes = task.uploadedBytes,
                    totalBytes = task.totalBytes,
                    status = task.status.name,
                    errorMessage = task.errorMessage,
                    sourceUri = task.sourceUri.orEmpty(),
                    targetMemoIdentifier = task.targetMemoIdentifier.orEmpty(),
                )
            },
            lastUploadTaskSequence = maxOf(
                uploadTaskSequence.get(),
                uploadTasks.maxOfOrNull(UploadTaskState::sequence) ?: 0L,
            ),
        )
        accountLocalSettingsStore.updateCurrentMemoEditorWorkflowState {
            snapshot
        }
    }
}
