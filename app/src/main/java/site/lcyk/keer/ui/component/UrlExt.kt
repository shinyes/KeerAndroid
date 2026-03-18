package site.lcyk.keer.ui.component

internal fun String.isHttpUrl(): Boolean {
    val normalized = trim()
    return normalized.startsWith("http://", ignoreCase = true) ||
        normalized.startsWith("https://", ignoreCase = true)
}
