package site.lcyk.keer.viewmodel

import site.lcyk.keer.data.local.entity.MemoEntity
import site.lcyk.keer.data.local.entity.ResourceEntity
import site.lcyk.keer.data.model.CachedMemoItem
import site.lcyk.keer.data.model.Memo
import site.lcyk.keer.data.model.Resource
import site.lcyk.keer.data.model.toMemo
import site.lcyk.keer.util.ProjectedList
import site.lcyk.keer.util.extractCollaboratorIds
import site.lcyk.keer.util.normalizeCollaboratorId
import site.lcyk.keer.util.reuseOrWrapProjectedList
import site.lcyk.keer.util.toMemoEntityForCard

internal class HomeMemoListProjector {
    private data class PersonalCache(
        val sourceMemo: MemoEntity,
        val item: HomeMemoItem,
    )

    private data class GroupCache(
        val signature: GroupSignature,
        val item: HomeMemoItem,
    )

    private data class GroupSignature(
        val cachedMemo: CachedMemoItem,
        val pinned: Boolean,
        val accountKey: String,
    )

    private var previousList: ProjectedList<HomeMemoItem> = ProjectedList.empty()
    private var personalById: Map<String, PersonalCache> = emptyMap()
    private var groupByKey: Map<String, GroupCache> = emptyMap()

    fun project(
        currentUserId: String?,
        accountKey: String,
        localMemos: List<MemoEntity>,
        cachedGroupMemos: List<Pair<CachedMemoItem, String>>,
        pinnedGroupMemoKeys: Set<String>,
    ): List<HomeMemoItem> {
        val nextPersonalById = LinkedHashMap<String, PersonalCache>(localMemos.size)
        val personalItems = ArrayList<HomeMemoItem>(localMemos.size)
        localMemos.forEach { memo ->
            val reused = personalById[memo.identifier]?.takeIf { cache -> cache.sourceMemo === memo }?.item
                ?: HomeMemoItem(memo = memo)
            personalItems += reused
            nextPersonalById[memo.identifier] = PersonalCache(
                sourceMemo = memo,
                item = reused,
            )
        }

        val nextGroupByKey = LinkedHashMap<String, GroupCache>(cachedGroupMemos.size)
        val groupItems = if (currentUserId.isNullOrBlank()) {
            emptyList()
        } else {
            cachedGroupMemos.mapNotNull { (cachedMemo, groupId) ->
                val creatorId = normalizeCollaboratorId(cachedMemo.creatorId.orEmpty())
                if (creatorId != currentUserId) {
                    return@mapNotNull null
                }
                val key = groupItemKey(groupId, cachedMemo.remoteId)
                val pinned = groupMemoKey(groupId, cachedMemo.remoteId) in pinnedGroupMemoKeys
                val signature = GroupSignature(
                    cachedMemo = cachedMemo,
                    pinned = pinned,
                    accountKey = accountKey,
                )
                val item = groupByKey[key]?.takeIf { cache -> cache.signature == signature }?.item
                    ?: buildGroupHomeItem(
                        accountKey = accountKey,
                        groupId = groupId,
                        cachedMemo = cachedMemo,
                        pinned = pinned,
                    )
                nextGroupByKey[key] = GroupCache(
                    signature = signature,
                    item = item,
                )
                item
            }
        }

        personalById = nextPersonalById
        groupByKey = nextGroupByKey

        val items = (personalItems + groupItems)
            .distinctBy { item -> item.memo.identifier }
            .sortedWith(
                compareByDescending<HomeMemoItem> { it.memo.pinned }
                    .thenByDescending { it.memo.date }
            )
        return reuseOrWrapProjectedList(previousList, items).also { projected ->
            previousList = projected
        }
    }

    private fun buildGroupHomeItem(
        accountKey: String,
        groupId: String,
        cachedMemo: CachedMemoItem,
        pinned: Boolean,
    ): HomeMemoItem {
        val resolvedMemo = cachedMemo.toPinnedMemo(pinned)
        val syncedAt = resolvedMemo.updatedAt ?: resolvedMemo.date
        return HomeMemoItem(
            memo = resolvedMemo.toMemoEntityForCard(
                identifier = groupItemKey(groupId, resolvedMemo.remoteId),
                accountKey = accountKey,
                needsSync = false,
                lastModified = syncedAt,
                lastSyncedAt = syncedAt,
            ),
            groupId = groupId,
        )
    }
}

internal class ExploreMemoListProjector {
    private data class PersonalCache(
        val sourceMemo: MemoEntity,
        val item: ExploreMemoItem,
    )

    private data class GroupCache(
        val signature: GroupSignature,
        val item: ExploreMemoItem,
    )

    private data class GroupSignature(
        val cachedMemo: CachedMemoItem,
        val pinned: Boolean,
    )

    private var previousList: ProjectedList<ExploreMemoItem> = ProjectedList.empty()
    private var personalById: Map<String, PersonalCache> = emptyMap()
    private var groupByKey: Map<String, GroupCache> = emptyMap()

    fun project(
        currentUserId: String?,
        localMemos: List<MemoEntity>,
        cachedGroupMemos: List<Pair<CachedMemoItem, String>>,
        pinnedGroupMemoKeys: Set<String>,
    ): List<ExploreMemoItem> {
        val nextPersonalById = LinkedHashMap<String, PersonalCache>(localMemos.size)
        val personalItems = if (currentUserId.isNullOrBlank()) {
            emptyList()
        } else {
            localMemos.mapNotNull { memo ->
                val isCollaborative = extractCollaboratorIds(memo.tags).any { collaboratorId ->
                    normalizeCollaboratorId(collaboratorId) == currentUserId
                }
                if (!isCollaborative) {
                    return@mapNotNull null
                }
                val reused = personalById[memo.identifier]?.takeIf { cache -> cache.sourceMemo === memo }?.item
                    ?: ExploreMemoItem(memo = memo.toExploreMemoSnapshot(), groupId = null)
                nextPersonalById[memo.identifier] = PersonalCache(
                    sourceMemo = memo,
                    item = reused,
                )
                reused
            }
        }

        val nextGroupByKey = LinkedHashMap<String, GroupCache>(cachedGroupMemos.size)
        val groupItems = cachedGroupMemos.map { (cachedMemo, groupId) ->
            val key = groupItemKey(groupId, cachedMemo.remoteId)
            val pinned = groupMemoKey(groupId, cachedMemo.remoteId) in pinnedGroupMemoKeys
            val signature = GroupSignature(
                cachedMemo = cachedMemo,
                pinned = pinned,
            )
            val item = groupByKey[key]?.takeIf { cache -> cache.signature == signature }?.item
                ?: ExploreMemoItem(
                    memo = cachedMemo.toPinnedMemo(pinned),
                    groupId = groupId,
                )
            nextGroupByKey[key] = GroupCache(
                signature = signature,
                item = item,
            )
            item
        }

        personalById = nextPersonalById
        groupByKey = nextGroupByKey

        val items = (personalItems + groupItems)
            .distinctBy { item -> "${item.groupId.orEmpty()}|${item.memo.remoteId}" }
            .sortedByDescending { item -> item.memo.date }
        return reuseOrWrapProjectedList(previousList, items).also { projected ->
            previousList = projected
        }
    }
}

private fun CachedMemoItem.toPinnedMemo(pinned: Boolean): Memo {
    val memo = toMemo()
    return if (memo.pinned == pinned) memo else memo.copy(pinned = pinned)
}

private fun MemoEntity.toExploreMemoSnapshot(): Memo {
    return Memo(
        remoteId = remoteId ?: identifier,
        content = content,
        date = date,
        pinned = pinned,
        visibility = visibility,
        resources = resources.map(ResourceEntity::toExploreResourceSnapshot),
        tags = tags,
        latitude = latitude,
        longitude = longitude,
        creator = null,
        archived = archived,
        updatedAt = lastSyncedAt ?: lastModified,
        quoteSourceKind = quoteSourceKind,
        quoteSource = quoteSource,
        quoteStatus = quoteStatus,
        quoteContentPreview = quoteContentPreview,
        quoteDate = quoteDate,
        quoteHasAttachments = quoteHasAttachments,
    )
}

private fun ResourceEntity.toExploreResourceSnapshot(): Resource {
    return Resource(
        remoteId = remoteId ?: identifier,
        date = date,
        filename = filename,
        mimeType = mimeType,
        encryptionMetadata = encryptionMetadata,
        uri = uri,
        localUri = localUri,
        thumbnailUri = thumbnailUri,
        thumbnailLocalUri = thumbnailLocalUri,
    )
}

private fun groupMemoKey(groupId: String, memoRemoteId: String): String {
    return "$groupId|$memoRemoteId"
}

private fun groupItemKey(groupId: String, memoRemoteId: String): String {
    return "group:$groupId:$memoRemoteId"
}
