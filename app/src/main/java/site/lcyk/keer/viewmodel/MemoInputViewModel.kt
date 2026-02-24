package site.lcyk.keer.viewmodel

import android.app.Application
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
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
import site.lcyk.keer.data.service.MemoService
import site.lcyk.keer.ext.getErrorMessage
import site.lcyk.keer.ext.settingsDataStore
import site.lcyk.keer.widget.WidgetUpdater
import okhttp3.MediaType.Companion.toMediaTypeOrNull
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

@HiltViewModel
class MemoInputViewModel @Inject constructor(
    @ApplicationContext application: Context,
    private val memoService: MemoService
) : AndroidViewModel(application as Application) {
    private val context = application
    val draft = context.settingsDataStore.data.map { settings ->
        settings.usersList.firstOrNull { it.accountKey == settings.currentUser }?.settings?.draft
    }
    var uploadResources = mutableStateListOf<ResourceEntity>()
    var uploadTasks = mutableStateListOf<UploadTaskState>()

    suspend fun createMemo(content: String, visibility: MemoVisibility, tags: List<String>): ApiResponse<MemoEntity> = withContext(viewModelScope.coroutineContext) {
        val response = memoService.getRepository().createMemo(content, visibility, uploadResources, tags)
        response.suspendOnSuccess {
            WidgetUpdater.updateWidgets(getApplication())
        }
        response
    }

    suspend fun editMemo(identifier: String, content: String, visibility: MemoVisibility, tags: List<String>): ApiResponse<MemoEntity> = withContext(viewModelScope.coroutineContext) {
        val response = memoService.getRepository().updateMemo(identifier, content, uploadResources, visibility, tags)
        response.suspendOnSuccess {
            WidgetUpdater.updateWidgets(getApplication())
        }
        response
    }

    fun updateDraft(content: String) {
        viewModelScope.launch(Dispatchers.IO) {
            context.settingsDataStore.updateData { settings ->
                val index = settings.usersList.indexOfFirst { it.accountKey == settings.currentUser }
                if (index == -1) {
                    return@updateData settings
                }
                val users = settings.usersList.toMutableList()
                val user = users[index]
                users[index] = user.copy(settings = user.settings.copy(draft = content))
                settings.copy(usersList = users)
            }
        }
    }

    suspend fun upload(uri: Uri, memoIdentifier: String?): ApiResponse<ResourceEntity> = withContext(Dispatchers.IO) {
        val mimeType = context.contentResolver.getType(uri)
        val extension = mimeType?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
        val filename = queryDisplayName(uri)
            ?: ("attachment_${UUID.randomUUID()}" + if (extension.isNullOrBlank()) "" else ".$extension")
        val size = queryFileSize(uri)
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
                type = mimeType?.toMediaTypeOrNull(),
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
        } catch (_: Exception) {
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
        } catch (_: Exception) {
            -1L
        }
    }

    fun deleteResource(resourceIdentifier: String) = viewModelScope.launch {
        memoService.getRepository().deleteResource(resourceIdentifier).suspendOnSuccess {
            uploadResources.removeIf { it.identifier == resourceIdentifier }
        }
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
}
