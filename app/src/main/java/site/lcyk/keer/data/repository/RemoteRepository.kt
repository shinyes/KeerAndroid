package site.lcyk.keer.data.repository

import com.skydoves.sandwich.ApiResponse
import site.lcyk.keer.data.model.Memo
import site.lcyk.keer.data.model.MemoGroup
import site.lcyk.keer.data.model.MemoVisibility
import site.lcyk.keer.data.model.Resource
import site.lcyk.keer.data.model.User
import okhttp3.MediaType
import java.io.File
import java.time.Instant

data class ResourceUploadThumbnail(
    val filename: String,
    val type: String,
    val content: String
)

data class MemoChanges(
    val memos: List<Memo>,
    val deletedMemoRemoteIds: List<String>,
    val syncAnchor: Instant
)

abstract class RemoteRepository {
    abstract suspend fun listMemos(): ApiResponse<List<Memo>>
    abstract suspend fun listArchivedMemos(): ApiResponse<List<Memo>>
    abstract suspend fun listMemoChanges(since: Instant): ApiResponse<MemoChanges>
    abstract suspend fun listWorkspaceMemos(
        pageSize: Int,
        pageToken: String?,
        filter: String? = null
    ): ApiResponse<Pair<List<Memo>, String?>>

    abstract suspend fun createMemo(
        content: String,
        visibility: MemoVisibility,
        resourceRemoteIds: List<String>,
        tags: List<String>? = null,
        createdAt: Instant? = null,
        latitude: Double? = null,
        longitude: Double? = null
    ): ApiResponse<Memo>

    abstract suspend fun updateMemo(
        remoteId: String,
        content: String? = null,
        resourceRemoteIds: List<String>? = null,
        visibility: MemoVisibility? = null,
        tags: List<String>? = null,
        pinned: Boolean? = null,
        archived: Boolean? = null
    ): ApiResponse<Memo>

    abstract suspend fun deleteMemo(remoteId: String): ApiResponse<Unit>

    abstract suspend fun listGroups(): ApiResponse<List<MemoGroup>>
    abstract suspend fun createGroup(name: String, description: String): ApiResponse<MemoGroup>
    abstract suspend fun joinGroup(groupId: String): ApiResponse<MemoGroup>
    abstract suspend fun updateGroup(
        groupId: String,
        name: String? = null,
        description: String? = null
    ): ApiResponse<MemoGroup>

    abstract suspend fun deleteOrLeaveGroup(groupId: String): ApiResponse<Unit>
    abstract suspend fun listGroupMessages(
        groupId: String,
        pageSize: Int,
        pageToken: String? = null
    ): ApiResponse<Pair<List<Memo>, String?>>

    abstract suspend fun createGroupMessage(
        groupId: String,
        content: String,
        tags: List<String> = emptyList()
    ): ApiResponse<Memo>

    abstract suspend fun listGroupTags(groupId: String): ApiResponse<List<String>>
    abstract suspend fun addGroupTag(groupId: String, tag: String): ApiResponse<List<String>>

    abstract suspend fun listTags(): ApiResponse<List<String>>
    abstract suspend fun listResources(): ApiResponse<List<Resource>>

    abstract suspend fun createResource(
        filename: String,
        type: MediaType?,
        file: File,
        memoRemoteId: String? = null,
        thumbnail: ResourceUploadThumbnail? = null,
        onProgress: (uploadedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> }
    ): ApiResponse<Resource>

    abstract suspend fun deleteResource(remoteId: String): ApiResponse<Unit>
    abstract suspend fun getCurrentUser(): ApiResponse<User>
}
