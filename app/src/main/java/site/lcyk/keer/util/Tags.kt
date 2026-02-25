package site.lcyk.keer.util

private val LEADING_HASH_REGEX = Regex("^#+")
private val PUNCTUATION_REGEX = Regex("\\p{P}")
private const val TAG_SEPARATOR = "/"

fun normalizeTagName(tag: String): String {
    val stripped = tag
        .trim()
        .replace(LEADING_HASH_REGEX, "")
        .trim()

    if (stripped.isEmpty()) {
        return ""
    }

    return stripped
        .split(TAG_SEPARATOR)
        .asSequence()
        .map { segment ->
            segment
                .trim()
                .replace(LEADING_HASH_REGEX, "")
                .trim()
        }
        .filter { it.isNotEmpty() }
        .joinToString(TAG_SEPARATOR)
}

private fun isValidTagSegment(segment: String): Boolean {
    val normalizedSegment = segment
        .trim()
        .replace(LEADING_HASH_REGEX, "")
        .trim()

    if (normalizedSegment.isEmpty()) {
        return false
    }
    return !PUNCTUATION_REGEX.containsMatchIn(normalizedSegment)
}

fun isValidTagName(tag: String): Boolean {
    val normalizedTag = normalizeTagName(tag)
    if (normalizedTag.isEmpty()) {
        return false
    }
    return normalizedTag
        .split(TAG_SEPARATOR)
        .all(::isValidTagSegment)
}

fun normalizeTagList(tags: List<String>): List<String> {
    return tags.asSequence()
        .map(::normalizeTagName)
        .filter(::isValidTagName)
        .distinct()
        .toList()
}
