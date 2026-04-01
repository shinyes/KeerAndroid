package site.lcyk.keer.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test
import site.lcyk.keer.data.local.entity.MemoEntity
import site.lcyk.keer.data.model.MemoVisibility
import java.time.Instant

class ResolvedMemoQuoteProjectorTest {
    @Test
    fun `project reuses previous map when unrelated memo changes`() {
        val projector = ResolvedMemoQuoteProjector()
        val sourceMemo = buildMemo(
            identifier = "source-1",
            remoteId = "remote-source-1",
            content = "source body",
        )
        val quotedMemo = buildMemo(
            identifier = "quote-1",
            remoteId = "remote-quote-1",
            content = "quoted body",
            quoteSourceKind = "remote",
            quoteSource = "remote-source-1",
        )
        val unrelatedMemo = buildMemo(
            identifier = "other-1",
            remoteId = "remote-other-1",
            content = "other body",
        )

        val first = projector.project(listOf(sourceMemo, quotedMemo, unrelatedMemo))
        val second = projector.project(
            listOf(
                sourceMemo,
                quotedMemo,
                unrelatedMemo.copy(content = "other body changed"),
            )
        )

        assertSame(first, second)
        assertSame(first.getValue("quote-1"), second.getValue("quote-1"))
    }

    @Test
    fun `project rebuilds only quote entries that depend on changed source memo`() {
        val projector = ResolvedMemoQuoteProjector()
        val sourceMemoOne = buildMemo(
            identifier = "source-1",
            remoteId = "remote-source-1",
            content = "first source body",
        )
        val sourceMemoTwo = buildMemo(
            identifier = "source-2",
            remoteId = "remote-source-2",
            content = "second source body",
        )
        val quotedMemoOne = buildMemo(
            identifier = "quote-1",
            remoteId = "remote-quote-1",
            content = "quote one",
            quoteSourceKind = "remote",
            quoteSource = "remote-source-1",
        )
        val quotedMemoTwo = buildMemo(
            identifier = "quote-2",
            remoteId = "remote-quote-2",
            content = "quote two",
            quoteSourceKind = "remote",
            quoteSource = "remote-source-2",
        )

        val first = projector.project(listOf(sourceMemoOne, sourceMemoTwo, quotedMemoOne, quotedMemoTwo))
        val second = projector.project(
            listOf(
                sourceMemoOne.copy(content = "first source body updated"),
                sourceMemoTwo,
                quotedMemoOne,
                quotedMemoTwo,
            )
        )

        assertNotSame(first, second)
        assertNotSame(first.getValue("quote-1"), second.getValue("quote-1"))
        assertSame(first.getValue("quote-2"), second.getValue("quote-2"))
        assertEquals(
            "first source body updated",
            second.getValue("quote-1").preview?.previewText,
        )
    }

    private fun buildMemo(
        identifier: String,
        remoteId: String,
        content: String,
        quoteSourceKind: String? = null,
        quoteSource: String? = null,
    ): MemoEntity {
        val timestamp = Instant.parse("2026-04-02T00:00:00Z")
        return MemoEntity(
            identifier = identifier,
            remoteId = remoteId,
            accountKey = "account",
            content = content,
            date = timestamp,
            visibility = MemoVisibility.PRIVATE,
            pinned = false,
            archived = false,
            quoteSourceKind = quoteSourceKind,
            quoteSource = quoteSource,
            needsSync = false,
            isDeleted = false,
            lastModified = timestamp,
            lastSyncedAt = timestamp,
        ).also { memo ->
            memo.tags = emptyList()
            memo.resources = emptyList()
        }
    }
}
