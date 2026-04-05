package site.lcyk.keer.viewmodel

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.skydoves.sandwich.ApiResponse
import com.skydoves.sandwich.suspendOnSuccess
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import site.lcyk.keer.data.local.entity.MemoEntity
import site.lcyk.keer.data.local.entity.ResourceEntity
import site.lcyk.keer.data.model.MemoVisibility
import site.lcyk.keer.data.model.QuickMemoDraftState
import site.lcyk.keer.data.service.AccountLocalSettingsStore
import site.lcyk.keer.data.service.MemoService
import site.lcyk.keer.ext.getErrorMessage
import site.lcyk.keer.util.UploadMediaMetadataResolver
import site.lcyk.keer.util.normalizeQuickMemoDraftState
import site.lcyk.keer.util.normalizeTagList
import site.lcyk.keer.util.resolveQuickMemoDraftResources
import site.lcyk.keer.widget.WidgetUpdater
import timber.log.Timber
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import java.io.File
import java.util.UUID
import javax.inject.Inject

enum class UploadTaskStatus {
    PREPARING,
    UPLOADING,
    FAILED
}

data class UploadTaskState(
    val id: String,
    val filename: String,
    val uploadedBytes: Long,
    val totalBytes: Long,
    val status: UploadTaskStatus,
    val errorMessage: String? = null
)

data class LoadedQuickMemoDraft(
    val draft: QuickMemoDraftState = QuickMemoDraftState(),
    val resources: List<ResourceEntity> = emptyList(),
)

@HiltViewModel
class MemoInputViewModel @Inject constructor(
    @ApplicationContext application: Context,
    private val memoService: MemoService,
    private val accountLocalSettingsStore: AccountLocalSettingsStore,
) : AndroidViewModel(application as Application) {
    private val context = application
    val draft = accountLocalSettingsStore.observeCurrentUserSettings().map { settings -> settings?.draft }
    val quickDraft = accountLocalSettingsStore.observeCurrentQuickMemoDraft()
    var uploadResources = mutableStateListOf<ResourceEntity>()
    var uploadTasks = mutableStateListOf<UploadTaskState>()

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

    fun updateDraft(content: String) {
        viewModelScope.launch(Dispatchers.IO) {
            accountLocalSettingsStore.updateCurrentUserSettings { settings ->
                settings.copy(draft = content)
            }
        }
    }

    fun updateQuickDraft(draft: QuickMemoDraftState) {
        viewModelScope.launch(Dispatchers.IO) {
            persistQuickDraft(draft)
        }
    }

    fun clearQuickDraft() {
        viewModelScope.launch(Dispatchers.IO) {
            accountLocalSettingsStore.clearCurrentQuickMemoDraft()
        }
    }

    suspend fun persistQuickDraft(draft: QuickMemoDraftState) = withContext(Dispatchers.IO) {
        val normalizedDraft = normalizeQuickMemoDraftState(draft)
        if (normalizedDraft.isEmpty()) {
            accountLocalSettingsStore.clearCurrentQuickMemoDraft()
        } else {
            accountLocalSettingsStore.updateCurrentQuickMemoDraft { normalizedDraft }
        }
    }

    suspend fun clearQuickDraftNow() = withContext(Dispatchers.IO) {
        accountLocalSettingsStore.clearCurrentQuickMemoDraft()
    }

    suspend fun loadQuickMemoDraft(): LoadedQuickMemoDraft = withContext(Dispatchers.IO) {
        val storedDraft = normalizeQuickMemoDraftState(accountLocalSettingsStore.currentQuickMemoDraft())
        if (storedDraft.resourceIdentifiers.isEmpty()) {
            if (storedDraft.isEmpty()) {
                return@withContext LoadedQuickMemoDraft()
            }
            return@withContext LoadedQuickMemoDraft(draft = storedDraft)
        }

        val repository = memoService.getRepository()
        val availableResources = repository.getResourcesByIdentifiers(storedDraft.resourceIdentifiers)
            .asSequence()
            .filter { resource -> resource.memoId.isNullOrBlank() }
            .filter(::hasUsableDraftSource)
            .distinctBy { resource -> resource.identifier }
            .toList()
        val restoredResources = resolveQuickMemoDraftResources(
            resourceIdentifiers = storedDraft.resourceIdentifiers,
            resources = availableResources,
        )
        val sanitizedDraft = storedDraft.copy(resourceIdentifiers = restoredResources.resourceIdentifiers)
        if (sanitizedDraft != storedDraft) {
            persistQuickDraft(sanitizedDraft)
        }
        LoadedQuickMemoDraft(
            draft = sanitizedDraft,
            resources = restoredResources.resources,
        )
    }

    suspend fun deleteDraftResources(resources: List<ResourceEntity>) = withContext(Dispatchers.IO) {
        val repository = memoService.getRepository()
        resources
            .asSequence()
            .filter { resource -> resource.memoId.isNullOrBlank() }
            .map { resource -> resource.identifier.trim() }
            .filter { identifier -> identifier.isNotEmpty() }
            .distinct()
            .forEach { identifier ->
                when (val response = repository.deleteResource(identifier)) {
                    is ApiResponse.Success -> Unit
                    is ApiResponse.Failure.Error -> {
                        Timber.w("Failed to delete draft resource %s", identifier)
                    }

                    is ApiResponse.Failure.Exception -> {
                        Timber.w(response.throwable, "Failed to delete draft resource %s", identifier)
                    }
                }
            }
    }

    suspend fun discardQuickDraft(resources: List<ResourceEntity>) = withContext(Dispatchers.IO) {
        deleteDraftResources(resources)
        accountLocalSettingsStore.clearCurrentQuickMemoDraft()
    }

    suspend fun upload(uri: Uri, memoIdentifier: String?): ApiResponse<ResourceEntity> = withContext(Dispatchers.IO) {
        val metadata = UploadMediaMetadataResolver.resolve(context.contentResolver, uri)
        val filename = metadata.filename
        val size = metadata.sizeBytes
        val taskId = UUID.randomUUID().toString()
        addOrUpdateUploadTask(
            UploadTaskState(
                id = taskId,
                filename = filename,
                uploadedBytes = 0L,
                totalBytes = size,
                status = UploadTaskStatus.PREPARING
            )
        )

        try {
            val repository = memoService.getRepository()
            val response = repository.createResource(
                filename = filename,
                type = metadata.mimeType?.toMediaTypeOrNull(),
                sourceUri = uri,
                memoIdentifier = memoIdentifier
            ) { uploadedBytes, totalBytes ->
                addOrUpdateUploadTask(
                    UploadTaskState(
                        id = taskId,
                        filename = filename,
                        uploadedBytes = uploadedBytes,
                        totalBytes = if (totalBytes > 0L) totalBytes else size,
                        status = UploadTaskStatus.UPLOADING
                    )
                )
            }

            if (response is ApiResponse.Success) {
                uploadResources.add(response.data)
                removeUploadTask(taskId)
            } else {
                addOrUpdateUploadTask(
                    UploadTaskState(
                        id = taskId,
                        filename = filename,
                        uploadedBytes = 0L,
                        totalBytes = size,
                        status = UploadTaskStatus.FAILED,
                        errorMessage = response.getErrorMessage()
                    )
                )
            }
            response
        } catch (e: Exception) {
            addOrUpdateUploadTask(
                UploadTaskState(
                    id = taskId,
                    filename = filename,
                    uploadedBytes = 0L,
                    totalBytes = size,
                    status = UploadTaskStatus.FAILED,
                    errorMessage = e.localizedMessage ?: e.message
                )
            )
            ApiResponse.Failure.Exception(e)
        }
    }

    fun hasActiveUpload(): Boolean {
        return uploadTasks.any { it.status == UploadTaskStatus.PREPARING || it.status == UploadTaskStatus.UPLOADING }
    }

    fun dismissUploadTask(taskId: String) {
        removeUploadTask(taskId)
    }

    fun deleteResource(resourceIdentifier: String) = viewModelScope.launch {
        memoService.getRepository().deleteResource(resourceIdentifier).suspendOnSuccess {
            uploadResources.removeIf { it.identifier == resourceIdentifier }
        }
    }

    fun removeResourceFromDraft(resource: ResourceEntity) = viewModelScope.launch {
        if (resource.remoteId.isNullOrBlank()) {
            memoService.getRepository().deleteResource(resource.identifier).suspendOnSuccess {
                uploadResources.removeIf { it.identifier == resource.identifier }
            }
            return@launch
        }
        uploadResources.removeIf { it.identifier == resource.identifier }
    }

    private fun addOrUpdateUploadTask(task: UploadTaskState) {
        viewModelScope.launch(Dispatchers.Main.immediate) {
            val index = uploadTasks.indexOfFirst { it.id == task.id }
            if (index >= 0) {
                uploadTasks[index] = task
            } else {
                uploadTasks.add(task)
            }
        }
    }

    private fun removeUploadTask(taskId: String) {
        viewModelScope.launch(Dispatchers.Main.immediate) {
            uploadTasks.removeAll { it.id == taskId }
        }
    }

    private fun hasUsableDraftSource(resource: ResourceEntity): Boolean {
        val candidateUris = listOfNotNull(resource.localUri, resource.uri)
            .mapNotNull { rawUri ->
                runCatching { Uri.parse(rawUri) }
                    .onFailure { throwable ->
                        Timber.w(throwable, "Failed to parse draft resource uri: %s", rawUri)
                    }
                    .getOrNull()
            }
        if (candidateUris.isEmpty()) {
            return false
        }
        return candidateUris.any { uri ->
            when (uri.scheme?.lowercase()) {
                "file" -> uri.path?.let(::File)?.exists() == true
                "content" -> {
                    runCatching {
                        context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { true } ?: false
                    }.getOrDefault(false)
                }

                "http", "https" -> true
                else -> uri.toString().isNotBlank()
            }
        }
    }
}
