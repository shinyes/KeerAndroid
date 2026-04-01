package site.lcyk.keer.viewmodel

import site.lcyk.keer.data.local.entity.MemoEntity
import site.lcyk.keer.data.model.Memo
import site.lcyk.keer.util.ProjectedList
import site.lcyk.keer.util.reuseOrWrapProjectedList
import site.lcyk.keer.util.toMemoEntityForCard

internal class ExploreQuoteMemoProjector {
    private data class CacheEntry(
        val accountKey: String,
        val item: ExploreMemoItem,
        val memoEntity: MemoEntity,
    )

    private var previousList: ProjectedList<MemoEntity> = ProjectedList.empty()
    private var memoByIdentifier: Map<String, CacheEntry> = emptyMap()

    fun project(
        accountKey: String,
        items: List<ExploreMemoItem>,
    ): List<MemoEntity> {
        if (accountKey.isBlank() || items.isEmpty()) {
            memoByIdentifier = emptyMap()
            previousList = ProjectedList.empty()
            return emptyList()
        }

        val nextMemoByIdentifier = LinkedHashMap<String, CacheEntry>(items.size)
        val memoEntities = items.map { item ->
            val identifier = item.toQuoteIdentifier()
            val cached = memoByIdentifier[identifier]
            val memoEntity = if (
                cached != null &&
                cached.accountKey == accountKey &&
                cached.item == item
            ) {
                cached.memoEntity
            } else {
                item.memo.toMemoEntityForCard(
                    identifier = identifier,
                    accountKey = accountKey,
                    needsSync = item.groupId != null && item.memo.remoteId.startsWith("local:"),
                )
            }
            nextMemoByIdentifier[identifier] = CacheEntry(
                accountKey = accountKey,
                item = item,
                memoEntity = memoEntity,
            )
            memoEntity
        }

        memoByIdentifier = nextMemoByIdentifier
        return reuseOrWrapProjectedList(previousList, memoEntities).also { projected ->
            previousList = projected
        }
    }
}

internal class GroupChatQuoteMemoProjector {
    private data class CacheEntry(
        val accountKey: String,
        val groupId: String,
        val memo: Memo,
        val memoEntity: MemoEntity,
    )

    private var previousList: ProjectedList<MemoEntity> = ProjectedList.empty()
    private var memoByIdentifier: Map<String, CacheEntry> = emptyMap()

    fun project(
        accountKey: String,
        groupId: String,
        memos: List<Memo>,
    ): List<MemoEntity> {
        if (accountKey.isBlank() || groupId.isBlank() || memos.isEmpty()) {
            memoByIdentifier = emptyMap()
            previousList = ProjectedList.empty()
            return emptyList()
        }

        val nextMemoByIdentifier = LinkedHashMap<String, CacheEntry>(memos.size)
        val memoEntities = memos.map { memo ->
            val identifier = groupQuoteIdentifier(groupId, memo.remoteId)
            val cached = memoByIdentifier[identifier]
            val memoEntity = if (
                cached != null &&
                cached.accountKey == accountKey &&
                cached.groupId == groupId &&
                cached.memo == memo
            ) {
                cached.memoEntity
            } else {
                memo.toMemoEntityForCard(
                    identifier = identifier,
                    accountKey = accountKey,
                    needsSync = memo.remoteId.startsWith("local:"),
                )
            }
            nextMemoByIdentifier[identifier] = CacheEntry(
                accountKey = accountKey,
                groupId = groupId,
                memo = memo,
                memoEntity = memoEntity,
            )
            memoEntity
        }

        memoByIdentifier = nextMemoByIdentifier
        return reuseOrWrapProjectedList(previousList, memoEntities).also { projected ->
            previousList = projected
        }
    }
}

private fun ExploreMemoItem.toQuoteIdentifier(): String {
    return if (groupId.isNullOrBlank()) {
        "explore:${memo.remoteId}"
    } else {
        groupQuoteIdentifier(groupId, memo.remoteId)
    }
}

private fun groupQuoteIdentifier(groupId: String, memoRemoteId: String): String {
    return "group:$groupId:$memoRemoteId"
}
