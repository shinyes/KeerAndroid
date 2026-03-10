package site.lcyk.keer.util

import site.lcyk.keer.data.local.entity.MemoEntity

private const val QUOTE_TAG_ROOT = "quote/src"
private const val QUOTE_TAG_PREFIX = "$QUOTE_TAG_ROOT/"

enum class MemoQuoteSourceKind(val tagSegment: String) {
    LOCAL("local"),
    REMOTE("remote");

    companion object {
        fun fromTagSegment(segment: String): MemoQuoteSourceKind? {
            return entries.firstOrNull { kind -> kind.tagSegment == segment }
        }
    }
}

data class MemoQuoteDescriptor(
    val sourceKind: MemoQuoteSourceKind,
    val source: String
)

fun buildMemoQuoteDescriptor(memo: MemoEntity): MemoQuoteDescriptor {
    val remoteId = memo.remoteId?.trim().orEmpty()
    if (remoteId.isNotEmpty() && !remoteId.startsWith("local:")) {
        return MemoQuoteDescriptor(
            sourceKind = MemoQuoteSourceKind.REMOTE,
            source = remoteId
        )
    }
    return MemoQuoteDescriptor(
        sourceKind = MemoQuoteSourceKind.LOCAL,
        source = memo.identifier
    )
}

fun buildMemoQuoteTag(descriptor: MemoQuoteDescriptor): String {
    val normalizedSource = descriptor.source.trim()
    if (normalizedSource.isEmpty()) {
        return ""
    }
    val encodedSource = encodeHex(normalizedSource)
    return "$QUOTE_TAG_PREFIX${descriptor.sourceKind.tagSegment}/$encodedSource"
}

fun parseMemoQuoteDescriptor(tags: List<String>): MemoQuoteDescriptor? {
    val quoteTag = tags
        .asSequence()
        .map(::normalizeTagName)
        .firstOrNull(::isQuoteTag)
        ?: return null
    val parts = quoteTag.split("/")
    if (parts.size != 4) {
        return null
    }
    val sourceKind = MemoQuoteSourceKind.fromTagSegment(parts[2]) ?: return null
    val source = decodeHex(parts[3])?.trim().orEmpty()
    if (source.isEmpty()) {
        return null
    }
    return MemoQuoteDescriptor(
        sourceKind = sourceKind,
        source = source
    )
}

fun isQuoteTag(tag: String): Boolean {
    return normalizeTagName(tag).startsWith(QUOTE_TAG_PREFIX)
}

fun stripQuoteTags(tags: List<String>): List<String> {
    return tags.asSequence()
        .map(::normalizeTagName)
        .filter { tag -> tag.isNotEmpty() }
        .filterNot(::isQuoteTag)
        .distinct()
        .toList()
}

fun mergeTagsWithCollaboratorsAndQuote(
    tags: List<String>,
    collaboratorIds: List<String>,
    quoteDescriptor: MemoQuoteDescriptor?
): List<String> {
    val merged = mergeTagsWithCollaborators(tags, collaboratorIds)
    val withoutQuote = merged.filterNot(::isQuoteTag)
    val quoteTag = quoteDescriptor?.let(::buildMemoQuoteTag).orEmpty()
    if (quoteTag.isEmpty()) {
        return withoutQuote
    }
    return (withoutQuote + quoteTag).distinct()
}

private fun encodeHex(value: String): String {
    val bytes = value.toByteArray(Charsets.UTF_8)
    val out = StringBuilder(bytes.size * 2)
    for (b in bytes) {
        val hex = b.toInt() and 0xFF
        out.append(hexChars[hex ushr 4])
        out.append(hexChars[hex and 0x0F])
    }
    return out.toString()
}

private fun decodeHex(value: String): String? {
    if (value.isEmpty() || value.length % 2 != 0) {
        return null
    }
    val out = ByteArray(value.length / 2)
    var index = 0
    while (index < value.length) {
        val high = value[index].digitToIntOrNull(16) ?: return null
        val low = value[index + 1].digitToIntOrNull(16) ?: return null
        out[index / 2] = ((high shl 4) or low).toByte()
        index += 2
    }
    return runCatching {
        out.toString(Charsets.UTF_8)
    }.getOrNull()
}

private val hexChars = "0123456789abcdef".toCharArray()
