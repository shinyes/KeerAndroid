package site.lcyk.keer.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.media3.common.util.UnstableApi
import coil3.ImageLoader
import site.lcyk.keer.data.local.entity.ResourceEntity
import site.lcyk.keer.data.model.ResourceRepresentable
import site.lcyk.keer.data.security.AttachmentEncryptionManager
import java.util.LinkedHashMap

private val imageExtensions = setOf(
    "png", "jpg", "jpeg", "gif", "webp", "bmp", "heic", "heif", "avif"
)
private val videoExtensions = setOf(
    "mp4", "mov", "m4v", "webm", "mkv", "avi", "3gp", "mpeg", "mpg"
)

fun ResourceRepresentable.isImageResource(): Boolean {
    return classifyResourceMediaKind(this) == ResourceMediaKind.IMAGE
}

fun ResourceRepresentable.isVideoResource(): Boolean {
    return classifyResourceMediaKind(this) == ResourceMediaKind.VIDEO
}

fun ResourceRepresentable.isMediaResource(): Boolean {
    return classifyResourceMediaKind(this) != ResourceMediaKind.OTHER
}

@Composable
@UnstableApi
fun MemoMedia(
    resource: ResourceRepresentable,
    modifier: Modifier = Modifier,
    autoPreviewPrefetch: Boolean = true,
    mediaImageLoader: ImageLoader? = null,
) {
    if (resource.isVideoResource()) {
        MemoVideo(
            resource = resource,
            modifier = modifier,
            autoPreviewPrefetch = autoPreviewPrefetch,
            mediaImageLoader = mediaImageLoader,
        )
    } else {
        MemoImage(
            resource = resource,
            modifier = modifier,
            autoPreviewPrefetch = autoPreviewPrefetch,
            mediaImageLoader = mediaImageLoader,
        )
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
    val stableIdentifier = when (resource) {
        is ResourceEntity -> resource.identifier
        else -> resource.remoteId?.trim()?.ifEmpty { resource.uri.trim() } ?: resource.uri.trim()
    }
    val normalizedFilename = resource.filename.trim().lowercase()
    val normalizedMimeType = resource.mimeType?.trim()?.lowercase().orEmpty()
    val normalizedMetadata = resource.encryptionMetadata?.trim().orEmpty()
    return "$stableIdentifier|$normalizedFilename|$normalizedMimeType|$normalizedMetadata"
}

internal fun clearMediaTypeClassificationCacheForTest() {
    synchronized(resourceMediaKindCacheLock) {
        resourceMediaKindCache.clear()
    }
}

internal fun mediaTypeClassificationCacheSizeForTest(): Int {
    return synchronized(resourceMediaKindCacheLock) {
        resourceMediaKindCache.size
    }
}

private enum class ResourceMediaKind {
    IMAGE,
    VIDEO,
    OTHER,
}

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
