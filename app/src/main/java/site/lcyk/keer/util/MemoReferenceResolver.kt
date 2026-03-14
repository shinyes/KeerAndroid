package site.lcyk.keer.util

import site.lcyk.keer.data.local.entity.MemoEntity

const val EXPLORE_MEMO_PREFIX = "explore:"
const val GROUP_MEMO_PREFIX = "group:"

fun resolveMemoByIdentifier(
    memoIdentifier: String,
    memos: List<MemoEntity>,
): MemoEntity? {
    val normalizedIdentifier = memoIdentifier.trim()
    if (normalizedIdentifier.isEmpty()) {
        return null
    }

    memos.firstOrNull { memo -> memo.identifier == normalizedIdentifier }?.let { memo ->
        return memo
    }

    val remoteId = extractRemoteIdFromIdentifier(normalizedIdentifier) ?: return null
    return resolveMemoByRemoteId(
        remoteId = remoteId,
        memos = memos,
    )
}

fun resolveMemoByRemoteId(
    remoteId: String,
    memos: List<MemoEntity>,
): MemoEntity? {
    val normalizedRemoteId = remoteId.trim()
    if (normalizedRemoteId.isEmpty()) {
        return null
    }

    memos.firstOrNull { memo -> memo.remoteId == normalizedRemoteId }?.let { memo ->
        return memo
    }

    return memos.firstOrNull { memo ->
        val identifier = memo.identifier.trim()
        identifier == "$EXPLORE_MEMO_PREFIX$normalizedRemoteId" ||
                identifier == normalizedRemoteId ||
                identifier.endsWith(":$normalizedRemoteId")
    }
}

fun resolveMemoFromQuoteDescriptor(
    descriptor: MemoQuoteDescriptor,
    memos: List<MemoEntity>,
): MemoEntity? {
    return when (descriptor.sourceKind) {
        MemoQuoteSourceKind.LOCAL -> resolveMemoByIdentifier(descriptor.source, memos)
        MemoQuoteSourceKind.REMOTE -> resolveMemoByRemoteId(descriptor.source, memos)
    }
}

private fun extractRemoteIdFromIdentifier(identifier: String): String? {
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
