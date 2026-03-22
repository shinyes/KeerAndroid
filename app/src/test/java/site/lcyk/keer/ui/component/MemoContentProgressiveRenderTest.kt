package site.lcyk.keer.ui.component

import org.junit.Assert.assertEquals
import org.junit.Test

class MemoContentProgressiveRenderTest {

    @Test
    fun computeProgressiveVisibleMediaCount_returnsAllWhenNotPreviewMode() {
        val count = computeProgressiveVisibleMediaCount(
            totalMediaCount = 20,
            previewMode = false,
            progressiveMediaEnabled = true,
            uiFrozen = true,
            settlePhaseReady = false,
        )

        assertEquals(20, count)
    }

    @Test
    fun computeProgressiveVisibleMediaCount_returnsAllWhenProgressiveDisabled() {
        val count = computeProgressiveVisibleMediaCount(
            totalMediaCount = 20,
            previewMode = true,
            progressiveMediaEnabled = false,
            uiFrozen = true,
            settlePhaseReady = false,
        )

        assertEquals(20, count)
    }

    @Test
    fun computeProgressiveVisibleMediaCount_usesScrollPhaseLimitWhenFrozen() {
        val count = computeProgressiveVisibleMediaCount(
            totalMediaCount = 20,
            previewMode = true,
            progressiveMediaEnabled = true,
            uiFrozen = true,
            settlePhaseReady = false,
        )

        assertEquals(SCROLL_PHASE_MEDIA_LIMIT, count)
    }

    @Test
    fun computeProgressiveVisibleMediaCount_usesSettlePhaseLimitWhenUnfrozenButNotReady() {
        val count = computeProgressiveVisibleMediaCount(
            totalMediaCount = 20,
            previewMode = true,
            progressiveMediaEnabled = true,
            uiFrozen = false,
            settlePhaseReady = false,
        )

        assertEquals(SETTLE_PHASE_MEDIA_LIMIT, count)
    }

    @Test
    fun computeProgressiveVisibleMediaCount_returnsAllAfterSettlePhaseReady() {
        val count = computeProgressiveVisibleMediaCount(
            totalMediaCount = 20,
            previewMode = true,
            progressiveMediaEnabled = true,
            uiFrozen = false,
            settlePhaseReady = true,
        )

        assertEquals(20, count)
    }

    @Test
    fun computeProgressiveVisibleMediaCount_clampsToTotalCountWhenSmallList() {
        val count = computeProgressiveVisibleMediaCount(
            totalMediaCount = 4,
            previewMode = true,
            progressiveMediaEnabled = true,
            uiFrozen = true,
            settlePhaseReady = false,
        )

        assertEquals(4, count)
    }
}
