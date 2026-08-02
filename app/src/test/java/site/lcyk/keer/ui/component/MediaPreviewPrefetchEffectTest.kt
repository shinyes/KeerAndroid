package site.lcyk.keer.ui.component

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MediaPreviewPrefetchEffectTest {

    @Test
    fun resolveInitialPrefetchVisibleIndices_returnsImmediateWhenAvailable() = runTest {
        val resolved = resolveInitialPrefetchVisibleIndices(
            immediateVisibleIndices = listOf(2, 3),
            visibleIndicesFlow = flowOf(emptyList(), listOf(1, 2)),
        )

        assertEquals(listOf(2, 3), resolved)
    }

    @Test
    fun resolveInitialPrefetchVisibleIndices_waitsForFirstNonEmptyEmission() = runTest {
        val emissions = MutableSharedFlow<List<Int>>(extraBufferCapacity = 4)

        val deferred = backgroundScope.launch {
            val resolved = resolveInitialPrefetchVisibleIndices(
                immediateVisibleIndices = emptyList(),
                visibleIndicesFlow = emissions,
            )
            assertEquals(listOf(5, 6), resolved)
        }
        runCurrent()

        emissions.emit(emptyList())
        runCurrent()
        emissions.emit(listOf(5, 6))
        runCurrent()

        deferred.join()
    }

    @Test
    fun resolveInitialPrefetchVisibleIndices_usesFallbackWhenImmediateIsEmpty() = runTest {
        val resolved = resolveInitialPrefetchVisibleIndices(
            immediateVisibleIndices = emptyList(),
            visibleIndicesFlow = flowOf(listOf(9, 10)),
            fallbackVisibleIndices = listOf(2, 3, 4),
        )

        assertEquals(listOf(2, 3, 4), resolved)
    }

    @Test
    fun collectPrefetchVisibleWindows_skipsPrefetchWhenPaused() = runTest {
        val emissions = MutableSharedFlow<List<Int>>(extraBufferCapacity = 4)
        val prefetched = mutableListOf<Pair<List<Int>, PrefetchWindow>>()

        val job = backgroundScope.launch {
            collectPrefetchVisibleWindows(
                visibleIndicesFlow = emissions,
                prefetchPaused = true,
            ) { indices, window ->
                prefetched += indices to window
            }
        }
        runCurrent()

        emissions.emit(listOf(1, 2))
        advanceTimeBy(PREFETCH_VISIBLE_DEBOUNCE_MS + 1L)
        runCurrent()

        assertTrue(prefetched.isEmpty())
        job.cancel()
    }

    @Test
    fun collectPrefetchVisibleWindows_conflatesToLatestWithoutCancellingInFlight() = runTest {
        val emissions = MutableSharedFlow<List<Int>>(extraBufferCapacity = 8)
        val completed = mutableListOf<List<Int>>()
        val cancellationCount = AtomicInteger(0)

        val job = backgroundScope.launch {
            collectPrefetchVisibleWindows(
                visibleIndicesFlow = emissions,
                prefetchPaused = false,
            ) { indices, _ ->
                try {
                    delay(200L)
                    completed += indices
                } catch (cancelled: CancellationException) {
                    cancellationCount.incrementAndGet()
                    throw cancelled
                }
            }
        }
        runCurrent()

        emissions.emit(listOf(1))
        advanceTimeBy(PREFETCH_VISIBLE_DEBOUNCE_MS + 1L)
        runCurrent()

        emissions.emit(listOf(2))
        advanceTimeBy(PREFETCH_VISIBLE_DEBOUNCE_MS + 1L)
        runCurrent()

        // 实现用 conflate（而非 collectLatest）：不会取消 in-flight 预取，仅保留最新窗口。
        // 因此第一个窗口会正常完成，最新窗口随后处理。
        advanceTimeBy(220L)
        runCurrent()
        assertEquals(listOf(listOf(1)), completed)
        assertEquals(0, cancellationCount.get())

        advanceTimeBy(220L)
        runCurrent()
        assertEquals(listOf(listOf(1), listOf(2)), completed)
        assertEquals(0, cancellationCount.get())
        job.cancel()
    }

    @Test
    fun resolvePrefetchDirection_andWindow_matchDirectionalProfiles() {
        assertEquals(
            PrefetchDirection.IDLE,
            resolvePrefetchDirection(previousAnchorIndex = null, currentAnchorIndex = 4)
        )
        assertEquals(
            PrefetchDirection.FORWARD,
            resolvePrefetchDirection(previousAnchorIndex = 3, currentAnchorIndex = 5)
        )
        assertEquals(
            PrefetchDirection.BACKWARD,
            resolvePrefetchDirection(previousAnchorIndex = 5, currentAnchorIndex = 3)
        )

        assertEquals(
            PrefetchWindow(
                ahead = PREFETCH_FORWARD_WINDOW_AHEAD,
                behind = PREFETCH_FORWARD_WINDOW_BEHIND,
            ),
            resolvePrefetchWindow(PrefetchDirection.FORWARD)
        )
        assertEquals(
            PrefetchWindow(
                ahead = PREFETCH_BACKWARD_WINDOW_AHEAD,
                behind = PREFETCH_BACKWARD_WINDOW_BEHIND,
            ),
            resolvePrefetchWindow(PrefetchDirection.BACKWARD)
        )
        assertEquals(
            PrefetchWindow(
                ahead = PREFETCH_IDLE_WINDOW_AHEAD,
                behind = PREFETCH_IDLE_WINDOW_BEHIND,
            ),
            resolvePrefetchWindow(PrefetchDirection.IDLE)
        )
    }

    @Test
    fun resolveFallbackPrefetchVisibleIndices_anchorsToCurrentIndex() {
        assertEquals(
            listOf(0, 1, 2, 3, 4),
            resolveFallbackPrefetchVisibleIndices(anchorIndex = 0, memoCount = 30),
        )
        assertEquals(
            listOf(27, 28, 29),
            resolveFallbackPrefetchVisibleIndices(anchorIndex = 27, memoCount = 30),
        )
        assertEquals(
            emptyList<Int>(),
            resolveFallbackPrefetchVisibleIndices(anchorIndex = 0, memoCount = 0),
        )
    }
}
