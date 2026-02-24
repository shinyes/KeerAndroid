package site.lcyk.keer.data.repository

import com.skydoves.sandwich.ApiResponse
import com.skydoves.sandwich.mapSuccess
import site.lcyk.keer.data.api.MemosRowStatus
import site.lcyk.keer.data.api.MemosV0Api
import site.lcyk.keer.data.api.MemosV0CreateMemoInput
import site.lcyk.keer.data.api.MemosV0Memo
import site.lcyk.keer.data.api.MemosV0PatchMemoInput
import site.lcyk.keer.data.api.MemosV0Resource
import site.lcyk.keer.data.api.MemosV0UpdateMemoOrganizerInput
import site.lcyk.keer.data.api.MemosV0UpdateTagInput
import site.lcyk.keer.data.api.MemosVisibility
import site.lcyk.keer.data.constant.KeerException
import site.lcyk.keer.data.model.Account
import site.lcyk.keer.data.model.Memo
import site.lcyk.keer.data.model.MemoVisibility
import site.lcyk.keer.data.model.Resource
import site.lcyk.keer.data.model.User
import okhttp3.MediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.File
import java.io.RandomAccessFile
import java.time.Instant

class MemosV0Repository (
    private val memosApi: MemosV0Api,
    private val account: Account.MemosV0,
) : RemoteRepository() {
    private fun convertResource(resource: MemosV0Resource): Resource {
        return Resource(
            remoteId = resource.id.toString(),
            date = Instant.ofEpochSecond(resource.createdTs),
            filename = resource.filename,
            uri = resource.uri(account.info.host).toString(),
            mimeType = resource.type
        )
    }

    private fun convertMemo(memo: MemosV0Memo): Memo {
        return Memo(
            remoteId = memo.id.toString(),
            content = memo.content,
            date = Instant.ofEpochSecond(memo.createdTs),
            pinned = memo.pinned,
            visibility = memo.visibility.toMemoVisibility(),
            resources = memo.resourceList?.map { convertResource(it) } ?: emptyList(),
            tags = emptyList(),
            creator = memo.creatorName?.let { User(memo.creatorId.toString(), it) },
            archived = memo.rowStatus == MemosRowStatus.ARCHIVED,
            updatedAt = Instant.ofEpochSecond(memo.updatedTs)
        )
    }

    override suspend fun listMemos(): ApiResponse<List<Memo>> {
        return memosApi.listMemo(rowStatus = MemosRowStatus.NORMAL).mapSuccess {
            this.map { convertMemo(it) }
        }
    }

    override suspend fun listArchivedMemos(): ApiResponse<List<Memo>> {
        return memosApi.listMemo(rowStatus = MemosRowStatus.ARCHIVED).mapSuccess {
            this.map { convertMemo(it) }
        }
    }

    override suspend fun createMemo(
        content: String,
        visibility: MemoVisibility,
        resourceRemoteIds: List<String>,
        tags: List<String>?,
        createdAt: Instant?
    ): ApiResponse<Memo> {
        val result = memosApi.createMemo(
            MemosV0CreateMemoInput(
                content = content,
                resourceIdList = resourceRemoteIds.map { it.toLong() },
                visibility = MemosVisibility.fromMemoVisibility(visibility),
                createdTs = createdAt?.epochSecond
            )
        ).mapSuccess {
            convertMemo(this)
        }
        tags?.forEach { tag ->
            memosApi.updateTag(MemosV0UpdateTagInput(tag))
        }
        return result
    }

    override suspend fun updateMemo(
        remoteId: String,
        content: String?,
        resourceRemoteIds: List<String>?,
        visibility: MemoVisibility?,
        tags: List<String>?,
        pinned: Boolean?,
        archived: Boolean?
    ): ApiResponse<Memo> {
        var touched = false
        var result: ApiResponse<Memo> = ApiResponse.exception(KeerException.invalidParameter)
        pinned?.let {
            touched = true
            result = memosApi.updateMemoOrganizer(remoteId.toLong(), MemosV0UpdateMemoOrganizerInput(pinned = it)).mapSuccess {
                convertMemo(this)
            }
            if (result !is ApiResponse.Success<Memo>) {
                return result
            }
        }
        if (content != null || visibility != null || resourceRemoteIds != null || archived != null) {
            touched = true
            val memosVisibility = visibility?.let { v -> MemosVisibility.fromMemoVisibility(v) }
            val resourceIdList = resourceRemoteIds?.map { it.toLong() }
            val rowStatus = archived?.let { isArchived ->
                if (isArchived) MemosRowStatus.ARCHIVED else MemosRowStatus.NORMAL
            }
            result = memosApi.patchMemo(
                remoteId.toLong(),
                MemosV0PatchMemoInput(
                    id = remoteId.toLong(),
                    content = content,
                    visibility = memosVisibility,
                    resourceIdList = resourceIdList,
                    rowStatus = rowStatus
                )
            ).mapSuccess {
                convertMemo(this)
            }
        }
        tags?.forEach { tag ->
            memosApi.updateTag(MemosV0UpdateTagInput(tag))
        }
        return if (touched) result else ApiResponse.exception(KeerException.invalidParameter)
    }

    override suspend fun deleteMemo(remoteId: String): ApiResponse<Unit> {
        return memosApi.deleteMemo(remoteId.toLong())
    }

    override suspend fun listTags(): ApiResponse<List<String>> {
        return memosApi.getTags()
    }

    override suspend fun listResources(): ApiResponse<List<Resource>> {
        return memosApi.getResources().mapSuccess {
            this.map { convertResource(it) }
        }
    }

    override suspend fun createResource(
        filename: String,
        type: MediaType?,
        file: File,
        memoRemoteId: String?,
        onProgress: (uploadedBytes: Long, totalBytes: Long) -> Unit
    ): ApiResponse<Resource> {
        val requestBody = ProgressFileRequestBody(
            file = file,
            mediaType = type,
            onProgress = onProgress
        )
        val multipart = MultipartBody.Part.createFormData("file", filename, requestBody)
        return memosApi.uploadResource(multipart).mapSuccess {
            convertResource(this)
        }
    }

    override suspend fun deleteResource(remoteId: String): ApiResponse<Unit> {
        return memosApi.deleteResource(remoteId.toLong())
    }

    override suspend fun listWorkspaceMemos(
        pageSize: Int,
        pageToken: String?
    ): ApiResponse<Pair<List<Memo>, String?>> {
        return memosApi.listAllMemo(limit = pageSize, offset = pageToken?.toIntOrNull()).mapSuccess {
            val memos = this.map { convertMemo(it) }
            val currentOffset = pageToken?.toIntOrNull() ?: 0
            val nextPageToken = if (memos.size < pageSize) {
                null
            } else {
                (currentOffset + pageSize).toString()
            }
            memos to nextPageToken
        }
    }

    override suspend fun getCurrentUser(): ApiResponse<User> {
        return memosApi.me().mapSuccess {
            toUser()
        }
    }
}

private class ProgressFileRequestBody(
    private val file: File,
    private val mediaType: MediaType?,
    private val onProgress: (uploadedBytes: Long, totalBytes: Long) -> Unit
) : RequestBody() {
    override fun contentType(): MediaType? = mediaType

    override fun contentLength(): Long = file.length()

    override fun writeTo(sink: BufferedSink) {
        val total = contentLength().coerceAtLeast(0L)
        RandomAccessFile(file, "r").use { raf ->
            val buffer = ByteArray(8 * 1024)
            var written = 0L
            while (true) {
                val read = raf.read(buffer)
                if (read <= 0) {
                    break
                }
                sink.write(buffer, 0, read)
                written += read
                onProgress(written, total)
            }
        }
    }
}
