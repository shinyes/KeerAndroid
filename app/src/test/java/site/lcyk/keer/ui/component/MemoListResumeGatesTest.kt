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
class MemoListResumeGatesTest {

    @Test
    fun scrollResumeGatesFlow_idleStagesPrefetchThenWarmup() = runTest {
        val source = MutableSharedFlow<Boolean>(extraBufferCapacity = 8)
        val observed = mutableListOf<ScrollResumeGates>()
        val job = backgroundScope.launch {
            source.scrollResumeGatesFlow(
                prefetchResumeDelayMillis = 100L,
                warmupResumeDelayMillis = 220L,
            ).collect { gates ->
                observed += gates
            }
        }
        runCurrent()

        source.emit(false)
        advanceTimeBy(100L)
        runCurrent()
        advanceTimeBy(120L)
        runCurrent()

        assertEquals(
            listOf(
                ScrollResumeGates(prefetchAllowed = false, warmupAllowed = false),
                ScrollResumeGates(prefetchAllowed = true, warmupAllowed = false),
                ScrollResumeGates(prefetchAllowed = true, warmupAllowed = true),
            ),
            observed,
        )
        job.cancel()
    }

    @Test
    fun scrollResumeGatesFlow_quickRestartCancelsIdleResume() = runTest {
        val source = MutableSharedFlow<Boolean>(extraBufferCapacity = 8)
        val observed = mutableListOf<ScrollResumeGates>()
        val job = backgroundScope.launch {
            source.scrollResumeGatesFlow(
                prefetchResumeDelayMillis = 120L,
                warmupResumeDelayMillis = 240L,
            ).collect { gates ->
                observed += gates
            }
        }
        runCurrent()

        source.emit(false)
        advanceTimeBy(80L)
        runCurrent()
        source.emit(true)
        runCurrent()
        advanceTimeBy(300L)
        runCurrent()

        assertEquals(
            listOf(
                ScrollResumeGates(prefetchAllowed = false, warmupAllowed = false),
            ),
            observed,
        )
        job.cancel()
    }
}
