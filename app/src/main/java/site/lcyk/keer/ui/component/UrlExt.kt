package site.lcyk.keer.ui.component

import android.net.Uri
import site.lcyk.keer.data.model.ResourceRepresentable

internal fun String.isHttpUrl(): Boolean {
    val normalized = trim()
    return normalized.startsWith("http://", ignoreCase = true) ||
        normalized.startsWith("https://", ignoreCase = true)
}

internal fun ResourceRepresentable.resolveUsableRemoteThumbnailUri(): String? {
    val thumbnail = thumbnailUri?.trim()?.ifEmpty { null } ?: return null
    if (!thumbnail.isHttpUrl()) {
        return null
    }
    val remoteMain = uri.trim()
    if (remoteMain.isHttpUrl() && thumbnail.isSameHttpResourceAs(remoteMain)) {
        return null
    }
    return thumbnail
}

private fun String.isSameHttpResourceAs(other: String): Boolean {
    val left = normalizeHttpResourceForCompare(this)
    val right = normalizeHttpResourceForCompare(other)
    return left != null && right != null && left == right
}

private fun normalizeHttpResourceForCompare(rawUrl: String): String? {
    val trimmed = rawUrl.trim()
    if (!trimmed.isHttpUrl()) {
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
