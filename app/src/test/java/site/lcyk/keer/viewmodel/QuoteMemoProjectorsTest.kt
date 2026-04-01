package site.lcyk.keer.viewmodel

import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import site.lcyk.keer.data.model.Memo
import site.lcyk.keer.data.model.MemoVisibility
import site.lcyk.keer.util.ProjectedList
import java.time.Instant

class QuoteMemoProjectorsTest {
    @Test
    fun `explore quote memo projector reuses unchanged memo entities`() {
        val projector = ExploreQuoteMemoProjector()
        val items = listOf(
            ExploreMemoItem(
                memo = buildMemo(remoteId = "memo-1"),
                groupId = null,
            ),
            ExploreMemoItem(
                memo = buildMemo(remoteId = "group-1"),
                groupId = "room-1",
            ),
        )

        val first = projector.project(accountKey = "account", items = items)
        val second = projector.project(accountKey = "account", items = items)

        assertTrue(first is ProjectedList<*>)
        assertSame(first, second)
        assertSame(first[0], second[0])
        assertSame(first[1], second[1])
    }

    @Test
    fun `group chat quote memo projector rebuilds only affected memo`() {
        val projector = GroupChatQuoteMemoProjector()
        val first = projector.project(
            accountKey = "account",
            groupId = "room-1",
            memos = listOf(
                buildMemo(remoteId = "memo-1", content = "first"),
                buildMemo(remoteId = "memo-2", content = "second"),
            ),
        )
        val second = projector.project(
            accountKey = "account",
            groupId = "room-1",
            memos = listOf(
                buildMemo(remoteId = "memo-1", content = "first updated"),
                buildMemo(remoteId = "memo-2", content = "second"),
            ),
        )

        assertNotSame(first, second)
        assertNotSame(first[0], second[0])
        assertSame(first[1], second[1])
    }

    private fun buildMemo(
        remoteId: String,
        content: String = "body",
    ): Memo {
        val timestamp = Instant.parse("2026-04-02T00:00:00Z")
        return Memo(
            remoteId = remoteId,
            content = content,
            date = timestamp,
            pinned = false,
            visibility = MemoVisibility.PRIVATE,
            resources = emptyList(),
            tags = emptyList(),
            archived = false,
            updatedAt = timestamp,
        )
    }
}
