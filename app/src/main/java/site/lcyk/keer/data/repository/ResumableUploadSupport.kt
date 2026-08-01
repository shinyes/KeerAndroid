package site.lcyk.keer.data.repository

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType
import okhttp3.RequestBody
import okio.BufferedSink
import site.lcyk.keer.data.api.KeerV2PayloadEnvelope
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import kotlin.math.min


@Serializable
internal data class ResumableUploadCreateRequest(
    val descriptorCiphertext: String,
    val descriptorEnvelope: KeerV2PayloadEnvelope? = null,
    val blobEncryption: String,
    val thumbnailBlobEncryption: String? = null,
    val filename: String,
    val type: String,
    val size: Long,
    val memo: String?,
    val thumbnail: ResumableUploadThumbnailRequest? = null
)

@Serializable
internal data class ResumableUploadThumbnailRequest(
    val filename: String,
    val type: String,
    val content: String
)

@Serializable
internal data class ResumableUploadCreateResponse(
    val uploadId: String,
    val uploadedSize: String = "0",
    val size: String? = null,
    val uploadMode: String? = null,
    val directUploadUrl: String? = null,
    val directUploadMethod: String? = null,
    val multipartPartSize: String? = null
)

@Serializable
internal data class MultipartPartUploadResponse(
    val uploadId: String,
    val partNumber: Int,
    val offset: String,
    val size: String,
    val uploadUrl: String,
    val method: String? = null
)

internal data class UploadSessionState(
    val uploadId: String,
    val offset: Long,
    val uploadMode: String? = null,
    val multipartPartSizeBytes: Long? = null
)

internal enum class UploadOffsetQueryStatus {
    SUCCESS,
    NOT_FOUND,
    DIRECT_UNSUPPORTED,
    ERROR
}

internal data class UploadOffsetQueryResult(
    val status: UploadOffsetQueryStatus,
    val offset: Long,
    val uploadMode: String? = null,
    val multipartPartSizeBytes: Long? = null
)

@Serializable
internal data class UploadCheckpoint(
    val uploadId: String,
    val totalBytes: Long,
    val uploadedBytes: Long = 0L,
    val updatedAtMillis: Long,
    val uploadMode: String? = null,
    val multipartPartSizeBytes: Long? = null
)

@Serializable
internal data class UploadCheckpointSnapshot(
    val entries: Map<String, UploadCheckpoint> = emptyMap()
)

internal class ResumableUploadCheckpointStore(
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

internal class ChunkFileRequestBody(
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

internal fun sha256Hex(input: String): String {
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
