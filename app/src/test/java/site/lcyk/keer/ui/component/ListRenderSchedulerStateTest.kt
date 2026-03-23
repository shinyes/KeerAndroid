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
class ListRenderSchedulerStateTest {

    @Test
    fun listRenderSchedulerStateFlow_scrollStopCommitsVisibleWithin100ms() = runTest {
        val source = MutableSharedFlow<ListRenderSchedulerInput>(extraBufferCapacity = 8)
        val observed = mutableListOf<ListRenderSchedulerState>()
        val job = backgroundScope.launch {
            source
                .listRenderSchedulerStateFlow(
                    visibleCommitDelayMillis = 100L,
                    prefetchResumeDelayMillis = 120L,
                    warmupResumeDelayMillis = 260L,
                    frameBudgetGapMillis = 16L,
                )
                .collect { observed += it }
        }
        runCurrent()

        source.emit(ListRenderSchedulerInput(scopeFrozen = false, scrolling = true))
        runCurrent()
        source.emit(ListRenderSchedulerInput(scopeFrozen = false, scrolling = false))
        advanceTimeBy(99L)
        runCurrent()
        advanceTimeBy(1L)
        runCurrent()

        assertEquals(
            listOf(
                ListRenderSchedulerState(
                    uiFrozen = false,
                    prefetchPaused = true,
                    warmupEnabled = false,
                ),
                ListRenderSchedulerState(
                    uiFrozen = true,
                    prefetchPaused = true,
                    warmupEnabled = false,
                ),
                ListRenderSchedulerState(
                    uiFrozen = false,
                    prefetchPaused = true,
                    warmupEnabled = false,
                ),
            ),
            observed,
        )
        job.cancel()
    }

    @Test
    fun listRenderSchedulerStateFlow_scopeFrozenKeepsPipelinePaused() = runTest {
        val source = MutableSharedFlow<ListRenderSchedulerInput>(extraBufferCapacity = 8)
        val observed = mutableListOf<ListRenderSchedulerState>()
        val job = backgroundScope.launch {
            source
                .listRenderSchedulerStateFlow(
                    visibleCommitDelayMillis = 100L,
                    prefetchResumeDelayMillis = 120L,
                    warmupResumeDelayMillis = 260L,
                    frameBudgetGapMillis = 16L,
                )
                .collect { observed += it }
        }
        runCurrent()

        source.emit(ListRenderSchedulerInput(scopeFrozen = true, scrolling = false))
        advanceTimeBy(400L)
        runCurrent()

        assertEquals(
            listOf(
                ListRenderSchedulerState(
                    uiFrozen = false,
                    prefetchPaused = true,
                    warmupEnabled = false,
                ),
                ListRenderSchedulerState(
                    uiFrozen = true,
                    prefetchPaused = true,
                    warmupEnabled = false,
                ),
            ),
            observed,
        )
        job.cancel()
    }
}
