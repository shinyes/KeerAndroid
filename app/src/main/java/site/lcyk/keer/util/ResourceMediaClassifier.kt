package site.lcyk.keer.util

import site.lcyk.keer.data.model.ResourceRepresentable
import site.lcyk.keer.data.security.AttachmentEncryptionManager
import java.util.LinkedHashMap

data class PartitionedResources<T>(
    val mediaResources: List<T>,
    val otherResources: List<T>,
)

fun ResourceRepresentable.isImageMediaResource(): Boolean {
    return classifyResourceMediaKind(this) == ResourceMediaKind.IMAGE
}

fun ResourceRepresentable.isVideoMediaResource(): Boolean {
    return classifyResourceMediaKind(this) == ResourceMediaKind.VIDEO
}

fun ResourceRepresentable.isMediaDisplayResource(): Boolean {
    return classifyResourceMediaKind(this) != ResourceMediaKind.OTHER
}

fun <T : ResourceRepresentable> partitionResourcesForDisplay(
    resources: List<T>,
): PartitionedResources<T> {
    if (resources.isEmpty()) {
        return PartitionedResources(
            mediaResources = emptyList(),
            otherResources = emptyList(),
        )
    }
    val media = ArrayList<T>(resources.size)
    val other = ArrayList<T>()
    resources.forEach { resource ->
        if (resource.isMediaDisplayResource()) {
            media += resource
        } else {
            other += resource
        }
    }
    return PartitionedResources(
        mediaResources = media,
        otherResources = other,
    )
}

internal fun clearResourceMediaClassificationCacheForTest() {
    synchronized(resourceMediaKindCacheLock) {
        resourceMediaKindCache.clear()
    }
}

internal fun resourceMediaClassificationCacheSizeForTest(): Int {
    return synchronized(resourceMediaKindCacheLock) {
        resourceMediaKindCache.size
    }
}

private fun classifyResourceMediaKind(resource: ResourceRepresentable): ResourceMediaKind {
    val cacheKey = resourceMediaCacheKey(resource)
    synchronized(resourceMediaKindCacheLock) {
        resourceMediaKindCache[cacheKey]?.let { cached ->
            return cached
        }
    }

    val normalizedMimeType = AttachmentEncryptionManager.resolveOriginalMimeType(
        resource.encryptionMetadata,
        resource.mimeType
    )?.lowercase().orEmpty()

    val resolved = when {
        normalizedMimeType.startsWith("image/") -> ResourceMediaKind.IMAGE
        normalizedMimeType.startsWith("video/") -> ResourceMediaKind.VIDEO
        else -> {
            val extension = resource.filename.substringAfterLast('.', "").lowercase()
            when {
                extension in imageExtensions -> ResourceMediaKind.IMAGE
                extension in videoExtensions -> ResourceMediaKind.VIDEO
                else -> ResourceMediaKind.OTHER
            }
        }
    }

    synchronized(resourceMediaKindCacheLock) {
        resourceMediaKindCache[cacheKey] = resolved
    }
    return resolved
}

private fun resourceMediaCacheKey(resource: ResourceRepresentable): String {
    val stableIdentifier = resource.remoteId?.trim()?.ifEmpty { resource.uri.trim() } ?: resource.uri.trim()
    val normalizedFilename = resource.filename.trim().lowercase()
    val normalizedMimeType = resource.mimeType?.trim()?.lowercase().orEmpty()
    val normalizedMetadata = resource.encryptionMetadata?.trim().orEmpty()
    return "$stableIdentifier|$normalizedFilename|$normalizedMimeType|$normalizedMetadata"
}

private enum class ResourceMediaKind {
    IMAGE,
    VIDEO,
    OTHER,
}

private val imageExtensions = setOf(
    "png", "jpg", "jpeg", "gif", "webp", "bmp", "heic", "heif", "avif"
)
private val videoExtensions = setOf(
    "mp4", "mov", "m4v", "webm", "mkv", "avi", "3gp", "mpeg", "mpg"
)

private val resourceMediaKindCacheLock = Any()
private val resourceMediaKindCache = object : LinkedHashMap<String, ResourceMediaKind>(
    MEDIA_CLASSIFICATION_CACHE_LIMIT,
    0.75f,
    true
) {
    override fun removeEldestEntry(
        eldest: MutableMap.MutableEntry<String, ResourceMediaKind>?
    ): Boolean {
        return size > MEDIA_CLASSIFICATION_CACHE_LIMIT
    }
}

private const val MEDIA_CLASSIFICATION_CACHE_LIMIT = 2_048
