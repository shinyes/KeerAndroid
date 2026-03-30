package site.lcyk.keer.viewmodel

import junit.framework.TestCase.assertEquals
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class InteractionSnapshotStoreTest {

    @Test
    fun `when idle delay is zero, unfreeze commits latest state immediately`() = runTest {
        val store = InteractionSnapshotStore(
            scope = backgroundScope,
            initialState = 0,
            idleCommitDelayMillis = 0L,
        )

        store.setFrozen(true)
        store.updateLiveState(1)
        assertEquals(0, store.visibleState.value)

        store.setFrozen(false)

        assertEquals(1, store.visibleState.value)
    }

    @Test
    fun `when idle delay is positive, unfreeze commit respects delay`() = runTest {
        val store = InteractionSnapshotStore(
            scope = backgroundScope,
            initialState = 0,
            idleCommitDelayMillis = 50L,
        )

        store.setFrozen(true)
        store.updateLiveState(1)
        store.setFrozen(false)

        assertEquals(0, store.visibleState.value)
        advanceTimeBy(49)
        runCurrent()
        assertEquals(0, store.visibleState.value)

        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, store.visibleState.value)
    }

    @Test
    fun `restore snapshot publishes immediately and cancels pending delayed commit`() = runTest {
        val store = InteractionSnapshotStore(
            scope = backgroundScope,
            initialState = 0,
            idleCommitDelayMillis = 50L,
        )

        store.updateLiveState(1)
        advanceTimeBy(10)
        runCurrent()
        assertEquals(0, store.visibleState.value)

        store.restoreSnapshot(5)
        assertEquals(5, store.visibleState.value)

        advanceTimeBy(50)
        runCurrent()
        assertEquals(5, store.visibleState.value)
    }

    @Test
    fun `drawer projection store commits immediately after unfreeze`() = runTest {
        val store = DrawerProjectionStore(scope = backgroundScope)

        store.setFrozen(true)
        store.updateLiveState(DrawerUiState(tags = listOf("focus")))
        assertEquals(emptyList<String>(), store.visibleState.value.tags)

        store.setFrozen(false)
        runCurrent()

        assertEquals(listOf("focus"), store.visibleState.value.tags)
    }

    @Test
    fun `drawer projection store publishes live state with short debounce`() = runTest {
        val store = DrawerProjectionStore(scope = backgroundScope)

        store.updateLiveState(DrawerUiState(tags = listOf("focus")))
        assertEquals(emptyList<String>(), store.visibleState.value.tags)

        advanceTimeBy(15)
        runCurrent()
        assertEquals(emptyList<String>(), store.visibleState.value.tags)

        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf("focus"), store.visibleState.value.tags)
    }
}
