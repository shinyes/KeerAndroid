package site.lcyk.keer.ui.component

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MemoListFreezeTest {

    @Test
    fun delayedScrollFreezeFlow_shortScrollDoesNotEnterFrozen() = runTest {
        val source = MutableSharedFlow<Boolean>(extraBufferCapacity = 8)
        val observed = mutableListOf<Boolean>()
        val job = backgroundScope.launch {
            source
                .delayedScrollFreezeFlow(
                    startDelayMillis = UI_FREEZE_START_DELAY_MS,
                    releaseHoldMillis = UI_FREEZE_RELEASE_HOLD_MS,
                )
                .collect { value -> observed += value }
        }
        runCurrent()

        source.emit(true)
        advanceTimeBy(UI_FREEZE_START_DELAY_MS - 20L)
        runCurrent()
        source.emit(false)
        advanceTimeBy(UI_FREEZE_RELEASE_HOLD_MS + 20L)
        runCurrent()

        assertEquals(listOf(false), observed)
        job.cancel()
    }

    @Test
    fun delayedScrollFreezeFlow_longScrollFreezesThenReleasesAfterHold() = runTest {
        val source = MutableSharedFlow<Boolean>(extraBufferCapacity = 8)
        val observed = mutableListOf<Boolean>()
        val job = backgroundScope.launch {
            source
                .delayedScrollFreezeFlow(
                    startDelayMillis = UI_FREEZE_START_DELAY_MS,
                    releaseHoldMillis = UI_FREEZE_RELEASE_HOLD_MS,
                )
                .collect { value -> observed += value }
        }
        runCurrent()

        source.emit(true)
        advanceTimeBy(UI_FREEZE_START_DELAY_MS + 1L)
        runCurrent()
        source.emit(false)
        advanceTimeBy(UI_FREEZE_RELEASE_HOLD_MS + 1L)
        runCurrent()

        assertEquals(listOf(false, true, false), observed)
        job.cancel()
    }

    @Test
    fun delayedScrollFreezeFlow_repeatedQuickSwipesDoNotToggleBackAndForth() = runTest {
        val source = MutableSharedFlow<Boolean>(extraBufferCapacity = 8)
        val observed = mutableListOf<Boolean>()
        val job = backgroundScope.launch {
            source
                .delayedScrollFreezeFlow(
                    startDelayMillis = UI_FREEZE_START_DELAY_MS,
                    releaseHoldMillis = UI_FREEZE_RELEASE_HOLD_MS,
                )
                .collect { value -> observed += value }
        }
        runCurrent()

        source.emit(true)
        advanceTimeBy(UI_FREEZE_START_DELAY_MS + 1L)
        runCurrent()
        source.emit(false)
        advanceTimeBy(100L)
        runCurrent()
        source.emit(true)
        advanceTimeBy(UI_FREEZE_START_DELAY_MS + 1L)
        runCurrent()
        source.emit(false)
        advanceTimeBy(UI_FREEZE_RELEASE_HOLD_MS + 1L)
        runCurrent()

        assertEquals(listOf(false, true, false), observed)
        job.cancel()
    }
}
