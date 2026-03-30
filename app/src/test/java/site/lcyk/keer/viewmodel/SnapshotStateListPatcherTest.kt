package site.lcyk.keer.viewmodel

import androidx.compose.runtime.mutableStateListOf
import junit.framework.TestCase.assertEquals
import org.junit.Test

class SnapshotStateListPatcherTest {

    @Test
    fun `patch by key updates items in place when order is stable`() {
        val items = mutableStateListOf(
            TestItem("a", "old-a"),
            TestItem("b", "old-b"),
        )

        items.patchByKey(
            target = listOf(
                TestItem("a", "new-a"),
                TestItem("b", "old-b"),
            ),
            keySelector = TestItem::id,
        )

        assertEquals(
            listOf(
                TestItem("a", "new-a"),
                TestItem("b", "old-b"),
            ),
            items.toList(),
        )
    }

    @Test
    fun `patch by key replaces list when order changes`() {
        val items = mutableStateListOf(
            TestItem("a", "first"),
            TestItem("b", "second"),
        )

        items.patchByKey(
            target = listOf(
                TestItem("b", "second"),
                TestItem("a", "first"),
            ),
            keySelector = TestItem::id,
        )

        assertEquals(
            listOf(
                TestItem("b", "second"),
                TestItem("a", "first"),
            ),
            items.toList(),
        )
    }

    private data class TestItem(
        val id: String,
        val value: String,
    )
}
