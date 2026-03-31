package site.lcyk.keer.viewmodel

import site.lcyk.keer.data.local.entity.MemoEntity
import site.lcyk.keer.data.model.MemoVisibility
import site.lcyk.keer.data.model.Memo
import site.lcyk.keer.util.extractCollaboratorIds
import site.lcyk.keer.util.isCollaboratorTag
import site.lcyk.keer.util.isQuoteTag
import site.lcyk.keer.util.ResolvedMemoQuote
import site.lcyk.keer.util.normalizeCollaboratorId
import site.lcyk.keer.util.normalizeTagList
import site.lcyk.keer.util.normalizeTagName

data class MemoCardUiModel(
    val memo: MemoEntity,
    val resolvedQuote: ResolvedMemoQuote?,
    val displayTags: List<String>,
    val collaboratorIds: List<String>,
    val authorName: String? = null,
    val authorAvatarUrl: String? = null,
)

data class CardListUiState<TCard>(
    val cards: List<TCard> = emptyList(),
    val prefetchMemos: List<MemoEntity> = emptyList(),
    val collaboratorIdsToPrefetch: List<String> = emptyList(),
)

data class HomeMemoCardUiModel(
    val card: MemoCardUiModel,
    val groupId: String? = null,
)

internal fun buildHomeFeedCards(
    items: List<HomeMemoCardUiModel>,
): List<MemoCardUiModel> {
    return items.map(HomeMemoCardUiModel::card)
}

internal fun buildHomeMemoItemsFromCards(
    items: List<HomeMemoCardUiModel>,
): List<HomeMemoItem> {
    return items.map { item ->
        HomeMemoItem(
            memo = item.card.memo,
            groupId = item.groupId,
            authorName = item.card.authorName,
            authorAvatarUrl = item.card.authorAvatarUrl,
        )
    }
}

internal fun indexHomeMemoCardsByMemoIdentifier(
    items: List<HomeMemoCardUiModel>,
): Map<String, HomeMemoCardUiModel> {
    return items.associateBy { item -> item.card.memo.identifier }
}

internal fun buildResolvedQuoteMapFromMemoCards(
    cards: List<MemoCardUiModel>,
): Map<String, ResolvedMemoQuote> {
    return cards.mapNotNull { card ->
        card.resolvedQuote?.let { resolvedQuote ->
            card.memo.identifier to resolvedQuote
        }
    }.toMap()
}

internal fun buildResolvedQuoteMapFromHomeMemoCards(
    cards: List<HomeMemoCardUiModel>,
): Map<String, ResolvedMemoQuote> {
    return buildResolvedQuoteMapFromMemoCards(cards.map(HomeMemoCardUiModel::card))
}

internal fun buildMemoCardListUiState(
    cards: List<MemoCardUiModel>,
): CardListUiState<MemoCardUiModel> {
    return CardListUiState(
        cards = cards,
        prefetchMemos = cards.map(MemoCardUiModel::memo),
        collaboratorIdsToPrefetch = buildCollaboratorPrefetchIds(
            cards.map(MemoCardUiModel::collaboratorIds)
        ),
    )
}

internal fun buildMemoCardUiModels(
    memos: List<MemoEntity>,
    resolvedQuoteByMemoId: Map<String, ResolvedMemoQuote>,
): List<MemoCardUiModel> = memos.map { memo ->
    MemoCardUiModel(
        memo = memo,
        resolvedQuote = resolvedQuoteByMemoId[memo.identifier],
        displayTags = buildMemoCardDisplayTags(memo.tags),
        collaboratorIds = buildMemoCardCollaboratorIds(memo.tags),
        authorName = null,
        authorAvatarUrl = null,
    )
}

internal fun buildHomeMemoCardUiModels(
    items: List<HomeMemoItem>,
    resolvedQuoteByMemoId: Map<String, ResolvedMemoQuote>,
): List<HomeMemoCardUiModel> {
    if (items.isEmpty()) {
        return emptyList()
    }

    val memoCards = buildMemoCardUiModels(
        memos = items.map(HomeMemoItem::memo),
        resolvedQuoteByMemoId = resolvedQuoteByMemoId,
    ).associateBy { model -> model.memo.identifier }

    return items.mapNotNull { item ->
        memoCards[item.memo.identifier]?.let { card ->
            HomeMemoCardUiModel(
                card = card.copy(
                    authorName = item.authorName,
                    authorAvatarUrl = item.authorAvatarUrl,
                ),
                groupId = item.groupId,
            )
        }
    }
}

internal fun filterMemoCardsByTag(
    cards: List<MemoCardUiModel>,
    tag: String,
): List<MemoCardUiModel> {
    val normalizedTag = tag.trim()
    if (normalizedTag.isEmpty()) {
        return cards
    }
    return cards.filter { card ->
        card.memo.tags.any { memoTag ->
            memoTag == normalizedTag || memoTag.startsWith("$normalizedTag/")
        }
    }
}

internal fun filterMemoCardsBySearch(
    cards: List<MemoCardUiModel>,
    query: String,
): List<MemoCardUiModel> {
    val normalizedQuery = query.trim()
    if (normalizedQuery.isEmpty()) {
        return cards
    }
    return cards.filter { card ->
        card.memo.content.contains(normalizedQuery, ignoreCase = true)
    }
}

internal fun filterMemoCardsByColumn(
    cards: List<MemoCardUiModel>,
    requiredTags: List<String>,
    pinnedMemoRemoteIds: Set<String>,
): List<MemoCardUiModel> {
    if (cards.isEmpty()) {
        return emptyList()
    }
    return cards
        .filter { card ->
            val memo = card.memo
            memo.visibility == MemoVisibility.PRIVATE &&
                memoMatchesRequiredTags(memo, requiredTags)
        }
        .map { card ->
            card.copy(
                memo = card.memo.withPinnedState(
                    pinned = pinnedMemoRemoteIds.contains(card.memo.remoteId)
                )
            )
        }
}

private fun memoMatchesRequiredTags(
    memo: MemoEntity,
    requiredTags: List<String>,
): Boolean {
    if (requiredTags.isEmpty()) {
        return true
    }
    return requiredTags.all { rawTag ->
        val normalizedRequiredTag = normalizeTagName(rawTag)
        normalizedRequiredTag.isNotEmpty() && memo.tags.any { memoTag ->
            memoTag == normalizedRequiredTag || memoTag.startsWith("$normalizedRequiredTag/")
        }
    }
}

private fun MemoEntity.withPinnedState(pinned: Boolean): MemoEntity {
    if (this.pinned == pinned) {
        return this
    }
    return copy(pinned = pinned).also { copied ->
        copied.resources = resources
        copied.tags = tags
    }
}

data class ExploreCardUiModel(
    val source: ExploreMemoItem,
    val memo: MemoEntity,
    val canManage: Boolean,
    val resolvedQuote: ResolvedMemoQuote?,
    val displayTags: List<String>,
    val collaboratorIds: List<String>,
    val authorName: String? = null,
    val authorAvatarUrl: String? = null,
)

data class GroupChatCardUiModel(
    val source: Memo,
    val memo: MemoEntity,
    val canManage: Boolean,
    val resolvedQuote: ResolvedMemoQuote?,
    val displayTags: List<String>,
    val collaboratorIds: List<String>,
    val authorName: String? = null,
    val authorAvatarUrl: String? = null,
)

internal fun buildExploreCardUiModels(
    items: List<ExploreMemoItem>,
    memoEntities: List<MemoEntity>,
    resolvedQuoteByMemoId: Map<String, ResolvedMemoQuote>,
    canManage: (ExploreMemoItem) -> Boolean,
): List<ExploreCardUiModel> {
    if (items.isEmpty() || memoEntities.isEmpty()) {
        return emptyList()
    }
    return items.mapIndexedNotNull { index, item ->
        val memoEntity = memoEntities.getOrNull(index) ?: return@mapIndexedNotNull null
        ExploreCardUiModel(
            source = item,
            memo = memoEntity,
            canManage = canManage(item),
            resolvedQuote = resolvedQuoteByMemoId[memoEntity.identifier],
            displayTags = buildMemoCardDisplayTags(memoEntity.tags),
            collaboratorIds = buildMemoCardCollaboratorIds(memoEntity.tags),
            authorName = item.memo.creator?.name,
            authorAvatarUrl = item.memo.creator?.avatarUrl,
        )
    }
}

internal fun buildGroupChatCardUiModels(
    memos: List<Memo>,
    memoEntities: List<MemoEntity>,
    resolvedQuoteByMemoId: Map<String, ResolvedMemoQuote>,
    canManage: (Memo) -> Boolean,
): List<GroupChatCardUiModel> {
    if (memos.isEmpty() || memoEntities.isEmpty()) {
        return emptyList()
    }
    return memos.mapIndexedNotNull { index, memo ->
        val memoEntity = memoEntities.getOrNull(index) ?: return@mapIndexedNotNull null
        GroupChatCardUiModel(
            source = memo,
            memo = memoEntity,
            canManage = canManage(memo),
            resolvedQuote = resolvedQuoteByMemoId[memoEntity.identifier],
            displayTags = buildMemoCardDisplayTags(memoEntity.tags),
            collaboratorIds = buildMemoCardCollaboratorIds(memoEntity.tags),
            authorName = memo.creator?.name,
            authorAvatarUrl = memo.creator?.avatarUrl,
        )
    }
}

internal fun buildMemoCardDisplayTags(tags: List<String>): List<String> {
    return normalizeTagList(
        tags
            .filterNot(::isCollaboratorTag)
            .filterNot(::isQuoteTag)
    )
}

internal fun buildMemoCardCollaboratorIds(tags: List<String>): List<String> {
    return extractCollaboratorIds(tags)
}

internal fun buildMemoEditorSelectedTags(tags: List<String>): List<String> {
    return buildMemoCardDisplayTags(tags)
}

internal fun buildMemoEditorCollaboratorIds(tags: List<String>): List<String> {
    return buildMemoCardCollaboratorIds(tags)
        .map(::normalizeCollaboratorId)
        .filter { it.isNotEmpty() }
        .distinct()
}

internal fun buildCollaboratorPrefetchIds(
    collaboratorGroups: Iterable<List<String>>,
): List<String> {
    return collaboratorGroups
        .asSequence()
        .flatMap { ids -> ids.asSequence() }
        .map(::normalizeCollaboratorId)
        .filter { it.isNotEmpty() }
        .distinct()
        .toList()
}

internal fun buildExploreCardListUiState(
    cards: List<ExploreCardUiModel>,
): CardListUiState<ExploreCardUiModel> {
    return CardListUiState(
        cards = cards,
        prefetchMemos = cards.map(ExploreCardUiModel::memo),
        collaboratorIdsToPrefetch = buildCollaboratorPrefetchIds(
            cards.map(ExploreCardUiModel::collaboratorIds)
        ),
    )
}

internal fun buildGroupChatCardListUiState(
    cards: List<GroupChatCardUiModel>,
): CardListUiState<GroupChatCardUiModel> {
    return CardListUiState(
        cards = cards,
        prefetchMemos = cards.map(GroupChatCardUiModel::memo),
        collaboratorIdsToPrefetch = buildCollaboratorPrefetchIds(
            cards.map(GroupChatCardUiModel::collaboratorIds)
        ),
    )
}
