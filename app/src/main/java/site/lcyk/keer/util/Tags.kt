package site.lcyk.keer.util

private val LEADING_HASH_REGEX = Regex("^#+")
private val PUNCTUATION_REGEX = Regex("\\p{P}")

fun normalizeTagName(tag: String): String {
    return tag
        .trim()
        .replace(LEADING_HASH_REGEX, "")
        .trim()
}

fun isValidTagName(tag: String): Boolean {
    val normalizedTag = normalizeTagName(tag)
    if (normalizedTag.isEmpty()) {
        return false
    }
    return !PUNCTUATION_REGEX.containsMatchIn(normalizedTag)
}

fun normalizeTagList(tags: List<String>): List<String> {
    return tags.asSequence()
        .map(::normalizeTagName)
        .filter(::isValidTagName)
        .distinct()
        .toList()
}
