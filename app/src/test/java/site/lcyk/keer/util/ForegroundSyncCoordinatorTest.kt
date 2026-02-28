package site.lcyk.keer.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundSyncCoordinatorTest {
    @Test
    fun requestResumeSync_skipsFirstResumeAfterAppStartSync() {
        val coordinator = ForegroundSyncCoordinator()

        assertTrue(coordinator.requestAppStartSync())
        coordinator.completeSync()

        assertFalse(coordinator.requestResumeSync())
        assertTrue(coordinator.requestResumeSync())
    }

    @Test
    fun requestResumeSync_blocksWhenPreviousForegroundSyncIsInFlight() {
        val coordinator = ForegroundSyncCoordinator()

        assertTrue(coordinator.requestResumeSync())
        assertFalse(coordinator.requestResumeSync())
        coordinator.completeSync()
        assertTrue(coordinator.requestResumeSync())
    }
}
