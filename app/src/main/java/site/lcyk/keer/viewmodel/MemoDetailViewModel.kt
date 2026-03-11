package site.lcyk.keer.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.skydoves.sandwich.getOrNull
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.withContext
import site.lcyk.keer.data.local.entity.MemoEntity
import site.lcyk.keer.data.model.CachedMemoItem
import site.lcyk.keer.data.model.MemoVisibility
import site.lcyk.keer.data.model.PendingGroupMemo
import site.lcyk.keer.data.model.toMemo
import site.lcyk.keer.data.service.MemoService
import site.lcyk.keer.data.service.OfflineGroupStore
import site.lcyk.keer.util.normalizeTagList
import site.lcyk.keer.util.toMemoEntityForCard

@HiltViewModel
class MemoDetailViewModel @Inject constructor(
    private val memoService: MemoService,
    private val offlineGroupStore: OfflineGroupStore,
) : ViewModel() {

    suspend fun resolveFallbackMemoEntity(
        accountKey: String,
        memoIdentifier: String,
    ): MemoEntity? = withContext(viewModelScope.coroutineContext) {
        val normalizedAccountKey = accountKey.trim()
        if (normalizedAccountKey.isEmpty()) {
            return@withContext null
        }

        if (memoIdentifier.startsWith(EXPLORE_MEMO_PREFIX)) {
            val remoteId = memoIdentifier.removePrefix(EXPLORE_MEMO_PREFIX).trim()
            if (remoteId.isEmpty()) {
                return@withContext null
            }
            memoService.getRepository().listMemos()
                .getOrNull()
                ?.firstOrNull { memo -> memo.remoteId == remoteId }
                ?.let { memo ->
                    return@withContext memo
                }
            val pinnedKeys = offlineGroupStore.getPinnedGroupMemoKeys(normalizedAccountKey)
            val groups = offlineGroupStore.getGroups(normalizedAccountKey)
            for (group in groups) {
                val cachedMemo = offlineGroupStore.getCachedGroupMemos(normalizedAccountKey, group.id)
                    .firstOrNull { memo -> memo.remoteId == remoteId }
                    ?: continue
                return@withContext cachedMemo.toMemoEntity(
                    identifier = memoIdentifier,
                    accountKey = normalizedAccountKey,
                    pinnedOverride = groupMemoKey(group.id, remoteId) in pinnedKeys,
                )
            }
            return@withContext null
        }

        if (!memoIdentifier.startsWith(GROUP_MEMO_PREFIX)) {
            return@withContext null
        }

        val parsed = parseGroupMemoIdentifier(memoIdentifier) ?: return@withContext null
        val pinned = offlineGroupStore.getPinnedGroupMemoKeys(normalizedAccountKey).contains(
            groupMemoKey(parsed.groupId, parsed.memoRemoteId)
        )

        if (parsed.memoRemoteId.startsWith(LOCAL_GROUP_MESSAGE_PREFIX)) {
            val localId = parsed.memoRemoteId.removePrefix(LOCAL_GROUP_MESSAGE_PREFIX).trim()
            if (localId.isNotEmpty()) {
                return@withContext offlineGroupStore.getPendingGroupMemos(normalizedAccountKey, parsed.groupId)
                    .firstOrNull { memo -> memo.localId == localId }
                    ?.toMemoEntity(
                        identifier = memoIdentifier,
                        remoteId = parsed.memoRemoteId,
                        accountKey = normalizedAccountKey,
                        pinned = pinned,
                    )
            }
        }

        return@withContext offlineGroupStore.getCachedGroupMemos(normalizedAccountKey, parsed.groupId)
            .firstOrNull { memo -> memo.remoteId == parsed.memoRemoteId }
            ?.toMemoEntity(
                identifier = memoIdentifier,
                accountKey = normalizedAccountKey,
                pinnedOverride = pinned,
            )
    }

    private data class ParsedGroupMemoIdentifier(
        val groupId: String,
        val memoRemoteId: String,
    )

    private fun parseGroupMemoIdentifier(memoIdentifier: String): ParsedGroupMemoIdentifier? {
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
            memoRemoteId = memoRemoteId,
        )
    }

    private fun groupMemoKey(groupId: String, memoRemoteId: String): String {
        return "$groupId|$memoRemoteId"
    }

    private fun CachedMemoItem.toMemoEntity(
        identifier: String,
        accountKey: String,
        pinnedOverride: Boolean? = null,
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
            lastSyncedAt = syncedAt,
        )
    }

    private fun PendingGroupMemo.toMemoEntity(
        identifier: String,
        remoteId: String,
        accountKey: String,
        pinned: Boolean,
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
            lastSyncedAt = null,
        )
        entity.tags = normalizeTagList(tags)
        return entity
    }

    private companion object {
        private const val EXPLORE_MEMO_PREFIX = "explore:"
        private const val GROUP_MEMO_PREFIX = "group:"
        private const val LOCAL_GROUP_MESSAGE_PREFIX = "local:"
    }
}
