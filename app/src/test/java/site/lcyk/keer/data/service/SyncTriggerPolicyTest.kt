package site.lcyk.keer.data.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncTriggerPolicyTest {
    @Test
    fun shouldSkipSync_returnsFalse_whenForceIsTrue() {
        val skip = SyncTriggerPolicy.shouldSkipSync(
            force = true,
            trigger = SyncTrigger.AUTO,
            hasPendingWork = false,
            nowMillis = 1_000L,
            lastSyncAttemptMillis = 900L,
            idleSyncIntervalMillis = 120_000L,
            pendingCoalesceMillis = 1_500L,
            foregroundCoalesceMillis = 3_000L,
            backoffUntilMillis = 0L
        )

        assertFalse(skip)
    }

    @Test
    fun shouldSkipSync_returnsTrue_whenForegroundTriggerWithinCoalesceWindow() {
        val skip = SyncTriggerPolicy.shouldSkipSync(
            force = false,
            trigger = SyncTrigger.APP_FOREGROUND,
            hasPendingWork = true,
            nowMillis = 10_000L,
            lastSyncAttemptMillis = 8_500L,
            idleSyncIntervalMillis = 120_000L,
            pendingCoalesceMillis = 1_500L,
            foregroundCoalesceMillis = 3_000L,
            backoffUntilMillis = 0L
        )

        assertTrue(skip)
    }

    @Test
    fun shouldSkipSync_returnsTrue_whenPendingWorkAndWithinPendingCoalesceWindow() {
        val skip = SyncTriggerPolicy.shouldSkipSync(
            force = false,
            trigger = SyncTrigger.MUTATION,
            hasPendingWork = true,
            nowMillis = 30_000L,
            lastSyncAttemptMillis = 29_000L,
            idleSyncIntervalMillis = 120_000L,
            pendingCoalesceMillis = 1_500L,
            foregroundCoalesceMillis = 3_000L,
            backoffUntilMillis = 0L
        )

        assertTrue(skip)
    }

    @Test
    fun shouldSkipSync_returnsTrue_whenNoPendingAndWithinIdleInterval() {
        val skip = SyncTriggerPolicy.shouldSkipSync(
            force = false,
            trigger = SyncTrigger.AUTO,
            hasPendingWork = false,
            nowMillis = 40_000L,
            lastSyncAttemptMillis = 10_000L,
            idleSyncIntervalMillis = 120_000L,
            pendingCoalesceMillis = 1_500L,
            foregroundCoalesceMillis = 3_000L,
            backoffUntilMillis = 0L
        )

        assertTrue(skip)
    }

    @Test
    fun shouldSkipSync_returnsTrue_whenBackoffWindowActive() {
        val skip = SyncTriggerPolicy.shouldSkipSync(
            force = false,
            trigger = SyncTrigger.MUTATION,
            hasPendingWork = true,
            nowMillis = 50_000L,
            lastSyncAttemptMillis = 10_000L,
            idleSyncIntervalMillis = 120_000L,
            pendingCoalesceMillis = 1_500L,
            foregroundCoalesceMillis = 3_000L,
            backoffUntilMillis = 60_000L
        )

        assertTrue(skip)
    }

    @Test
    fun shouldSkipSync_returnsFalse_whenNoPendingAndIdleIntervalElapsed() {
        val skip = SyncTriggerPolicy.shouldSkipSync(
            force = false,
            trigger = SyncTrigger.AUTO,
            hasPendingWork = false,
            nowMillis = 150_001L,
            lastSyncAttemptMillis = 30_000L,
            idleSyncIntervalMillis = 120_000L,
            pendingCoalesceMillis = 1_500L,
            foregroundCoalesceMillis = 3_000L,
            backoffUntilMillis = 0L
        )

        assertFalse(skip)
    }

    @Test
    fun calculateBackoffUntil_increasesExponentiallyAndCaps() {
        val first = SyncTriggerPolicy.calculateBackoffUntil(
            nowMillis = 1_000L,
            consecutiveFailures = 1,
            baseBackoffMillis = 5_000L,
            maxBackoffMillis = 120_000L
        )
        val fifth = SyncTriggerPolicy.calculateBackoffUntil(
            nowMillis = 1_000L,
            consecutiveFailures = 5,
            baseBackoffMillis = 5_000L,
            maxBackoffMillis = 120_000L
        )
        val tenth = SyncTriggerPolicy.calculateBackoffUntil(
            nowMillis = 1_000L,
            consecutiveFailures = 10,
            baseBackoffMillis = 5_000L,
            maxBackoffMillis = 120_000L
        )

        assertTrue(first > 1_000L)
        assertTrue(fifth > first)
        assertTrue(tenth <= 121_000L)
    }
}
