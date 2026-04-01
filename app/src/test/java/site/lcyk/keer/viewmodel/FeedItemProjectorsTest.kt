package site.lcyk.keer.viewmodel

import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import site.lcyk.keer.data.local.entity.MemoEntity
import site.lcyk.keer.data.local.entity.ResourceEntity
import site.lcyk.keer.data.model.CachedMemoItem
import site.lcyk.keer.data.model.CachedResourceItem
import site.lcyk.keer.data.model.MemoVisibility
import site.lcyk.keer.util.ProjectedList
import java.time.Instant

class FeedItemProjectorsTest {
    @Test
    fun `home projector reuses unchanged personal and group items`() {
        val projector = HomeMemoListProjector()
        val localMemo = buildMemoEntity(
            identifier = "local-1",
            tags = listOf("personal"),
        )
        val groupMemo = buildCachedMemo(
            remoteId = "group-1",
            creatorId = "42",
        )

        val first = projector.project(
            currentUserId = "42",
            accountKey = "account",
            localMemos = listOf(localMemo),
            cachedGroupMemos = listOf(groupMemo to "room-1"),
            pinnedGroupMemoKeys = setOf("room-1|group-1"),
        )
        val second = projector.project(
            currentUserId = "42",
            accountKey = "account",
            localMemos = listOf(localMemo),
            cachedGroupMemos = listOf(groupMemo to "room-1"),
            pinnedGroupMemoKeys = setOf("room-1|group-1"),
        )

        assertTrue(first is ProjectedList<HomeMemoItem>)
        assertSame(first, second)
        assertSame(first[0], second[0])
        assertSame(first[1], second[1])
    }

    @Test
    fun `home projector rebuilds only affected group item when pinned changes`() {
        val projector = HomeMemoListProjector()
        val localMemo = buildMemoEntity(identifier = "local-1", tags = listOf("personal"))
        val groupMemo = buildCachedMemo(remoteId = "group-1", creatorId = "42")

        val first = projector.project(
            currentUserId = "42",
            accountKey = "account",
            localMemos = listOf(localMemo),
            cachedGroupMemos = listOf(groupMemo to "room-1"),
            pinnedGroupMemoKeys = emptySet(),
        )
        val second = projector.project(
            currentUserId = "42",
            accountKey = "account",
            localMemos = listOf(localMemo),
            cachedGroupMemos = listOf(groupMemo to "room-1"),
            pinnedGroupMemoKeys = setOf("room-1|group-1"),
        )

        assertNotSame(first, second)
        val firstById = first.associateBy { item -> item.memo.identifier }
        val secondById = second.associateBy { item -> item.memo.identifier }
        assertSame(firstById.getValue("local-1"), secondById.getValue("local-1"))
        assertNotSame(
            firstById.getValue("group:room-1:group-1"),
            secondById.getValue("group:room-1:group-1"),
        )
    }

    @Test
    fun `explore projector reuses unchanged collaborative and group items`() {
        val projector = ExploreMemoListProjector()
        val localMemo = buildMemoEntity(
            identifier = "local-1",
            tags = listOf("collab/42"),
        )
        val groupMemo = buildCachedMemo(
            remoteId = "group-1",
            creatorId = "77",
        )

        val first = projector.project(
            currentUserId = "42",
            localMemos = listOf(localMemo),
            cachedGroupMemos = listOf(groupMemo to "room-1"),
            pinnedGroupMemoKeys = emptySet(),
        )
        val second = projector.project(
            currentUserId = "42",
            localMemos = listOf(localMemo),
            cachedGroupMemos = listOf(groupMemo to "room-1"),
            pinnedGroupMemoKeys = emptySet(),
        )

        assertTrue(first is ProjectedList<ExploreMemoItem>)
        assertSame(first, second)
        assertSame(first[0], second[0])
        assertSame(first[1], second[1])
    }

    private fun buildMemoEntity(
        identifier: String,
        tags: List<String>,
    ): MemoEntity {
        val now = Instant.parse("2026-04-02T00:00:00Z")
        return MemoEntity(
            identifier = identifier,
            remoteId = "remote-$identifier",
            accountKey = "account",
            content = "content-$identifier",
            date = now,
            visibility = MemoVisibility.PRIVATE,
            pinned = false,
            archived = false,
            needsSync = false,
            isDeleted = false,
            lastModified = now,
            lastSyncedAt = now,
        ).also { memo ->
            memo.tags = tags
            memo.resources = listOf(
                ResourceEntity(
                    identifier = "resource-$identifier",
                    remoteId = "resource-remote-$identifier",
                    accountKey = "account",
                    date = now,
                    filename = "$identifier.jpg",
                    uri = "file:///tmp/$identifier.jpg",
                    localUri = "file:///tmp/$identifier.jpg",
                    mimeType = "image/jpeg",
                    memoId = identifier,
                )
            )
        }
    }

    private fun buildCachedMemo(
        remoteId: String,
        creatorId: String,
    ): CachedMemoItem {
        val now = Instant.parse("2026-04-02T00:00:00Z").toEpochMilli()
        return CachedMemoItem(
            remoteId = remoteId,
            content = "group-$remoteId",
            dateEpochMillis = now,
            creatorId = creatorId,
            creatorName = "User $creatorId",
            resources = listOf(
                CachedResourceItem(
                    remoteId = "resource-$remoteId",
                    dateEpochMillis = now,
                    filename = "$remoteId.jpg",
                    mimeType = "image/jpeg",
                    uri = "file:///tmp/$remoteId.jpg",
                    localUri = "file:///tmp/$remoteId.jpg",
                )
            ),
            updatedAtEpochMillis = now,
        )
    }
}
