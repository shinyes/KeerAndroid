package site.lcyk.keer.util

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import java.util.UUID
import timber.log.Timber

data class UploadMediaMetadata(
    val filename: String,
    val mimeType: String?,
    val sizeBytes: Long,
)

object UploadMediaMetadataResolver {
    private const val SNIFF_SAMPLE_BYTES = 96

    private val genericMimeTypes = setOf(
        "*/*",
        "application/octet-stream",
        "application/unknown",
        "binary/octet-stream",
        "image/*",
        "video/*",
    )

    private val mimeTypeExtensions = mapOf(
        "image/avif" to listOf("avif"),
        "image/gif" to listOf("gif"),
        "image/heic" to listOf("heic"),
        "image/heif" to listOf("heif", "heic"),
        "image/jpeg" to listOf("jpg", "jpeg"),
        "image/png" to listOf("png"),
        "image/webp" to listOf("webp"),
        "video/3gpp" to listOf("3gp", "3gpp"),
        "video/mp4" to listOf("mp4", "m4v"),
        "video/quicktime" to listOf("mov", "qt"),
    )

    private val extensionMimeTypes = mimeTypeExtensions
        .flatMap { (mimeType, extensions) ->
            extensions.map { extension -> extension to mimeType }
        }
        .toMap()

    fun resolve(
        contentResolver: ContentResolver,
        uri: Uri,
        fallbackBaseName: String = "attachment_${UUID.randomUUID()}",
    ): UploadMediaMetadata {
        val displayName = queryDisplayName(contentResolver, uri)
            ?: sanitizeFilename(uri.lastPathSegment)
        val resolverMimeType = normalizeMimeType(contentResolver.getType(uri))
        val sniffedMimeType = sniffMimeType(readSample(contentResolver, uri))
        val extensionMimeType = extensionFromFilename(displayName)?.let(::mimeTypeForExtension)
        val mimeType = sniffedMimeType ?: resolverMimeType ?: extensionMimeType
        val filename = resolveFilename(
            displayName = displayName,
            mimeType = mimeType,
            fallbackBaseName = fallbackBaseName,
        )
        return UploadMediaMetadata(
            filename = filename,
            mimeType = mimeType,
            sizeBytes = queryFileSize(contentResolver, uri),
        )
    }

    internal fun resolveFilename(
        displayName: String?,
        mimeType: String?,
        fallbackBaseName: String,
    ): String {
        val sanitizedDisplayName = sanitizeFilename(displayName)
        val normalizedMimeType = normalizeMimeType(mimeType)
        val preferredExtensions = normalizedMimeType
            ?.let { candidateMimeType -> mimeTypeExtensions[candidateMimeType] }
            .orEmpty()
        if (sanitizedDisplayName.isNullOrBlank()) {
            val fallbackExtension = preferredExtensions.firstOrNull()
            return if (fallbackExtension == null) {
                fallbackBaseName
            } else {
                "$fallbackBaseName.$fallbackExtension"
            }
        }

        if (preferredExtensions.isEmpty()) {
            return sanitizedDisplayName
        }

        val currentExtension = extensionFromFilename(sanitizedDisplayName)
        if (currentExtension != null && currentExtension in preferredExtensions) {
            return sanitizedDisplayName
        }

        val baseName = sanitizedDisplayName
            .substringBeforeLast('.', sanitizedDisplayName)
            .trim()
            .trimEnd('.')
            .ifEmpty { fallbackBaseName }
        return "$baseName.${preferredExtensions.first()}"
    }

    internal fun sniffMimeType(sample: ByteArray?): String? {
        if (sample == null || sample.isEmpty()) {
            return null
        }
        if (sample.startsWith(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()))) {
            return "image/jpeg"
        }
        if (
            sample.size >= 8 &&
            sample[0] == 0x89.toByte() &&
            sample[1] == 0x50.toByte() &&
            sample[2] == 0x4E.toByte() &&
            sample[3] == 0x47.toByte() &&
            sample[4] == 0x0D.toByte() &&
            sample[5] == 0x0A.toByte() &&
            sample[6] == 0x1A.toByte() &&
            sample[7] == 0x0A.toByte()
        ) {
            return "image/png"
        }
        if (sample.startsWith("GIF87a".encodeToByteArray()) || sample.startsWith("GIF89a".encodeToByteArray())) {
            return "image/gif"
        }
        if (
            sample.size >= 12 &&
            sample.copyOfRange(0, 4).decodeToString() == "RIFF" &&
            sample.copyOfRange(8, 12).decodeToString() == "WEBP"
        ) {
            return "image/webp"
        }
        if (sample.size >= 12 && sample.copyOfRange(4, 8).decodeToString() == "ftyp") {
            val brands = mutableListOf<String>()
            var offset = 8
            while (offset + 4 <= sample.size) {
                brands += sample.copyOfRange(offset, offset + 4).decodeToString()
                offset += 4
            }
            if (brands.any { brand -> brand in setOf("avif", "avis") }) {
                return "image/avif"
            }
            if (brands.any { brand -> brand in setOf("heic", "heix", "hevc", "hevx") }) {
                return "image/heic"
            }
            if (brands.any { brand -> brand in setOf("heif", "heis", "heim", "hevm", "mif1", "msf1") }) {
                return "image/heif"
            }
            if (brands.any { brand -> brand == "qt  " }) {
                return "video/quicktime"
            }
            if (brands.any { brand -> brand.startsWith("3gp") || brand.startsWith("3g2") }) {
                return "video/3gpp"
            }
            if (brands.any { brand ->
                    brand in setOf("isom", "iso2", "mp41", "mp42", "avc1", "dash", "M4V ")
                        || brand.startsWith("mp4")
                        || brand.startsWith("iso")
                }) {
                return "video/mp4"
            }
        }
        return null
    }

    internal fun mimeTypeForExtension(extension: String): String? {
        return extensionMimeTypes[extension.trim().lowercase()]
    }

    internal fun extensionFromFilename(displayName: String?): String? {
        val name = sanitizeFilename(displayName)
            ?.takeIf { candidate -> candidate.contains('.') }
            ?: return null
        return name.substringAfterLast('.', "").trim().lowercase().takeIf { extension -> extension.isNotEmpty() }
    }

    private fun normalizeMimeType(mimeType: String?): String? {
        val normalized = mimeType?.trim()?.lowercase()?.takeIf { candidate -> candidate.isNotEmpty() } ?: return null
        return normalized.takeUnless { candidate -> candidate in genericMimeTypes }
    }

    private fun sanitizeFilename(displayName: String?): String? {
        val sanitized = displayName
            ?.substringAfterLast('/')
            ?.substringAfterLast('\\')
            ?.trim()
            ?.takeIf { candidate -> candidate.isNotEmpty() }
        return sanitized
    }

    private fun readSample(
        contentResolver: ContentResolver,
        uri: Uri,
    ): ByteArray? {
        return try {
            contentResolver.openInputStream(uri)?.use { input ->
                val buffer = ByteArray(SNIFF_SAMPLE_BYTES)
                val read = input.read(buffer)
                if (read <= 0) {
                    null
                } else {
                    buffer.copyOf(read)
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to sniff mime type for URI: %s", uri)
            null
        }
    }

    private fun queryDisplayName(contentResolver: ContentResolver, uri: Uri): String? {
        return try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
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

    private fun queryFileSize(contentResolver: ContentResolver, uri: Uri): Long {
        return try {
            contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
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

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
        if (size < prefix.size) {
            return false
        }
        return prefix.indices.all { index -> this[index] == prefix[index] }
    }
}
