package site.lcyk.keer.data.repository

import android.net.Uri
import com.skydoves.sandwich.ApiResponse
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import site.lcyk.keer.data.local.entity.MemoEntity
import site.lcyk.keer.data.local.entity.ResourceEntity
import site.lcyk.keer.data.model.MemoVisibility
import site.lcyk.keer.data.model.SyncStatus
import site.lcyk.keer.data.model.User
import okhttp3.MediaType

abstract class AbstractMemoRepository {
    private val _syncStatus = MutableStateFlow(SyncStatus())
    open val syncStatus: StateFlow<SyncStatus> = _syncStatus.asStateFlow()

    abstract suspend fun listMemos(): ApiResponse<List<MemoEntity>>
    abstract suspend fun listArchivedMemos(): ApiResponse<List<MemoEntity>>
    abstract suspend fun createMemo(content: String, visibility: MemoVisibility, resources: List<ResourceEntity>, tags: List<String>? = null): ApiResponse<MemoEntity>
    abstract suspend fun updateMemo(identifier: String, content: String? = null, resources: List<ResourceEntity>? = null, visibility: MemoVisibility? = null, tags: List<String>? = null, pinned: Boolean? = null): ApiResponse<MemoEntity>
    abstract suspend fun deleteMemo(identifier: String): ApiResponse<Unit>
    abstract suspend fun archiveMemo(identifier: String): ApiResponse<Unit>
    abstract suspend fun restoreMemo(identifier: String): ApiResponse<Unit>

    abstract suspend fun listTags(): ApiResponse<List<String>>

    abstract suspend fun listResources(): ApiResponse<List<ResourceEntity>>
    abstract suspend fun createResource(
        filename: String,
        type: MediaType?,
        sourceUri: Uri,
        memoIdentifier: String? = null,
        onProgress: (uploadedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> }
    ): ApiResponse<ResourceEntity>
    abstract suspend fun deleteResource(identifier: String): ApiResponse<Unit>

    abstract suspend fun getCurrentUser(): ApiResponse<User>

    open fun observeMemos(): Flow<List<MemoEntity>> = emptyFlow()

    open suspend fun cacheResourceFile(identifier: String, downloadedUri: Uri): ApiResponse<Unit> {
        return ApiResponse.Success(Unit)
    }

    open suspend fun cacheResourceThumbnail(identifier: String, downloadedUri: Uri): ApiResponse<Unit> {
        return ApiResponse.Success(Unit)
    }

    open suspend fun sync(): ApiResponse<Unit> {
        return ApiResponse.Success(Unit)
    }

    open fun close() = Unit
}
