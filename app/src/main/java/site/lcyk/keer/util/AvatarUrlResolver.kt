package site.lcyk.keer.util

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

fun resolveAvatarUrl(host: String, avatarUrl: String): String? {
    if (avatarUrl.isBlank()) {
        return null
    }
    if (avatarUrl.toHttpUrlOrNull() != null || "://" in avatarUrl) {
        return avatarUrl
    }
    val baseUrl = host.toHttpUrlOrNull() ?: return avatarUrl
    return runCatching {
        baseUrl.toUrl().toURI().resolve(avatarUrl).toString()
    }.getOrDefault(avatarUrl)
}
