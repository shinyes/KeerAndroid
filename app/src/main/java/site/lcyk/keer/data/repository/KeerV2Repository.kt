package site.lcyk.keer.data.repository

import com.skydoves.sandwich.ApiResponse
import com.skydoves.sandwich.getOrNull
import com.skydoves.sandwich.mapSuccess
import com.skydoves.sandwich.onSuccess
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import site.lcyk.keer.data.api.KeerV2Api
import site.lcyk.keer.data.api.KeerV2CreateMemoRequest
import site.lcyk.keer.data.api.KeerV2Memo
import site.lcyk.keer.data.api.KeerV2Resource
import site.lcyk.keer.data.api.KeerV2State
import site.lcyk.keer.data.api.MemosVisibility
import site.lcyk.keer.data.api.UpdateMemoRequest
import site.lcyk.keer.data.constant.KeerException
import site.lcyk.keer.data.model.Account
import site.lcyk.keer.data.model.Memo
import site.lcyk.keer.data.model.MemoVisibility
import site.lcyk.keer.data.model.Resource
import site.lcyk.keer.data.model.User
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import java.io.File
import java.io.RandomAccessFile
import java.time.Instant
import kotlin.math.min

private const val PAGE_SIZE = 200
private const val uploadChunkSizeBytes = 8L * 1024L * 1024L
private const val maxChunkRetryCount = 6
private const val retryDelayMillis = 500L
private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
private val uploadChunkMediaType = "application/offset+octet-stream".toMediaType()
private val uploadJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    explicitNulls = false
}

class KeerV2Repository(
    private val memosApi: KeerV2Api,
    private val account: Account.KeerV2,
    private val okHttpClient: OkHttpClient
): RemoteRepository() {
    private fun convertResource(resource: KeerV2Resource): Resource {
        return Resource(
            remoteId = requireNotNull(resource.name),
            date = resource.createTime ?: Instant.now(),
            filename = resource.filename ?: "",
            uri = resource.uri(account.info.host).toString(),
            mimeType = resource.type
        )
    }

    private fun convertMemo(memo: KeerV2Memo): Memo {
        return Memo(
            remoteId = memo.name,
            content = memo.content ?: "",
            date = memo.displayTime ?: Instant.now(),
            pinned = memo.pinned ?: false,
            visibility = memo.visibility?.toMemoVisibility() ?: MemoVisibility.PRIVATE,
            resources = memo.attachments?.map { convertResource(it) } ?: emptyList(),
            tags = emptyList(),
            archived = memo.state == KeerV2State.ARCHIVED,
            updatedAt = memo.updateTime
        )
    }

    private suspend fun listMemosByFilter(state: KeerV2State, filter: String): ApiResponse<List<Memo>> {
        var nextPageToken = ""
        val memos = arrayListOf<Memo>()

        do {
            val resp = memosApi.listMemos(PAGE_SIZE, nextPageToken, state, filter)
                .onSuccess { nextPageToken = data.nextPageToken.orEmpty() }
                .mapSuccess { this.memos.map { convertMemo(it) } }
            if (resp is ApiResponse.Success) {
                memos.addAll(resp.data)
            } else {
                return resp
            }
        } while (nextPageToken.isNotEmpty())
        return ApiResponse.Success(memos)
    }

    private fun getId(identifier: String): String {
        return identifier.substringBefore('|').substringAfterLast('/')
    }

    private fun getName(identifier: String): String {
        return identifier.substringBefore('|')
    }

    override suspend fun listMemos(): ApiResponse<List<Memo>> {
        return listMemosByFilter(KeerV2State.NORMAL, "creator_id == ${account.info.id}")
    }

    override suspend fun listArchivedMemos(): ApiResponse<List<Memo>> {
        return listMemosByFilter(KeerV2State.ARCHIVED, "creator_id == ${account.info.id}")
    }

    override suspend fun listWorkspaceMemos(
        pageSize: Int,
        pageToken: String?
    ): ApiResponse<Pair<List<Memo>, String?>> {
        val resp = memosApi.listMemos(pageSize, pageToken, filter = "visibility in [\"PUBLIC\", \"PROTECTED\"]")
        if (resp !is ApiResponse.Success) {
            return resp.mapSuccess { emptyList<Memo>() to null }
        }
        val users = resp.data.memos.mapNotNull { it.creator }.map { getId(it) }.toSet()
        val userResp = coroutineScope {
            users.map { userId ->
                async { memosApi.getUser(userId).getOrNull() }
            }.awaitAll()
        }.filterNotNull()
        val userMap = mapOf(*userResp.map { user -> user.name to user }.toTypedArray())

        return resp
            .mapSuccess { this.memos.map {
                convertMemo(it).copy(
                    creator = it.creator?.let { creator ->
                        userMap[creator]?.let { user ->
                            User(
                                user.name,
                                user.displayName ?: user.username,
                                user.createTime ?: Instant.now()
                            )
                        }
                    }
                )
            } to this.nextPageToken?.ifEmpty { null } }
    }

    override suspend fun createMemo(
        content: String,
        visibility: MemoVisibility,
        resourceRemoteIds: List<String>,
        tags: List<String>?,
        createdAt: Instant?
    ): ApiResponse<Memo> {
        val resp = memosApi.createMemo(
            KeerV2CreateMemoRequest(
                content = content,
                visibility = MemosVisibility.fromMemoVisibility(visibility),
                attachments = resourceRemoteIds.map { KeerV2Resource(name = getName(it)) },
                createTime = createdAt
            )
        )
            .mapSuccess { convertMemo(this) }
        return resp
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
        val resp = memosApi.updateMemo(getId(remoteId), UpdateMemoRequest(
            content = content,
            visibility = visibility?.let { MemosVisibility.fromMemoVisibility(it) },
            pinned = pinned,
            state = archived?.let { isArchived -> if (isArchived) KeerV2State.ARCHIVED else KeerV2State.NORMAL },
            updateTime = Instant.now(),
            attachments = resourceRemoteIds?.map { KeerV2Resource(name = getName(it)) }
        )).mapSuccess { convertMemo(this) }
        return resp
    }

    override suspend fun deleteMemo(remoteId: String): ApiResponse<Unit> {
        return memosApi.deleteMemo(getId(remoteId))
    }

    override suspend fun listTags(): ApiResponse<List<String>> {
        return memosApi.getUserStats(account.info.id.toString()).mapSuccess {
            tagCount.keys.toList()
          }
    }

    override suspend fun listResources(): ApiResponse<List<Resource>> {
        return memosApi.listResources().mapSuccess { this.attachments.map { convertResource(it) } }
    }

    override suspend fun createResource(
        filename: String,
        type: MediaType?,
        file: File,
        memoRemoteId: String?,
        onProgress: (uploadedBytes: Long, totalBytes: Long) -> Unit
    ): ApiResponse<Resource> {
        val totalBytes = file.length()
        if (totalBytes <= 0L) {
            return ApiResponse.Failure.Exception(IllegalArgumentException("file is empty"))
        }

        val baseUrl = account.info.host.trimEnd('/')
        val createRequestBody = uploadJson.encodeToString(
            ResumableUploadCreateRequest.serializer(),
            ResumableUploadCreateRequest(
                filename = filename,
                type = type?.toString() ?: "application/octet-stream",
                size = totalBytes,
                memo = memoRemoteId?.let { getName(it) }
            )
        )
        val createRequest = Request.Builder()
            .url("$baseUrl/api/v1/attachments/uploads")
            .post(createRequestBody.toRequestBody(jsonMediaType))
            .build()

        val createResponse = try {
            okHttpClient.newCall(createRequest).execute()
        } catch (e: Throwable) {
            return ApiResponse.Failure.Exception(e)
        }
        val session = createResponse.use { response ->
            if (!response.isSuccessful) {
                return ApiResponse.Failure.Exception(
                    IllegalStateException("create upload failed: HTTP ${response.code}")
                )
            }
            val body = response.body.string()
            if (body.isBlank()) {
                return ApiResponse.Failure.Exception(IllegalStateException("create upload response empty"))
            }
            try {
                uploadJson.decodeFromString(ResumableUploadCreateResponse.serializer(), body)
            } catch (e: Throwable) {
                return ApiResponse.Failure.Exception(e)
            }
        }

        var offset = session.uploadedSize.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
        val uploadId = session.uploadId
        if (uploadId.isBlank()) {
            return ApiResponse.Failure.Exception(IllegalStateException("missing upload id"))
        }

        var retryCount = 0
        while (offset < totalBytes) {
            val chunkLength = min(uploadChunkSizeBytes, totalBytes - offset)
            val chunkBody = ChunkFileRequestBody(
                file = file,
                offset = offset,
                length = chunkLength,
                mediaType = uploadChunkMediaType,
                onProgress = { sent, _ ->
                    onProgress((offset + sent).coerceAtMost(totalBytes), totalBytes)
                }
            )
            val patchRequest = Request.Builder()
                .url("$baseUrl/api/v1/attachments/uploads/$uploadId")
                .patch(chunkBody)
                .header("Upload-Offset", offset.toString())
                .build()

            val patchResponse = try {
                okHttpClient.newCall(patchRequest).execute()
            } catch (_: Throwable) {
                retryCount += 1
                if (retryCount > maxChunkRetryCount) {
                    return ApiResponse.Failure.Exception(
                        IllegalStateException("upload chunk failed after retries")
                    )
                }
                val latestOffset = queryUploadOffset(baseUrl, uploadId)
                if (latestOffset >= 0L) {
                    offset = latestOffset
                }
                delay(retryDelayMillis)
                continue
            }

            val handled = patchResponse.use { response ->
                if (response.isSuccessful) {
                    val nextOffset = response.header("Upload-Offset")?.toLongOrNull()
                    offset = nextOffset ?: (offset + chunkLength)
                    onProgress(offset.coerceAtMost(totalBytes), totalBytes)
                    retryCount = 0
                    true
                } else {
                    val isConflict = response.code == 409 || response.code == 412
                    if (isConflict) {
                        val latestOffset = response.header("Upload-Offset")?.toLongOrNull()
                            ?: queryUploadOffset(baseUrl, uploadId)
                        if (latestOffset >= 0L) {
                            offset = latestOffset
                        }
                        retryCount += 1
                        false
                    } else {
                        return ApiResponse.Failure.Exception(
                            IllegalStateException("upload chunk failed: HTTP ${response.code}")
                        )
                    }
                }
            }
            if (!handled) {
                if (retryCount > maxChunkRetryCount) {
                    return ApiResponse.Failure.Exception(
                        IllegalStateException("upload chunk conflict after retries")
                    )
                }
                delay(retryDelayMillis)
            }
        }

        val completeRequest = Request.Builder()
            .url("$baseUrl/api/v1/attachments/uploads/$uploadId/complete")
            .post(ByteArray(0).toRequestBody(null))
            .build()
        val completeResponse = try {
            okHttpClient.newCall(completeRequest).execute()
        } catch (e: Throwable) {
            return ApiResponse.Failure.Exception(e)
        }
        val uploadedResource = completeResponse.use { response ->
            if (!response.isSuccessful) {
                return ApiResponse.Failure.Exception(
                    IllegalStateException("complete upload failed: HTTP ${response.code}")
                )
            }
            val body = response.body.string()
            if (body.isBlank()) {
                return ApiResponse.Failure.Exception(IllegalStateException("complete upload response empty"))
            }
            try {
                uploadJson.decodeFromString(KeerV2Resource.serializer(), body)
            } catch (e: Throwable) {
                return ApiResponse.Failure.Exception(e)
            }
        }

        return ApiResponse.Success(convertResource(uploadedResource))
    }

    private fun queryUploadOffset(baseUrl: String, uploadId: String): Long {
        val request = Request.Builder()
            .url("$baseUrl/api/v1/attachments/uploads/$uploadId")
            .head()
            .build()
        val response = try {
            okHttpClient.newCall(request).execute()
        } catch (_: Throwable) {
            return -1L
        }
        return response.use {
            if (!response.isSuccessful) {
                return -1L
            }
            response.header("Upload-Offset")?.toLongOrNull() ?: -1L
        }
    }

    override suspend fun deleteResource(remoteId: String): ApiResponse<Unit> {
        return memosApi.deleteResource(getId(remoteId))
    }

    override suspend fun getCurrentUser(): ApiResponse<User> {
        val resp = memosApi.getCurrentUser().mapSuccess {
            if (user == null) {
                throw KeerException.notLogin
            }
            User(
                user.name,
                user.displayName ?: user.username,
                user.createTime ?: Instant.now(),
                avatarUrl = user.avatarUrl
            )
        }
        if (resp !is ApiResponse.Success) {
            return resp
        }

        return memosApi.getUserSetting(getId(resp.data.identifier)).mapSuccess {
            resp.data.copy(
                defaultVisibility = generalSetting?.memoVisibility?.toMemoVisibility() ?: MemoVisibility.PRIVATE
            )
        }
    }
}

@Serializable
private data class ResumableUploadCreateRequest(
    val filename: String,
    val type: String,
    val size: Long,
    val memo: String?
)

@Serializable
private data class ResumableUploadCreateResponse(
    val uploadId: String,
    val uploadedSize: String = "0",
    val size: String? = null
)

private class ChunkFileRequestBody(
    private val file: File,
    private val offset: Long,
    private val length: Long,
    private val mediaType: MediaType,
    private val onProgress: (uploadedBytes: Long, totalBytes: Long) -> Unit
) : RequestBody() {
    override fun contentType(): MediaType = mediaType

    override fun contentLength(): Long = length

    override fun writeTo(sink: BufferedSink) {
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(offset)
            val buffer = ByteArray(8 * 1024)
            var written = 0L
            while (written < length) {
                val remaining = length - written
                val toRead = min(buffer.size.toLong(), remaining).toInt()
                val read = raf.read(buffer, 0, toRead)
                if (read <= 0) {
                    break
                }
                sink.write(buffer, 0, read)
                written += read
                onProgress(written, length)
            }
        }
    }
}
