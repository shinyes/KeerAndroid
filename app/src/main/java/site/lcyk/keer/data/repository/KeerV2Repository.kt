package site.lcyk.keer.data.repository

import android.content.Context
import com.skydoves.sandwich.ApiResponse
import com.skydoves.sandwich.getOrNull
import com.skydoves.sandwich.mapSuccess
import com.skydoves.sandwich.onSuccess
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
import okhttp3.Response
import okio.BufferedSink
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.time.Instant
import kotlin.math.min

private const val PAGE_SIZE = 200
private const val uploadChunkSizeBytes = 8L * 1024L * 1024L
private const val maxChunkRetryCount = 6
private const val retryDelayMillis = 500L
private const val uploadCheckpointTTLMillis = 24L * 60L * 60L * 1000L
private const val uploadCheckpointCleanupIntervalMillis = 15L * 60L * 1000L
private const val uploadCheckpointMaxEntries = 256
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
    private val okHttpClient: OkHttpClient,
    appContext: Context
): RemoteRepository() {
    private val uploadCheckpointStore = ResumableUploadCheckpointStore(
        File(
            File(appContext.filesDir, "resumable_uploads"),
            "${sha256Hex(account.accountKey())}.json"
        )
    )
    @Volatile
    private var lastCheckpointCleanupAtMillis: Long = 0L

    private fun convertResource(resource: KeerV2Resource): Resource {
        return Resource(
            remoteId = requireNotNull(resource.name),
            date = resource.createTime ?: Instant.now(),
            filename = resource.filename ?: "",
            uri = resource.uri(account.info.host).toString(),
            mimeType = resource.type,
            thumbnailUri = resource.thumbnailUri(account.info.host)?.toString()
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
            tags = memo.tags ?: emptyList(),
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
                tags = tags,
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
            tags = tags,
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
        thumbnail: ResourceUploadThumbnail?,
        onProgress: (uploadedBytes: Long, totalBytes: Long) -> Unit
    ): ApiResponse<Resource> = withContext(Dispatchers.IO) {
        val totalBytes = file.length()
        if (totalBytes <= 0L) {
            return@withContext failure("prepare upload", "file is empty")
        }

        val baseUrl = account.info.host.trimEnd('/')
        maybeCleanupStaleUploadCheckpoints(baseUrl)
        val checkpointKey = buildUploadCheckpointKey(
            filename = filename,
            type = type,
            file = file,
            memoRemoteId = memoRemoteId,
            thumbnail = thumbnail
        )

        var uploadId = ""
        var offset = 0L

        val resumedSession = resolveExistingUploadSession(baseUrl, checkpointKey, totalBytes)
        if (resumedSession != null) {
            uploadId = resumedSession.uploadId
            offset = resumedSession.offset
            onProgress(offset.coerceAtMost(totalBytes), totalBytes)
        }

        if (uploadId.isEmpty()) {
            val createRequestBody = runCatching {
                uploadJson.encodeToString(
                    ResumableUploadCreateRequest.serializer(),
                    ResumableUploadCreateRequest(
                        filename = filename,
                        type = type?.toString() ?: "application/octet-stream",
                        size = totalBytes,
                        memo = memoRemoteId?.let { getName(it) },
                        thumbnail = thumbnail?.let {
                            ResumableUploadThumbnailRequest(
                                filename = it.filename,
                                type = it.type,
                                content = it.content
                            )
                        }
                    )
                )
            }.getOrElse { throwable ->
                return@withContext failure("prepare upload", "serialize create payload failed", throwable)
            }

            val createURL = "$baseUrl/api/v1/attachments/uploads"
            val createRequest = runCatching {
                Request.Builder()
                    .url(createURL)
                    .post(createRequestBody.toRequestBody(jsonMediaType))
                    .build()
            }.getOrElse { throwable ->
                return@withContext failure("prepare upload", "build create request failed ($createURL)", throwable)
            }

            val createResponse = try {
                okHttpClient.newCall(createRequest).execute()
            } catch (e: Throwable) {
                return@withContext failure("create upload", "request execution failed ($createURL)", e)
            }

            var createFailure: ApiResponse.Failure.Exception? = null
            var session: ResumableUploadCreateResponse? = null
            createResponse.use { response ->
                if (!response.isSuccessful) {
                    createFailure = httpFailure("create upload", response)
                    return@use
                }
                val body = response.body.string()
                if (body.isBlank()) {
                    createFailure = failure("create upload", "response empty")
                    return@use
                }
                try {
                    session = uploadJson.decodeFromString(ResumableUploadCreateResponse.serializer(), body)
                } catch (e: Throwable) {
                    createFailure = failure("create upload", "decode response failed", e)
                }
            }
            val resolvedCreateFailure = createFailure
            if (resolvedCreateFailure != null) {
                return@withContext resolvedCreateFailure
            }
            val resolvedSession = session ?: return@withContext failure(
                "create upload",
                "missing upload session in response"
            )
            val resolvedUploadID = resolvedSession.uploadId.trim()
            if (resolvedUploadID.isEmpty()) {
                return@withContext failure("create upload", "missing upload id")
            }

            uploadId = resolvedUploadID
            offset = (resolvedSession.uploadedSize.toLongOrNull() ?: 0L).coerceIn(0L, totalBytes)
            uploadCheckpointStore.upsert(
                checkpointKey,
                UploadCheckpoint(
                    uploadId = resolvedUploadID,
                    totalBytes = totalBytes,
                    uploadedBytes = offset,
                    updatedAtMillis = System.currentTimeMillis()
                )
            )
        }

        if (uploadId.isBlank()) {
            return@withContext failure("create upload", "missing upload id")
        }
        val resolvedUploadId = uploadId

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
                .url("$baseUrl/api/v1/attachments/uploads/$resolvedUploadId")
                .patch(chunkBody)
                .header("Upload-Offset", offset.toString())
                .build()

            val patchResponse = try {
                okHttpClient.newCall(patchRequest).execute()
            } catch (e: Throwable) {
                retryCount += 1
                if (retryCount > maxChunkRetryCount) {
                    return@withContext failure("upload chunk", "request execution failed after retries", e)
                }
                val latestOffset = queryUploadOffset(baseUrl, resolvedUploadId)
                if (latestOffset >= 0L) {
                    offset = latestOffset.coerceIn(0L, totalBytes)
                    uploadCheckpointStore.updateProgress(checkpointKey, offset)
                }
                delay(retryDelayMillis)
                continue
            }

            var chunkFatal: ApiResponse.Failure.Exception? = null
            val handled = patchResponse.use { response ->
                if (response.isSuccessful) {
                    val nextOffset = response.header("Upload-Offset")?.toLongOrNull()
                    offset = (nextOffset ?: (offset + chunkLength)).coerceIn(0L, totalBytes)
                    uploadCheckpointStore.updateProgress(checkpointKey, offset)
                    onProgress(offset.coerceAtMost(totalBytes), totalBytes)
                    retryCount = 0
                    true
                } else {
                    val isConflict = response.code == 409 || response.code == 412
                    if (isConflict) {
                        val latestOffset = response.header("Upload-Offset")?.toLongOrNull()
                            ?: queryUploadOffset(baseUrl, resolvedUploadId)
                        if (latestOffset >= 0L) {
                            offset = latestOffset.coerceIn(0L, totalBytes)
                            uploadCheckpointStore.updateProgress(checkpointKey, offset)
                        }
                        retryCount += 1
                        false
                    } else {
                        if (response.code == 404 || response.code == 410) {
                            uploadCheckpointStore.remove(checkpointKey)
                        }
                        chunkFatal = httpFailure("upload chunk", response)
                        false
                    }
                }
            }
            val resolvedChunkFatal = chunkFatal
            if (resolvedChunkFatal != null) {
                return@withContext resolvedChunkFatal
            }
            if (!handled) {
                if (retryCount > maxChunkRetryCount) {
                    return@withContext failure("upload chunk", "offset conflict after retries")
                }
                delay(retryDelayMillis)
            }
        }

        val completeRequest = Request.Builder()
            .url("$baseUrl/api/v1/attachments/uploads/$resolvedUploadId/complete")
            .post(ByteArray(0).toRequestBody(null))
            .build()
        val completeResponse = try {
            okHttpClient.newCall(completeRequest).execute()
        } catch (e: Throwable) {
            return@withContext failure("complete upload", "request execution failed", e)
        }

        var completeFailure: ApiResponse.Failure.Exception? = null
        var uploadedResource: KeerV2Resource? = null
        completeResponse.use { response ->
            if (!response.isSuccessful) {
                if (response.code == 404 || response.code == 410) {
                    uploadCheckpointStore.remove(checkpointKey)
                }
                completeFailure = httpFailure("complete upload", response)
                return@use
            }
            val body = response.body.string()
            if (body.isBlank()) {
                completeFailure = failure("complete upload", "response empty")
                return@use
            }
            try {
                uploadedResource = uploadJson.decodeFromString(KeerV2Resource.serializer(), body)
            } catch (e: Throwable) {
                completeFailure = failure("complete upload", "decode response failed", e)
            }
        }
        val resolvedCompleteFailure = completeFailure
        if (resolvedCompleteFailure != null) {
            return@withContext resolvedCompleteFailure
        }
        val resolvedResource = uploadedResource ?: return@withContext failure(
            "complete upload",
            "missing attachment in response"
        )
        uploadCheckpointStore.remove(checkpointKey)

        return@withContext ApiResponse.Success(convertResource(resolvedResource))
    }

    private fun maybeCleanupStaleUploadCheckpoints(baseUrl: String) {
        val now = System.currentTimeMillis()
        val shouldRun = synchronized(this) {
            if (now - lastCheckpointCleanupAtMillis < uploadCheckpointCleanupIntervalMillis) {
                false
            } else {
                lastCheckpointCleanupAtMillis = now
                true
            }
        }
        if (!shouldRun) {
            return
        }
        val removed = uploadCheckpointStore.prune(
            now = now,
            maxAgeMillis = uploadCheckpointTTLMillis,
            maxEntries = uploadCheckpointMaxEntries
        )
        removed
            .asSequence()
            .map { it.uploadId }
            .filter { it.isNotBlank() }
            .distinct()
            .forEach { staleUploadId ->
                cancelUploadSession(baseUrl, staleUploadId)
            }
    }

    private fun resolveExistingUploadSession(
        baseUrl: String,
        checkpointKey: String,
        totalBytes: Long
    ): UploadSessionState? {
        val checkpoint = uploadCheckpointStore.get(checkpointKey) ?: return null
        if (checkpoint.totalBytes != totalBytes || checkpoint.uploadId.isBlank()) {
            uploadCheckpointStore.remove(checkpointKey)
            return null
        }

        val queryResult = queryUploadOffsetWithStatus(baseUrl, checkpoint.uploadId)
        val resolvedOffset = when (queryResult.status) {
            UploadOffsetQueryStatus.SUCCESS -> queryResult.offset
            UploadOffsetQueryStatus.NOT_FOUND -> {
                uploadCheckpointStore.remove(checkpointKey)
                return null
            }
            UploadOffsetQueryStatus.ERROR -> checkpoint.uploadedBytes
        }.coerceIn(0L, totalBytes)

        uploadCheckpointStore.updateProgress(checkpointKey, resolvedOffset)
        return UploadSessionState(
            uploadId = checkpoint.uploadId,
            offset = resolvedOffset
        )
    }

    private fun cancelUploadSession(baseUrl: String, uploadId: String) {
        if (uploadId.isBlank()) {
            return
        }
        val request = Request.Builder()
            .url("$baseUrl/api/v1/attachments/uploads/$uploadId")
            .delete()
            .build()
        runCatching {
            okHttpClient.newCall(request).execute().use { }
        }
    }

    private fun buildUploadCheckpointKey(
        filename: String,
        type: MediaType?,
        file: File,
        memoRemoteId: String?,
        thumbnail: ResourceUploadThumbnail?
    ): String {
        val raw = buildString {
            append(account.accountKey())
            append('\n')
            append(file.absolutePath)
            append('\n')
            append(file.length())
            append('\n')
            append(file.lastModified())
            append('\n')
            append(filename.trim())
            append('\n')
            append(type?.toString().orEmpty())
            append('\n')
            append(memoRemoteId?.let(::getName).orEmpty())
            append('\n')
            append(thumbnail?.filename.orEmpty())
            append('\n')
            append(thumbnail?.type.orEmpty())
            append('\n')
            append(thumbnail?.content?.let(::sha256Hex).orEmpty())
        }
        return sha256Hex(raw)
    }

    private fun queryUploadOffset(baseUrl: String, uploadId: String): Long {
        val result = queryUploadOffsetWithStatus(baseUrl, uploadId)
        return if (result.status == UploadOffsetQueryStatus.SUCCESS) {
            result.offset
        } else {
            -1L
        }
    }

    private fun queryUploadOffsetWithStatus(baseUrl: String, uploadId: String): UploadOffsetQueryResult {
        val request = Request.Builder()
            .url("$baseUrl/api/v1/attachments/uploads/$uploadId")
            .head()
            .build()
        val response = try {
            okHttpClient.newCall(request).execute()
        } catch (_: Throwable) {
            return UploadOffsetQueryResult(UploadOffsetQueryStatus.ERROR, -1L)
        }
        return response.use {
            if (!response.isSuccessful) {
                if (response.code == 404 || response.code == 410) {
                    return UploadOffsetQueryResult(UploadOffsetQueryStatus.NOT_FOUND, -1L)
                }
                return UploadOffsetQueryResult(UploadOffsetQueryStatus.ERROR, -1L)
            }
            val offset = response.header("Upload-Offset")?.toLongOrNull()
                ?: return UploadOffsetQueryResult(UploadOffsetQueryStatus.ERROR, -1L)
            UploadOffsetQueryResult(UploadOffsetQueryStatus.SUCCESS, offset)
        }
    }

    private fun httpFailure(stage: String, response: Response): ApiResponse.Failure.Exception {
        val code = response.code
        val detail = runCatching { response.body.string().trim() }
            .getOrElse { "" }
            .ifEmpty { response.message.trim() }
        val message = if (detail.isNotEmpty()) {
            "$stage failed: HTTP $code - $detail"
        } else {
            "$stage failed: HTTP $code"
        }
        return ApiResponse.Failure.Exception(IllegalStateException(message))
    }

    private fun failure(
        stage: String,
        message: String,
        throwable: Throwable? = null
    ): ApiResponse.Failure.Exception {
        val causeDetail = throwable?.let {
            val className = it::class.simpleName ?: "Throwable"
            val reason = it.message?.trim().orEmpty()
            if (reason.isNotEmpty()) "$className: $reason" else className
        }
        val fullMessage = if (!causeDetail.isNullOrEmpty()) {
            "$stage failed: $message - $causeDetail"
        } else {
            "$stage failed: $message"
        }
        return ApiResponse.Failure.Exception(IllegalStateException(fullMessage, throwable))
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
    val memo: String?,
    val thumbnail: ResumableUploadThumbnailRequest? = null
)

@Serializable
private data class ResumableUploadThumbnailRequest(
    val filename: String,
    val type: String,
    val content: String
)

@Serializable
private data class ResumableUploadCreateResponse(
    val uploadId: String,
    val uploadedSize: String = "0",
    val size: String? = null
)

private data class UploadSessionState(
    val uploadId: String,
    val offset: Long
)

private enum class UploadOffsetQueryStatus {
    SUCCESS,
    NOT_FOUND,
    ERROR
}

private data class UploadOffsetQueryResult(
    val status: UploadOffsetQueryStatus,
    val offset: Long
)

@Serializable
private data class UploadCheckpoint(
    val uploadId: String,
    val totalBytes: Long,
    val uploadedBytes: Long = 0L,
    val updatedAtMillis: Long
)

@Serializable
private data class UploadCheckpointSnapshot(
    val entries: Map<String, UploadCheckpoint> = emptyMap()
)

private class ResumableUploadCheckpointStore(
    private val file: File
) {
    private val lock = Any()
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
    }

    fun get(key: String): UploadCheckpoint? = synchronized(lock) {
        readSnapshot().entries[key]
    }

    fun upsert(key: String, checkpoint: UploadCheckpoint) = synchronized(lock) {
        val entries = readSnapshot().entries.toMutableMap()
        entries[key] = checkpoint
        writeSnapshot(UploadCheckpointSnapshot(entries))
    }

    fun updateProgress(key: String, uploadedBytes: Long) = synchronized(lock) {
        val snapshot = readSnapshot()
        val current = snapshot.entries[key] ?: return@synchronized
        val next = current.copy(
            uploadedBytes = uploadedBytes.coerceIn(0L, current.totalBytes),
            updatedAtMillis = System.currentTimeMillis()
        )
        val entries = snapshot.entries.toMutableMap()
        entries[key] = next
        writeSnapshot(UploadCheckpointSnapshot(entries))
    }

    fun remove(key: String): UploadCheckpoint? = synchronized(lock) {
        val entries = readSnapshot().entries.toMutableMap()
        val removed = entries.remove(key) ?: return@synchronized null
        writeSnapshot(UploadCheckpointSnapshot(entries))
        removed
    }

    fun prune(now: Long, maxAgeMillis: Long, maxEntries: Int): List<UploadCheckpoint> = synchronized(lock) {
        val snapshot = readSnapshot()
        if (snapshot.entries.isEmpty()) {
            return@synchronized emptyList()
        }
        val entries = snapshot.entries.toMutableMap()
        val removed = mutableListOf<UploadCheckpoint>()
        val expireBefore = now - maxAgeMillis

        val expiredKeys = entries
            .filterValues { it.updatedAtMillis < expireBefore }
            .keys
        expiredKeys.forEach { key ->
            entries.remove(key)?.let(removed::add)
        }

        if (entries.size > maxEntries) {
            val overflowKeys = entries.entries
                .sortedBy { it.value.updatedAtMillis }
                .take(entries.size - maxEntries)
                .map { it.key }
            overflowKeys.forEach { key ->
                entries.remove(key)?.let(removed::add)
            }
        }

        if (removed.isNotEmpty()) {
            writeSnapshot(UploadCheckpointSnapshot(entries))
        }
        removed
    }

    private fun readSnapshot(): UploadCheckpointSnapshot {
        if (!file.exists()) {
            return UploadCheckpointSnapshot()
        }
        return runCatching {
            val raw = file.readText()
            if (raw.isBlank()) {
                UploadCheckpointSnapshot()
            } else {
                json.decodeFromString(UploadCheckpointSnapshot.serializer(), raw)
            }
        }.getOrElse {
            UploadCheckpointSnapshot()
        }
    }

    private fun writeSnapshot(snapshot: UploadCheckpointSnapshot) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(json.encodeToString(UploadCheckpointSnapshot.serializer(), snapshot))
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
    }
}

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

private fun sha256Hex(input: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val bytes = digest.digest(input.toByteArray())
    return buildString(bytes.size * 2) {
        for (byte in bytes) {
            val v = byte.toInt() and 0xFF
            append("0123456789abcdef"[v ushr 4])
            append("0123456789abcdef"[v and 0x0F])
        }
    }
}
