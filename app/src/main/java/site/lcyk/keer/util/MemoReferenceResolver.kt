package site.lcyk.keer.util

import site.lcyk.keer.data.local.entity.MemoEntity
import site.lcyk.keer.data.model.CachedMemoItem
import site.lcyk.keer.data.model.MemoVisibility
import site.lcyk.keer.data.model.PendingGroupMemo
import site.lcyk.keer.data.model.Settings
import site.lcyk.keer.data.model.toMemo
import java.time.Instant

const val EXPLORE_MEMO_PREFIX = "explore:"
const val GROUP_MEMO_PREFIX = "group:"
private const val LOCAL_GROUP_MESSAGE_PREFIX = "local:"

fun resolveMemoByIdentifier(
    memoIdentifier: String,
    memos: List<MemoEntity>,
    settings: Settings
): MemoEntity? {
    memos.firstOrNull { memo -> memo.identifier == memoIdentifier }?.let { memo ->
        return memo
    }
    return resolveMemoFromSettingsByIdentifier(settings, memoIdentifier)
}

fun resolveMemoByRemoteId(
    remoteId: String,
    memos: List<MemoEntity>,
    settings: Settings
): MemoEntity? {
    val normalizedRemoteID = remoteId.trim()
    if (normalizedRemoteID.isEmpty()) {
        return null
    }
    memos.firstOrNull { memo -> memo.remoteId == normalizedRemoteID }?.let { memo ->
        return memo
    }

    val userSettings = settings.usersList
        .firstOrNull { user -> user.accountKey == settings.currentUser }
        ?.settings
        ?: return null
    val accountKey = settings.currentUser.ifBlank { "cached" }

    userSettings.cachedExploreMemos
        .firstOrNull { memo -> memo.remoteId == normalizedRemoteID }
        ?.let { cached ->
            return cached.toMemoEntity(
                identifier = "$EXPLORE_MEMO_PREFIX$normalizedRemoteID",
                accountKey = accountKey
            )
        }

    userSettings.cachedGroupMemos
        .firstOrNull { memo -> memo.remoteId == normalizedRemoteID && memo.groupId != null }
        ?.let { cached ->
            val groupID = cached.groupId ?: return@let
            val pinned = userSettings.pinnedGroupMemoKeys.contains(groupMemoKey(groupID, normalizedRemoteID))
            return cached.toMemoEntity(
                identifier = "$GROUP_MEMO_PREFIX$groupID:$normalizedRemoteID",
                accountKey = accountKey,
                pinnedOverride = pinned
            )
        }

    if (normalizedRemoteID.startsWith(LOCAL_GROUP_MESSAGE_PREFIX)) {
        val localID = normalizedRemoteID.removePrefix(LOCAL_GROUP_MESSAGE_PREFIX).trim()
        if (localID.isNotEmpty()) {
            userSettings.pendingGroupMemos
                .firstOrNull { pending -> pending.localId == localID }
                ?.let { pendingMemo ->
                    val pinned = userSettings.pinnedGroupMemoKeys.contains(
                        groupMemoKey(pendingMemo.groupId, normalizedRemoteID)
                    )
                    return pendingMemo.toMemoEntity(
                        identifier = "$GROUP_MEMO_PREFIX${pendingMemo.groupId}:$normalizedRemoteID",
                        remoteId = normalizedRemoteID,
                        accountKey = accountKey,
                        pinned = pinned
                    )
                }
        }
    }

    return null
}

fun resolveMemoFromQuoteDescriptor(
    descriptor: MemoQuoteDescriptor,
    memos: List<MemoEntity>,
    settings: Settings
): MemoEntity? {
    return when (descriptor.sourceKind) {
        MemoQuoteSourceKind.LOCAL -> resolveMemoByIdentifier(descriptor.source, memos, settings)
        MemoQuoteSourceKind.REMOTE -> resolveMemoByRemoteId(descriptor.source, memos, settings)
    }
}

private fun resolveMemoFromSettingsByIdentifier(
    settings: Settings,
    memoIdentifier: String
): MemoEntity? {
    val userSettings = settings.usersList
        .firstOrNull { user -> user.accountKey == settings.currentUser }
        ?.settings
        ?: return null
    val accountKey = settings.currentUser.ifBlank { "cached" }

    if (memoIdentifier.startsWith(EXPLORE_MEMO_PREFIX)) {
        val remoteId = memoIdentifier.removePrefix(EXPLORE_MEMO_PREFIX).trim()
        if (remoteId.isEmpty()) {
            return null
        }
        return userSettings.cachedExploreMemos
            .firstOrNull { memo -> memo.remoteId == remoteId }
            ?.toMemoEntity(
                identifier = memoIdentifier,
                accountKey = accountKey
            )
    }

    if (memoIdentifier.startsWith(GROUP_MEMO_PREFIX)) {
        val parsed = parseGroupMemoIdentifier(memoIdentifier) ?: return null
        val pinned = userSettings.pinnedGroupMemoKeys.contains(
            groupMemoKey(parsed.groupId, parsed.memoRemoteId)
        )

        if (parsed.memoRemoteId.startsWith(LOCAL_GROUP_MESSAGE_PREFIX)) {
            val localId = parsed.memoRemoteId.removePrefix(LOCAL_GROUP_MESSAGE_PREFIX).trim()
            if (localId.isNotEmpty()) {
                userSettings.pendingGroupMemos
                    .firstOrNull { memo ->
                        memo.groupId == parsed.groupId && memo.localId == localId
                    }
                    ?.let { pendingMemo ->
                        return pendingMemo.toMemoEntity(
                            identifier = memoIdentifier,
                            remoteId = parsed.memoRemoteId,
                            accountKey = accountKey,
                            pinned = pinned
                        )
                    }
            }
        }

        return userSettings.cachedGroupMemos
            .firstOrNull { memo ->
                memo.groupId == parsed.groupId && memo.remoteId == parsed.memoRemoteId
            }
            ?.toMemoEntity(
                identifier = memoIdentifier,
                accountKey = accountKey,
                pinnedOverride = pinned
            )
    }

    return null
}

private data class ParsedGroupMemoIdentifier(
    val groupId: String,
    val memoRemoteId: String
)

private fun parseGroupMemoIdentifier(memoIdentifier: String): ParsedGroupMemoIdentifier? {
    if (!memoIdentifier.startsWith(GROUP_MEMO_PREFIX)) {
        return null
    }
    val payload = memoIdentifier.removePrefix(GROUP_MEMO_PREFIX)
    val parts = payload.split(":", limit = 2)
    if (parts.size != 2) {
        return null
    }
    val groupId = parts[0].trim()
    val memoRemoteId = parts[1].trim()
    if (groupId.isEmpty() || memoRemoteId.isEmpty()) {
        return null
    }
    return ParsedGroupMemoIdentifier(
        groupId = groupId,
        memoRemoteId = memoRemoteId
    )
}

private fun groupMemoKey(groupId: String, memoRemoteId: String): String {
    return "$groupId|$memoRemoteId"
}

private fun CachedMemoItem.toMemoEntity(
    identifier: String,
    accountKey: String,
    pinnedOverride: Boolean? = null
): MemoEntity {
    val resolvedMemo = toMemo().let { memo ->
        if (pinnedOverride == null) memo else memo.copy(pinned = pinnedOverride)
    }
    val syncedAt = resolvedMemo.updatedAt ?: resolvedMemo.date
    return resolvedMemo.toMemoEntityForCard(
        identifier = identifier,
        accountKey = accountKey,
        needsSync = false,
        lastModified = syncedAt,
        lastSyncedAt = syncedAt
    )
}

private fun PendingGroupMemo.toMemoEntity(
    identifier: String,
    remoteId: String,
    accountKey: String,
    pinned: Boolean
): MemoEntity {
    val createdAt = Instant.ofEpochMilli(createdAtEpochMillis)
    val entity = MemoEntity(
        identifier = identifier,
        remoteId = remoteId,
        accountKey = accountKey,
        content = content,
        date = createdAt,
        visibility = MemoVisibility.PROTECTED,
        pinned = pinned,
        archived = false,
        needsSync = true,
        isDeleted = false,
        lastModified = createdAt,
        lastSyncedAt = null
    )
    entity.tags = normalizeTagList(tags)
    return entity
}
