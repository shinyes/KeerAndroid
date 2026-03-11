package site.lcyk.keer.data.security

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min

@Serializable
data class AttachmentEncryptionMetadata(
    val version: Int = 1,
    val algorithm: String = AttachmentEncryptionManager.ALGORITHM,
    val originalMimeType: String? = null,
    val main: EncryptedBlobMetadata,
    val thumbnail: EncryptedBlobMetadata? = null,
)

@Serializable
data class EncryptedBlobMetadata(
    val wrappedKeys: List<WrappedContentKey> = emptyList(),
    val noncePrefix: String,
    val plaintextSize: Long,
    val chunkSize: Int,
    val tagSize: Int = AttachmentEncryptionManager.DEFAULT_TAG_SIZE_BYTES,
)

enum class EncryptedBlobVariant {
    MAIN,
    THUMBNAIL,
}

data class PreparedEncryptedThumbnail(
    val filename: String,
    val type: String,
    val content: String,
)

data class PreparedEncryptedUpload(
    val file: File,
    val mimeType: String,
    val encryptionMetadata: String,
    val thumbnail: PreparedEncryptedThumbnail? = null,
)

@Singleton
class AttachmentEncryptionManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val secureAccountMasterKeyStorage: site.lcyk.keer.data.service.SecureAccountMasterKeyStorage by lazy {
        site.lcyk.keer.data.service.SecureAccountMasterKeyStorage(context.applicationContext)
    }

    fun prepareEncryptedUpload(
        accountKey: String,
        checkpointKey: String,
        sourceFile: File,
        originalMimeType: String?,
        thumbnail: PreparedEncryptedThumbnail?,
    ): PreparedEncryptedUpload {
        val preparedDir = preparedUploadDir(accountKey, checkpointKey)
        val payloadFile = File(preparedDir, "payload.bin")
        val thumbnailFile = File(preparedDir, "thumbnail.bin")
        val metadataFile = File(preparedDir, "metadata.json")

        if (payloadFile.exists() && metadataFile.exists()) {
            val cachedMetadata = parseMetadata(metadataFile.readText(StandardCharsets.UTF_8))
            if (cachedMetadata != null && isAccountMasterKeyWrapped(cachedMetadata)) {
                val cachedThumbnail = if (cachedMetadata.thumbnail != null && thumbnailFile.exists()) {
                    PreparedEncryptedThumbnail(
                        filename = thumbnail?.filename ?: "thumbnail.bin",
                        type = ENCRYPTED_MIME_TYPE,
                        content = base64Encode(thumbnailFile.readBytes()),
                    )
                } else {
                    null
                }
                return PreparedEncryptedUpload(
                    file = payloadFile,
                    mimeType = ENCRYPTED_MIME_TYPE,
                    encryptionMetadata = metadataFile.readText(StandardCharsets.UTF_8),
                    thumbnail = cachedThumbnail,
                )
            }
        }

        preparedDir.deleteRecursively()
        preparedDir.mkdirs()

        val mainDescriptor = FileInputStream(sourceFile).use { input ->
            encryptInputStreamToFile(
                accountKey = accountKey,
                input = input,
                plaintextSize = sourceFile.length(),
                outputFile = payloadFile,
            )
        }

        val thumbnailDescriptor = thumbnail?.let { plainThumbnail ->
            val thumbnailBytes = base64Decode(plainThumbnail.content)
            encryptByteArrayToFile(
                accountKey = accountKey,
                plaintext = thumbnailBytes,
                outputFile = thumbnailFile,
            )
        }

        val metadata = AttachmentEncryptionMetadata(
            originalMimeType = originalMimeType?.trim()?.ifBlank { null },
            main = mainDescriptor,
            thumbnail = thumbnailDescriptor,
        )
        val metadataRaw = json.encodeToString(
            AttachmentEncryptionMetadata.serializer(),
            metadata,
        )
        metadataFile.writeText(metadataRaw, StandardCharsets.UTF_8)

        val encryptedThumbnail = thumbnail?.takeIf { thumbnailDescriptor != null }?.let { originalThumbnail ->
            PreparedEncryptedThumbnail(
                filename = originalThumbnail.filename,
                type = ENCRYPTED_MIME_TYPE,
                content = base64Encode(thumbnailFile.readBytes()),
            )
        }

        return PreparedEncryptedUpload(
            file = payloadFile,
            mimeType = ENCRYPTED_MIME_TYPE,
            encryptionMetadata = metadataRaw,
            thumbnail = encryptedThumbnail,
        )
    }

    fun clearPreparedUpload(accountKey: String, checkpointKey: String) {
        preparedUploadDir(accountKey, checkpointKey).deleteRecursively()
    }

    fun decryptVariantToFile(
        accountKey: String?,
        rawMetadata: String,
        variant: EncryptedBlobVariant,
        input: InputStream,
        outputFile: File,
    ): File {
        val metadata = requireNotNull(parseMetadata(rawMetadata)) {
            "Invalid attachment encryption metadata"
        }
        val blob = requireNotNull(metadata.blobForVariant(variant)) {
            "Missing encrypted blob metadata"
        }
        val fileKey = unwrapFileKey(
            wrappedKeys = blob.wrappedKeys,
            accountKey = accountKey,
        )
        outputFile.parentFile?.mkdirs()
        outputFile.outputStream().use { output ->
            var chunkIndex = 0L
            var writtenPlaintextBytes = 0L
            while (writtenPlaintextBytes < blob.plaintextSize) {
                val expectedPlaintextSize = blob.chunkPlaintextSize(chunkIndex)
                val expectedCiphertextSize = expectedPlaintextSize + blob.tagSize
                val ciphertextChunk = readExactly(input, expectedCiphertextSize)
                val plaintextChunk = decryptChunk(
                    ciphertext = ciphertextChunk,
                    fileKey = fileKey,
                    noncePrefix = base64Decode(blob.noncePrefix),
                    chunkIndex = chunkIndex,
                    expectedPlaintextSize = expectedPlaintextSize,
                )
                output.write(plaintextChunk)
                writtenPlaintextBytes += plaintextChunk.size.toLong()
                chunkIndex += 1
            }
            output.flush()
        }
        return outputFile
    }

    fun decryptVariantFile(
        accountKey: String?,
        rawMetadata: String,
        variant: EncryptedBlobVariant,
        sourceFile: File,
        outputFile: File,
    ): File {
        FileInputStream(sourceFile).use { input ->
            return decryptVariantToFile(
                accountKey = accountKey,
                rawMetadata = rawMetadata,
                variant = variant,
                input = input,
                outputFile = outputFile,
            )
        }
    }

    fun createStreamingDataSourceFactory(
        accountKey: String?,
        okHttpClient: OkHttpClient,
        sourceUrl: String,
        rawMetadata: String,
        variant: EncryptedBlobVariant = EncryptedBlobVariant.MAIN,
    ): DataSource.Factory? {
        val metadata = parseMetadata(rawMetadata) ?: return null
        val blob = metadata.blobForVariant(variant) ?: return null
        val fileKey = runCatching {
            unwrapFileKey(
                wrappedKeys = blob.wrappedKeys,
                accountKey = accountKey,
            )
        }.getOrNull() ?: return null
        return DataSource.Factory {
            EncryptedHttpRangeDataSource(
                okHttpClient = okHttpClient,
                sourceUrl = sourceUrl,
                blob = blob,
                fileKey = fileKey,
            )
        }
    }

    fun unwrapVariantKey(
        accountKey: String,
        rawMetadata: String,
        variant: EncryptedBlobVariant,
    ): ByteArray? {
        val metadata = parseMetadata(rawMetadata) ?: return null
        val blob = metadata.blobForVariant(variant) ?: return null
        return runCatching {
            unwrapFileKey(
                wrappedKeys = blob.wrappedKeys,
                accountKey = accountKey,
            )
        }.getOrNull()
    }

    private fun encryptByteArrayToFile(
        accountKey: String,
        plaintext: ByteArray,
        outputFile: File,
    ): EncryptedBlobMetadata {
        outputFile.parentFile?.mkdirs()
        outputFile.outputStream().use { output ->
            val fileKey = randomBytes(KEY_SIZE_BYTES)
            val noncePrefix = randomBytes(NONCE_PREFIX_BYTES)
            val wrappedKeySlot = E2eeKeyEnvelope.wrapForAccountMasterKey(
                accountKey = accountKey,
                rawKey = fileKey,
                secureAccountMasterKeyStorage = secureAccountMasterKeyStorage,
            )
            var chunkIndex = 0L
            var offset = 0
            while (offset < plaintext.size) {
                val nextOffset = min(offset + DEFAULT_CHUNK_SIZE_BYTES, plaintext.size)
                val ciphertextChunk = encryptChunk(
                    plaintext = plaintext.copyOfRange(offset, nextOffset),
                    fileKey = fileKey,
                    noncePrefix = noncePrefix,
                    chunkIndex = chunkIndex,
                )
                output.write(ciphertextChunk)
                offset = nextOffset
                chunkIndex += 1
            }
            output.flush()
            return EncryptedBlobMetadata(
                wrappedKeys = listOf(wrappedKeySlot),
                noncePrefix = base64Encode(noncePrefix),
                plaintextSize = plaintext.size.toLong(),
                chunkSize = DEFAULT_CHUNK_SIZE_BYTES,
            )
        }
    }

    private fun encryptInputStreamToFile(
        accountKey: String,
        input: InputStream,
        plaintextSize: Long,
        outputFile: File,
    ): EncryptedBlobMetadata {
        outputFile.parentFile?.mkdirs()
        val buffer = ByteArray(DEFAULT_CHUNK_SIZE_BYTES)
        val fileKey = randomBytes(KEY_SIZE_BYTES)
        val noncePrefix = randomBytes(NONCE_PREFIX_BYTES)
        val wrappedKeySlot = E2eeKeyEnvelope.wrapForAccountMasterKey(
            accountKey = accountKey,
            rawKey = fileKey,
            secureAccountMasterKeyStorage = secureAccountMasterKeyStorage,
        )
        var chunkIndex = 0L

        outputFile.outputStream().use { output ->
            while (true) {
                val read = readChunk(input, buffer)
                if (read <= 0) {
                    break
                }
                val ciphertextChunk = encryptChunk(
                    plaintext = if (read == buffer.size) {
                        buffer.copyOf()
                    } else {
                        buffer.copyOf(read)
                    },
                    fileKey = fileKey,
                    noncePrefix = noncePrefix,
                    chunkIndex = chunkIndex,
                )
                output.write(ciphertextChunk)
                chunkIndex += 1
            }
            output.flush()
        }

        return EncryptedBlobMetadata(
            wrappedKeys = listOf(wrappedKeySlot),
            noncePrefix = base64Encode(noncePrefix),
            plaintextSize = plaintextSize,
            chunkSize = DEFAULT_CHUNK_SIZE_BYTES,
        )
    }

    private fun encryptChunk(
        plaintext: ByteArray,
        fileKey: ByteArray,
        noncePrefix: ByteArray,
        chunkIndex: Long,
    ): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(fileKey, KEY_ALGORITHM),
            GCMParameterSpec(GCM_TAG_LENGTH_BITS, chunkNonce(noncePrefix, chunkIndex)),
        )
        return cipher.doFinal(plaintext)
    }

    private fun decryptChunk(
        ciphertext: ByteArray,
        fileKey: ByteArray,
        noncePrefix: ByteArray,
        chunkIndex: Long,
        expectedPlaintextSize: Int,
    ): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(fileKey, KEY_ALGORITHM),
            GCMParameterSpec(GCM_TAG_LENGTH_BITS, chunkNonce(noncePrefix, chunkIndex)),
        )
        val plaintext = cipher.doFinal(ciphertext)
        if (plaintext.size != expectedPlaintextSize) {
            throw IOException("Unexpected decrypted chunk size")
        }
        return plaintext
    }

    private fun unwrapFileKey(
        wrappedKeys: List<WrappedContentKey>,
        accountKey: String?,
    ): ByteArray {
        return requireNotNull(
            E2eeKeyEnvelope.unwrapFirstSupportedKey(
                accountKey = accountKey,
                wrappedKeys = wrappedKeys,
                secureAccountMasterKeyStorage = secureAccountMasterKeyStorage,
            )
        ) {
            "Missing supported wrapped key"
        }
    }

    private fun preparedUploadDir(accountKey: String, checkpointKey: String): File {
        return File(
            File(context.filesDir, "encrypted_uploads/${sha256Hex(accountKey)}"),
            checkpointKey,
        )
    }

    companion object {
        internal const val ALGORITHM = "AES_GCM_CHUNKED_V1"
        internal const val DEFAULT_TAG_SIZE_BYTES = 16
        internal const val DEFAULT_CHUNK_SIZE_BYTES = 1024 * 1024
        internal const val ENCRYPTED_MIME_TYPE = "application/octet-stream"
        private const val KEY_SIZE_BYTES = 32
        private const val NONCE_PREFIX_BYTES = 8
        private const val NONCE_SIZE_BYTES = 12
        private const val KEY_ALGORITHM = "AES"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_LENGTH_BITS = 128
        private val json = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
            explicitNulls = false
        }

        fun parseMetadata(rawMetadata: String?): AttachmentEncryptionMetadata? {
            val trimmed = rawMetadata?.trim().orEmpty()
            if (trimmed.isEmpty()) {
                return null
            }
            return runCatching {
                json.decodeFromString(AttachmentEncryptionMetadata.serializer(), trimmed)
            }.getOrNull()?.takeIf { metadata ->
                metadata.version == 1 &&
                    metadata.algorithm == ALGORITHM &&
                    metadata.main.wrappedKeys.isNotEmpty() &&
                    metadata.main.chunkSize > 0 &&
                    metadata.main.tagSize > 0 &&
                    metadata.main.plaintextSize >= 0
            }
        }

        fun resolveOriginalMimeType(
            rawMetadata: String?,
            fallbackMimeType: String?,
        ): String? {
            return parseMetadata(rawMetadata)?.originalMimeType?.trim()?.ifBlank { null }
                ?: fallbackMimeType?.trim()?.ifBlank { null }
        }

        fun hasEncryptedThumbnail(rawMetadata: String?): Boolean {
            return parseMetadata(rawMetadata)?.thumbnail != null
        }

        private fun isAccountMasterKeyWrapped(metadata: AttachmentEncryptionMetadata): Boolean {
            val mainIsWrapped = metadata.main.wrappedKeys.any { slot ->
                slot.wrapAlgorithm == E2eeKeyEnvelope.ACCOUNT_MASTER_KEY_WRAP_ALGORITHM
            }
            if (!mainIsWrapped) {
                return false
            }
            val thumbnail = metadata.thumbnail ?: return true
            return thumbnail.wrappedKeys.any { slot ->
                slot.wrapAlgorithm == E2eeKeyEnvelope.ACCOUNT_MASTER_KEY_WRAP_ALGORITHM
            }
        }

        private fun sha256Hex(value: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val bytes = digest.digest(value.toByteArray(StandardCharsets.UTF_8))
            return buildString(bytes.size * 2) {
                bytes.forEach { byte ->
                    val b = byte.toInt() and 0xFF
                    append("0123456789abcdef"[b ushr 4])
                    append("0123456789abcdef"[b and 0x0F])
                }
            }
        }

        private fun base64Encode(bytes: ByteArray): String {
            return Base64.getEncoder().encodeToString(bytes)
        }

        private fun base64Decode(value: String): ByteArray {
            return Base64.getDecoder().decode(value)
        }

        private fun randomBytes(size: Int): ByteArray {
            val bytes = ByteArray(size)
            java.security.SecureRandom().nextBytes(bytes)
            return bytes
        }

        private fun chunkNonce(noncePrefix: ByteArray, chunkIndex: Long): ByteArray {
            require(noncePrefix.size == NONCE_PREFIX_BYTES) { "Invalid nonce prefix" }
            require(chunkIndex in 0..0xFFFF_FFFFL) { "Chunk index out of range" }
            return ByteBuffer.allocate(NONCE_SIZE_BYTES)
                .put(noncePrefix)
                .putInt(chunkIndex.toInt())
                .array()
        }

        private fun readChunk(input: InputStream, buffer: ByteArray): Int {
            var totalRead = 0
            while (totalRead < buffer.size) {
                val read = input.read(buffer, totalRead, buffer.size - totalRead)
                if (read <= 0) {
                    break
                }
                totalRead += read
            }
            return totalRead
        }

        private fun readExactly(input: InputStream, size: Int): ByteArray {
            val buffer = ByteArray(size)
            var totalRead = 0
            while (totalRead < size) {
                val read = input.read(buffer, totalRead, size - totalRead)
                if (read <= 0) {
                    throw EOFException("Encrypted attachment stream ended unexpectedly")
                }
                totalRead += read
            }
            return buffer
        }
    }
}

private fun AttachmentEncryptionMetadata.blobForVariant(
    variant: EncryptedBlobVariant,
): EncryptedBlobMetadata? {
    return when (variant) {
        EncryptedBlobVariant.MAIN -> main
        EncryptedBlobVariant.THUMBNAIL -> thumbnail
    }
}

private fun EncryptedBlobMetadata.chunkPlaintextSize(chunkIndex: Long): Int {
    if (plaintextSize <= 0L) {
        return 0
    }
    val chunkStart = chunkIndex * chunkSize.toLong()
    val remaining = plaintextSize - chunkStart
    return min(chunkSize.toLong(), remaining).toInt()
}

private fun EncryptedBlobMetadata.chunkCiphertextOffset(chunkIndex: Long): Long {
    return chunkIndex * (chunkSize.toLong() + tagSize.toLong())
}

private fun EncryptedBlobMetadata.chunkCiphertextSize(chunkIndex: Long): Int {
    return chunkPlaintextSize(chunkIndex) + tagSize
}

private class EncryptedHttpRangeDataSource(
    private val okHttpClient: OkHttpClient,
    private val sourceUrl: String,
    private val blob: EncryptedBlobMetadata,
    private val fileKey: ByteArray,
) : BaseDataSource(true) {
    private var uri: Uri? = null
    private var currentPosition = 0L
    private var bytesRemaining = 0L
    private var opened = false
    private var chunkIndex = -1L
    private var currentChunkStart = 0L
    private var currentChunkData = ByteArray(0)
    private val noncePrefix = Base64.getDecoder().decode(blob.noncePrefix)

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        uri = dataSpec.uri
        if (dataSpec.position < 0L || dataSpec.position > blob.plaintextSize) {
            throw IOException("Invalid encrypted media position")
        }
        currentPosition = dataSpec.position
        bytesRemaining = when {
            dataSpec.length == C.LENGTH_UNSET.toLong() -> blob.plaintextSize - dataSpec.position
            else -> min(dataSpec.length, blob.plaintextSize - dataSpec.position)
        }.coerceAtLeast(0L)
        currentChunkData = ByteArray(0)
        chunkIndex = -1L
        currentChunkStart = 0L
        opened = true
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) {
            return 0
        }
        if (bytesRemaining == 0L) {
            return C.RESULT_END_OF_INPUT
        }
        ensureChunkLoaded()
        val chunkOffset = (currentPosition - currentChunkStart).toInt()
        val readable = min(
            min(length.toLong(), bytesRemaining),
            (currentChunkData.size - chunkOffset).toLong(),
        ).toInt()
        if (readable <= 0) {
            return C.RESULT_END_OF_INPUT
        }
        System.arraycopy(currentChunkData, chunkOffset, buffer, offset, readable)
        currentPosition += readable.toLong()
        bytesRemaining -= readable.toLong()
        bytesTransferred(readable)
        return readable
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
        currentChunkData = ByteArray(0)
        chunkIndex = -1L
        currentChunkStart = 0L
        if (opened) {
            opened = false
            transferEnded()
        }
    }

    private fun ensureChunkLoaded() {
        val targetChunkIndex = currentPosition / blob.chunkSize.toLong()
        if (targetChunkIndex == chunkIndex && currentChunkData.isNotEmpty()) {
            return
        }
        val cipherStart = blob.chunkCiphertextOffset(targetChunkIndex)
        val cipherSize = blob.chunkCiphertextSize(targetChunkIndex)
        val cipherEnd = cipherStart + cipherSize - 1L
        val request = Request.Builder()
            .url(sourceUrl)
            .get()
            .header("Range", "bytes=$cipherStart-$cipherEnd")
            .build()
        val ciphertext = okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Encrypted media range request failed: HTTP ${response.code}")
            }
            val bytes = response.body.bytes()
            if (bytes.size != cipherSize) {
                throw IOException("Unexpected encrypted media chunk size")
            }
            bytes
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(fileKey, "AES"),
            GCMParameterSpec(128, ByteBuffer.allocate(12).put(noncePrefix).putInt(targetChunkIndex.toInt()).array()),
        )
        currentChunkData = cipher.doFinal(ciphertext)
        currentChunkStart = targetChunkIndex * blob.chunkSize.toLong()
        chunkIndex = targetChunkIndex
    }
}
