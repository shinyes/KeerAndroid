package site.lcyk.keer.data.repository

import android.net.Uri
import okhttp3.MediaType


internal fun MediaType?.isImageMimeType(): Boolean {
    return this?.type.equals("image", ignoreCase = true)
}

internal fun MediaType?.isVideoMimeType(): Boolean {
    return this?.type.equals("video", ignoreCase = true)
}

internal fun isHttpUrl(rawUrl: String): Boolean {
    return rawUrl.startsWith("http://", ignoreCase = true) ||
        rawUrl.startsWith("https://", ignoreCase = true)
}

internal fun isSameHttpResource(leftRawUrl: String, rightRawUrl: String): Boolean {
    val left = normalizeHttpResourceForCompare(leftRawUrl)
    val right = normalizeHttpResourceForCompare(rightRawUrl)
    return left != null && right != null && left == right
}

internal fun normalizeHttpResourceForCompare(rawUrl: String): String? {
    val trimmed = rawUrl.trim()
    if (!isHttpUrl(trimmed)) {
        return null
    }
    val parsed = runCatching { Uri.parse(trimmed) }.getOrNull() ?: return trimmed.lowercase()
    val scheme = parsed.scheme?.lowercase().orEmpty()
    val host = parsed.host?.lowercase().orEmpty()
    if (scheme.isEmpty() || host.isEmpty()) {
        return trimmed.lowercase()
    }
    val port = parsed.port.takeIf { it >= 0 }?.let { ":$it" }.orEmpty()
    val path = parsed.encodedPath
        ?.trim()
        ?.ifEmpty { "/" }
        ?.trimEnd('/')
        ?.ifEmpty { "/" }
        ?: "/"
    return "$scheme://$host$port$path"
}

internal fun renameTagWithPrefix(tag: String, oldPrefix: String, newPrefix: String): String {
    return when {
        tag == oldPrefix -> newPrefix
        tag.startsWith("$oldPrefix/") -> "$newPrefix/${tag.removePrefix("$oldPrefix/")}"
        else -> tag
    }
}

internal fun matchesTagOrDescendant(tag: String, rootTag: String): Boolean {
    return tag == rootTag || tag.startsWith("$rootTag/")
}

internal class ThumbnailUploadTaskScheduler {
    private val lock = Any()
    private val pending = mutableSetOf<String>()
    private val running = mutableSetOf<String>()

    fun enqueue(resourceIdentifier: String): Boolean = synchronized(lock) {
        pending += resourceIdentifier
        if (resourceIdentifier in running) {
            false
        } else {
            running += resourceIdentifier
            true
        }
    }

    fun takePending(resourceIdentifier: String): Boolean = synchronized(lock) {
        pending.remove(resourceIdentifier)
    }

    fun finishAndShouldRestart(resourceIdentifier: String): Boolean = synchronized(lock) {
        running -= resourceIdentifier
        if (resourceIdentifier in pending) {
            running += resourceIdentifier
            true
        } else {
            false
        }
    }
}
