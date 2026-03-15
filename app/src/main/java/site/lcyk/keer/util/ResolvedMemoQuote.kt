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

    val byIdentifier = memos.associateBy { memo -> memo.identifier }
    val byRemoteId = memos
        .asSequence()
        .mapNotNull { memo ->
            memo.remoteId
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let { remoteId -> remoteId to memo }
        }
        .toMap()

    return buildMap {
        memos.forEach { memo ->
            val descriptor = memo.resolveMemoQuoteDescriptor() ?: return@forEach
            val quotedMemo = transientMemoLookup(descriptor.source)
                ?: when (descriptor.sourceKind) {
                    MemoQuoteSourceKind.LOCAL -> byIdentifier[descriptor.source]
                    MemoQuoteSourceKind.REMOTE -> byRemoteId[descriptor.source]
                }
                ?: resolveMemoFromQuoteDescriptor(descriptor, memos)
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
