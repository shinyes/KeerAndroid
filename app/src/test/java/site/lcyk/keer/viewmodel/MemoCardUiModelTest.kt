package site.lcyk.keer.viewmodel

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import site.lcyk.keer.data.local.entity.MemoEntity
import site.lcyk.keer.data.model.Memo
import site.lcyk.keer.data.model.MemoVisibility
import site.lcyk.keer.data.model.User
import site.lcyk.keer.util.MemoQuoteDescriptor
import site.lcyk.keer.util.MemoQuoteSourceKind
import site.lcyk.keer.util.ResolvedMemoQuote
import site.lcyk.keer.util.buildMemoQuoteTag

class MemoCardUiModelTest {

    @Test
    fun buildMemoCardUiModels_mapsResolvedQuotesByMemoIdentifier() {
        val firstMemo = memoEntity(
            identifier = "memo-1",
            content = "First",
            tags = listOf("dev", "collab/alice"),
        )
        val secondMemo = memoEntity(identifier = "memo-2", content = "Second")
        val quote = ResolvedMemoQuote(
            sourceMemo = secondMemo,
            preview = null,
        )

        val models = buildMemoCardUiModels(
            memos = listOf(firstMemo, secondMemo),
            resolvedQuoteByMemoId = mapOf(firstMemo.identifier to quote),
        )

        assertEquals(listOf(firstMemo, secondMemo), models.map { it.memo })
        assertEquals(quote, models.first().resolvedQuote)
        assertEquals(listOf("dev"), models.first().displayTags)
        assertEquals(listOf("alice"), models.first().collaboratorIds)
        assertNull(models.last().resolvedQuote)
    }

    @Test
    fun buildHomeMemoCardUiModels_preservesGroupOwnershipAndResolvedQuote() {
        val localMemo = memoEntity(identifier = "local", content = "Local")
        val groupMemo = memoEntity(identifier = "group", content = "Group")
        val quote = ResolvedMemoQuote(
            sourceMemo = localMemo,
            preview = null,
        )

        val models = buildHomeMemoCardUiModels(
            items = listOf(
                HomeMemoItem(memo = localMemo, groupId = null),
                HomeMemoItem(
                    memo = groupMemo,
                    groupId = "group-1",
                    authorName = "Alice",
                    authorAvatarUrl = "https://example.com/alice.png",
                ),
            ),
            resolvedQuoteByMemoId = mapOf(groupMemo.identifier to quote),
        )

        assertEquals(2, models.size)
        assertEquals(localMemo, models[0].card.memo)
        assertNull(models[0].groupId)
        assertEquals(groupMemo, models[1].card.memo)
        assertEquals("group-1", models[1].groupId)
        assertEquals(quote, models[1].card.resolvedQuote)
        assertEquals("Alice", models[1].card.authorName)
        assertEquals("https://example.com/alice.png", models[1].card.authorAvatarUrl)
    }

    @Test
    fun buildHomeFeedCardsAndIndex_preserveCardOrderAndLookup() {
        val firstMemo = memoEntity(identifier = "memo-1", content = "First")
        val secondMemo = memoEntity(identifier = "memo-2", content = "Second")
        val homeCards = listOf(
            HomeMemoCardUiModel(
                card = MemoCardUiModel(
                    memo = firstMemo,
                    resolvedQuote = null,
                    displayTags = emptyList(),
                    collaboratorIds = emptyList(),
                ),
                groupId = null,
            ),
            HomeMemoCardUiModel(
                card = MemoCardUiModel(
                    memo = secondMemo,
                    resolvedQuote = null,
                    displayTags = listOf("focus"),
                    collaboratorIds = listOf("alice"),
                    authorName = "Alice",
                    authorAvatarUrl = "https://example.com/alice.png",
                ),
                groupId = "group-1",
            ),
        )

        val feedCards = buildHomeFeedCards(homeCards)
        val homeCardIndex = indexHomeMemoCardsByMemoIdentifier(homeCards)

        assertEquals(listOf(firstMemo, secondMemo), feedCards.map { it.memo })
        assertEquals(homeCards[0], homeCardIndex[firstMemo.identifier])
        assertEquals(homeCards[1], homeCardIndex[secondMemo.identifier])
    }

    @Test
    fun filterMemoCards_helpersRespectTagQueryAndPinnedColumnState() {
        val firstMemo = memoEntity(
            identifier = "memo-1",
            content = "Learn Kotlin",
            tags = listOf("dev/kotlin"),
        )
        val secondMemo = memoEntity(
            identifier = "memo-2",
            content = "Buy groceries",
            remoteId = "remote-2",
            tags = listOf("life"),
        )
        val models = buildMemoCardUiModels(
            memos = listOf(firstMemo, secondMemo),
            resolvedQuoteByMemoId = emptyMap(),
        )

        val tagFiltered = filterMemoCardsByTag(models, "dev")
        val queryFiltered = filterMemoCardsBySearch(models, "groc")
        val columnFiltered = filterMemoCardsByColumn(
            cards = models,
            requiredTags = listOf("life"),
            pinnedMemoRemoteIds = setOf("remote-2"),
        )

        assertEquals(listOf("memo-1"), tagFiltered.map { it.memo.identifier })
        assertEquals(listOf("memo-2"), queryFiltered.map { it.memo.identifier })
        assertEquals(listOf("memo-2"), columnFiltered.map { it.memo.identifier })
        assertTrue(columnFiltered.single().memo.pinned)
        assertFalse(models[1].memo.pinned)
    }

    @Test
    fun buildExploreCardUiModels_precomputesDisplayTagsAndCollaborators() {
        val memo = remoteMemo(
            remoteId = "explore-1",
            content = "Explore memo",
            tags = listOf(
                "dev/android",
                "collab/alice",
                buildMemoQuoteTag(
                    MemoQuoteDescriptor(
                        sourceKind = MemoQuoteSourceKind.REMOTE,
                        source = "quoted-1",
                    )
                ),
            ),
        )
        val memoEntity = memoEntity(
            identifier = "explore:explore-1",
            content = memo.content,
            tags = memo.tags,
        )
        val quote = ResolvedMemoQuote(
            sourceMemo = memoEntity,
            preview = null,
        )

        val models = buildExploreCardUiModels(
            items = listOf(ExploreMemoItem(memo = memo)),
            memoEntities = listOf(memoEntity),
            resolvedQuoteByMemoId = mapOf(memoEntity.identifier to quote),
            canManage = { true },
        )

        assertEquals(1, models.size)
        assertEquals(listOf("dev/android"), models.single().displayTags)
        assertEquals(listOf("alice"), models.single().collaboratorIds)
        assertTrue(models.single().canManage)
        assertEquals(quote, models.single().resolvedQuote)
        assertEquals("Creator", models.single().authorName)
        assertNull(models.single().authorAvatarUrl)
    }

    @Test
    fun buildGroupChatCardUiModels_precomputesDisplayTagsAndCollaborators() {
        val memo = remoteMemo(
            remoteId = "group-1",
            content = "Group memo",
            tags = listOf("life", "collab/bob"),
        )
        val memoEntity = memoEntity(
            identifier = "group:room-1:group-1",
            content = memo.content,
            tags = memo.tags,
        )

        val models = buildGroupChatCardUiModels(
            memos = listOf(memo),
            memoEntities = listOf(memoEntity),
            resolvedQuoteByMemoId = emptyMap(),
            canManage = { false },
        )

        assertEquals(1, models.size)
        assertEquals(listOf("life"), models.single().displayTags)
        assertEquals(listOf("bob"), models.single().collaboratorIds)
        assertFalse(models.single().canManage)
        assertNull(models.single().resolvedQuote)
        assertEquals("Creator", models.single().authorName)
        assertNull(models.single().authorAvatarUrl)
    }

    @Test
    fun buildMemoEditorHelpers_stripQuoteAndCollaboratorTags_consistently() {
        val tags = listOf(
            "dev/android",
            "collab/alice",
            buildMemoQuoteTag(
                MemoQuoteDescriptor(
                    sourceKind = MemoQuoteSourceKind.REMOTE,
                    source = "quoted-1",
                )
            ),
        )

        assertEquals(listOf("dev/android"), buildMemoEditorSelectedTags(tags))
        assertEquals(listOf("alice"), buildMemoEditorCollaboratorIds(tags))
    }

    @Test
    fun buildCollaboratorPrefetchIds_normalizesAndDeduplicatesAcrossCards() {
        val ids = buildCollaboratorPrefetchIds(
            listOf(
                listOf("alice", "bob"),
                listOf("bob", "carol"),
                listOf("  /alice  ", ""),
            )
        )

        assertEquals(listOf("alice", "bob", "carol"), ids)
    }

    @Test
    fun buildMemoCardListUiState_collectsCardsMemosAndDeduplicatedCollaborators() {
        val firstMemo = memoEntity(
            identifier = "memo-1",
            content = "First",
            tags = listOf("focus", "collab/alice"),
        )
        val secondMemo = memoEntity(
            identifier = "memo-2",
            content = "Second",
            tags = listOf("life", "collab/alice", "collab/bob"),
        )

        val state = buildMemoCardListUiState(
            buildMemoCardUiModels(
                memos = listOf(firstMemo, secondMemo),
                resolvedQuoteByMemoId = emptyMap(),
            )
        )

        assertEquals(listOf(firstMemo, secondMemo), state.prefetchMemos)
        assertEquals(listOf("alice", "bob"), state.collaboratorIdsToPrefetch)
        assertEquals(listOf("memo-1", "memo-2"), state.cards.map { it.memo.identifier })
    }

    @Test
    fun buildExploreAndGroupCardListUiState_collectsPrefetchInputs() {
        val firstMemo = remoteMemo(
            remoteId = "explore-1",
            content = "Explore memo",
            tags = listOf("focus", "collab/alice"),
        )
        val secondMemo = remoteMemo(
            remoteId = "group-1",
            content = "Group memo",
            tags = listOf("life", "collab/alice", "collab/bob"),
        )
        val firstMemoEntity = memoEntity(
            identifier = "explore:1",
            content = firstMemo.content,
            tags = firstMemo.tags,
        )
        val secondMemoEntity = memoEntity(
            identifier = "group:1",
            content = secondMemo.content,
            tags = secondMemo.tags,
        )

        val exploreState = buildExploreCardListUiState(
            buildExploreCardUiModels(
                items = listOf(ExploreMemoItem(memo = firstMemo)),
                memoEntities = listOf(firstMemoEntity),
                resolvedQuoteByMemoId = emptyMap(),
                canManage = { true },
            )
        )
        val groupState = buildGroupChatCardListUiState(
            buildGroupChatCardUiModels(
                memos = listOf(secondMemo),
                memoEntities = listOf(secondMemoEntity),
                resolvedQuoteByMemoId = emptyMap(),
                canManage = { true },
            )
        )

        assertEquals(listOf(firstMemoEntity), exploreState.prefetchMemos)
        assertEquals(listOf("alice"), exploreState.collaboratorIdsToPrefetch)
        assertEquals(listOf(secondMemoEntity), groupState.prefetchMemos)
        assertEquals(listOf("alice", "bob"), groupState.collaboratorIdsToPrefetch)
    }

    private fun memoEntity(
        identifier: String,
        content: String,
        remoteId: String? = null,
        tags: List<String> = emptyList(),
    ): MemoEntity = MemoEntity(
        identifier = identifier,
        remoteId = remoteId,
        accountKey = "account-key",
        content = content,
        date = Instant.parse("2026-03-30T12:00:00Z"),
        visibility = MemoVisibility.PRIVATE,
        pinned = false,
        archived = false,
    ).also { memo ->
        memo.tags = tags
    }

    private fun remoteMemo(
        remoteId: String,
        content: String,
        tags: List<String> = emptyList(),
    ): Memo = Memo(
        remoteId = remoteId,
        content = content,
        date = Instant.parse("2026-03-30T12:00:00Z"),
        pinned = false,
        visibility = MemoVisibility.PRIVATE,
        resources = emptyList(),
        tags = tags,
        creator = User(
            identifier = "creator-1",
            name = "Creator",
        ),
    )
}
