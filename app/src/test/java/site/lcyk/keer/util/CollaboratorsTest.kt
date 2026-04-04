package site.lcyk.keer.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import site.lcyk.keer.data.model.Memo
import site.lcyk.keer.data.model.MemoVisibility
import site.lcyk.keer.data.model.User
import java.time.Instant

class CollaboratorsTest {

    @Test
    fun `canManageCollaborativeMemo allows creator and collaborator`() {
        val memo = buildMemo(
            creatorId = "42",
            tags = listOf("collab/99"),
        )

        assertTrue(canManageCollaborativeMemo(memo, "42"))
        assertTrue(canManageCollaborativeMemo(memo, "99"))
    }

    @Test
    fun `canManageCollaborativeMemo rejects unrelated user`() {
        val memo = buildMemo(
            creatorId = "42",
            tags = listOf("collab/99"),
        )

        assertFalse(canManageCollaborativeMemo(memo, "100"))
    }

    private fun buildMemo(
        creatorId: String,
        tags: List<String>,
    ): Memo {
        return Memo(
            remoteId = "memo-1",
            content = "content",
            date = Instant.parse("2026-04-02T00:00:00Z"),
            pinned = false,
            visibility = MemoVisibility.PROTECTED,
            resources = emptyList(),
            tags = tags,
            creator = User(
                identifier = creatorId,
                name = "User $creatorId",
                startDate = Instant.parse("2026-04-01T00:00:00Z"),
            ),
        )
    }
}
