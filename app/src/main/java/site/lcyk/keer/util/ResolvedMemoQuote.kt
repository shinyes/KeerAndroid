package site.lcyk.keer.util

import site.lcyk.keer.data.local.entity.MemoEntity
import site.lcyk.keer.data.model.MemoQuotePreview

data class ResolvedMemoQuote(
    val sourceMemo: MemoEntity?,
    val preview: MemoQuotePreview?,
)

fun buildResolvedMemoQuoteMap(
    memos: List<MemoEntity>,
    transientMemoLookup: (String) -> MemoEntity? = { null },
): Map<String, ResolvedMemoQuote> {
    if (memos.isEmpty()) {
        return emptyMap()
    }

    val byLookupKey = linkedMapOf<String, MemoEntity>()
    memos.forEach { memo ->
        memoQuoteLookupKeys(memo).forEach { key ->
            byLookupKey.putIfAbsent(key, memo)
        }
    }

    return buildMap {
        memos.forEach { memo ->
            val descriptor = memo.resolveMemoQuoteDescriptor() ?: return@forEach
            val source = descriptor.source.trim()
            if (source.isEmpty()) {
                return@forEach
            }
            val quotedMemo = transientMemoLookup(source)
                ?: byLookupKey[source]
            val preview = quotedMemo?.toMemoQuotePreview() ?: memo.storedMemoQuotePreviewOrNull()
            put(
                memo.identifier,
                ResolvedMemoQuote(
                    sourceMemo = quotedMemo,
                    preview = preview,
                )
            )
        }
    }
}

private fun memoQuoteLookupKeys(memo: MemoEntity): Sequence<String> = sequence {
    val identifier = memo.identifier.trim()
    if (identifier.isNotEmpty()) {
        yield(identifier)
        extractRemoteIdFromMemoIdentifier(identifier)?.let { remoteId ->
            yield(remoteId)
        }
    }
    memo.remoteId
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.let { remoteId ->
            yield(remoteId)
        }
}

private fun extractRemoteIdFromMemoIdentifier(identifier: String): String? {
    if (identifier.startsWith(EXPLORE_MEMO_PREFIX)) {
        return identifier.removePrefix(EXPLORE_MEMO_PREFIX).trim().ifEmpty { null }
    }
    if (!identifier.startsWith(GROUP_MEMO_PREFIX)) {
        return null
    }
    val payload = identifier.removePrefix(GROUP_MEMO_PREFIX)
    val separatorIndex = payload.indexOf(':')
    if (separatorIndex < 0 || separatorIndex == payload.lastIndex) {
        return null
    }
    return payload.substring(separatorIndex + 1).trim().ifEmpty { null }
}
