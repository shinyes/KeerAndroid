package site.lcyk.keer.util

import site.lcyk.keer.data.local.entity.MemoEntity
import site.lcyk.keer.data.model.MemoQuotePreview

internal class ResolvedMemoQuoteProjector(
    private val transientMemoLookup: (String) -> MemoEntity? = { null },
) {
    private data class CacheEntry(
        val targetMemo: MemoEntity,
        val sourceMemo: MemoEntity?,
        val preview: MemoQuotePreview?,
        val resolvedQuote: ResolvedMemoQuote,
    )

    private var previousEntries: Map<String, CacheEntry> = emptyMap()
    private var previousMap: Map<String, ResolvedMemoQuote> = emptyMap()

    fun project(memos: List<MemoEntity>): Map<String, ResolvedMemoQuote> {
        if (memos.isEmpty()) {
            previousEntries = emptyMap()
            previousMap = emptyMap()
            return emptyMap()
        }

        val byIdentifier = HashMap<String, MemoEntity>(memos.size * 2)
        val byRemoteId = HashMap<String, MemoEntity>(memos.size * 2)
        memos.forEach { memo ->
            byIdentifier[memo.identifier] = memo
            memo.remoteId
                ?.trim()
                ?.takeIf(String::isNotEmpty)
                ?.let { remoteId -> byRemoteId[remoteId] = memo }
        }

        val nextEntries = LinkedHashMap<String, CacheEntry>()
        val nextMap = LinkedHashMap<String, ResolvedMemoQuote>()
        memos.forEach { memo ->
            val descriptor = memo.resolveMemoQuoteDescriptor() ?: return@forEach
            val sourceMemo = transientMemoLookup(descriptor.source)
                ?: resolveMemoFromQuoteDescriptor(
                    descriptor = descriptor,
                    byIdentifier = byIdentifier,
                    byRemoteId = byRemoteId,
                )
            val preview = sourceMemo?.toMemoQuotePreview() ?: memo.storedMemoQuotePreviewOrNull()
            val cached = previousEntries[memo.identifier]
            val resolvedQuote = if (
                cached != null &&
                cached.targetMemo === memo &&
                cached.sourceMemo === sourceMemo &&
                cached.preview == preview
            ) {
                cached.resolvedQuote
            } else {
                ResolvedMemoQuote(
                    sourceMemo = sourceMemo,
                    preview = preview,
                )
            }
            nextEntries[memo.identifier] = CacheEntry(
                targetMemo = memo,
                sourceMemo = sourceMemo,
                preview = preview,
                resolvedQuote = resolvedQuote,
            )
            nextMap[memo.identifier] = resolvedQuote
        }

        previousEntries = nextEntries
        return reuseResolvedQuoteMap(previousMap, nextMap).also { resolvedMap ->
            previousMap = resolvedMap
        }
    }
}

private fun reuseResolvedQuoteMap(
    previous: Map<String, ResolvedMemoQuote>,
    next: LinkedHashMap<String, ResolvedMemoQuote>,
): Map<String, ResolvedMemoQuote> {
    if (previous.size == next.size) {
        val previousIterator = previous.entries.iterator()
        val nextIterator = next.entries.iterator()
        var identical = true
        while (previousIterator.hasNext() && nextIterator.hasNext()) {
            val previousEntry = previousIterator.next()
            val nextEntry = nextIterator.next()
            if (previousEntry.key != nextEntry.key || previousEntry.value !== nextEntry.value) {
                identical = false
                break
            }
        }
        if (identical) {
            return previous
        }
    }
    return next
}
