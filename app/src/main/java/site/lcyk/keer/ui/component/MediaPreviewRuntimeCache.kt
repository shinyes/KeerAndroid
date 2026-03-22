package site.lcyk.keer.ui.component

import android.graphics.Bitmap
import android.util.LruCache
import site.lcyk.keer.data.model.ResourceRepresentable
import java.io.File

internal object MediaPreviewRuntimeCache {
    private val maxHeapBytes = Runtime.getRuntime().maxMemory().coerceAtLeast(1L)
    private val bitmapCacheMaxBytes = minOf(
        128L * 1024L * 1024L,
        (maxHeapBytes * 14L) / 100L,
    ).coerceAtLeast(12L * 1024L * 1024L).toInt()
    private val decryptedBytesCacheMaxBytes = minOf(
        40L * 1024L * 1024L,
        (maxHeapBytes * 5L) / 100L,
    ).coerceAtLeast(4L * 1024L * 1024L).toInt()

    private val bitmapCache = object : LruCache<String, Bitmap>(bitmapCacheMaxBytes) {
        override fun sizeOf(key: String, value: Bitmap): Int {
            return value.allocationByteCount.coerceAtLeast(1)
        }
    }
    private val decryptedBytesCache = object : LruCache<String, ByteArray>(decryptedBytesCacheMaxBytes) {
        override fun sizeOf(key: String, value: ByteArray): Int {
            return value.size.coerceAtLeast(1)
        }
    }
    private val previewUriIndex = object : LruCache<String, String>(PREVIEW_URI_INDEX_LIMIT) {
        override fun sizeOf(key: String, value: String): Int = 1
    }

    fun getBitmap(key: String): Bitmap? = bitmapCache.get(key)

    fun putBitmap(key: String, bitmap: Bitmap) {
        bitmapCache.put(key, bitmap)
    }

    fun getDecryptedBytes(key: String): ByteArray? = decryptedBytesCache.get(key)

    fun putDecryptedBytes(key: String, bytes: ByteArray) {
        decryptedBytesCache.put(key, bytes)
    }

    fun rememberPreviewUri(key: String, uri: String) {
        if (uri.isBlank()) {
            return
        }
        previewUriIndex.put(key, uri)
    }

    fun resolvePreviewUri(key: String): String? {
        val cached = previewUriIndex.get(key)?.trim().orEmpty()
        if (cached.isEmpty()) {
            return null
        }
        val file = resolveFileFromUriString(cached)
        if (file != null && !file.exists()) {
            previewUriIndex.remove(key)
            return null
        }
        return cached
    }

    private fun resolveFileFromUriString(uri: String): File? {
        if (!uri.startsWith("file:", ignoreCase = true)) {
            return null
        }
        return runCatching { File(android.net.Uri.parse(uri).path.orEmpty()) }
            .getOrNull()
            ?.takeIf { it.path.isNotBlank() }
    }

    private const val PREVIEW_URI_INDEX_LIMIT = 6_000
}

internal fun previewCacheKey(resource: ResourceRepresentable): String {
    val stable = when (resource) {
        is site.lcyk.keer.data.local.entity.ResourceEntity -> resource.identifier
        else -> resource.remoteId?.trim()?.takeIf(String::isNotEmpty) ?: resource.uri.trim()
    }
    return "preview:$stable"
}
