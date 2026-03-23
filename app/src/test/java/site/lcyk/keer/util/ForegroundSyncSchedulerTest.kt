package site.lcyk.keer.util

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import site.lcyk.keer.data.service.SyncTrigger

@OptIn(ExperimentalCoroutinesApi::class)
class ForegroundSyncSchedulerTest {

    @Test
    fun request_runsFastLaneThenIdleLaneInOrder() = runTest {
        val events = mutableListOf<String>()
        val scheduler = ForegroundSyncScheduler(
            scope = backgroundScope,
            awaitFastLaneStart = {},
            runFastLaneSync = { trigger -> events += "fast:$trigger" },
            runIdleLaneSync = { trigger -> events += "idle:$trigger" },
            fastLaneDelayMillis = 20L,
            idleLaneDelayMillis = 40L,
        )

        scheduler.request(SyncTrigger.APP_START)
        advanceTimeBy(100L)

        assertEquals(
            listOf(
                "fast:${SyncTrigger.APP_START}",
                "idle:${SyncTrigger.APP_START}",
            ),
            events
        )
    }

    @Test
    fun request_skipsFirstResumeAfterAppStart() = runTest {
        val events = mutableListOf<String>()
        val scheduler = ForegroundSyncScheduler(
            scope = backgroundScope,
            awaitFastLaneStart = {},
            runFastLaneSync = { trigger -> events += "fast:$trigger" },
            runIdleLaneSync = { trigger -> events += "idle:$trigger" },
            fastLaneDelayMillis = 0L,
            idleLaneDelayMillis = 0L,
        )

        scheduler.request(SyncTrigger.APP_START)
        runCurrent()
        advanceTimeBy(1L)
        scheduler.request(SyncTrigger.APP_FOREGROUND)
        scheduler.request(SyncTrigger.APP_FOREGROUND)
        advanceTimeBy(1L)

        assertEquals(
            listOf(
                "fast:${SyncTrigger.APP_START}",
                "idle:${SyncTrigger.APP_START}",
                "fast:${SyncTrigger.APP_FOREGROUND}",
                "idle:${SyncTrigger.APP_FOREGROUND}",
            ),
            events
        )
    }

    @Test
    fun request_newerRequestCancelsOlderPipeline() = runTest {
        val events = mutableListOf<String>()
        val scheduler = ForegroundSyncScheduler(
            scope = backgroundScope,
            awaitFastLaneStart = {},
            runFastLaneSync = { trigger -> events += "fast:$trigger" },
            runIdleLaneSync = { trigger -> events += "idle:$trigger" },
            fastLaneDelayMillis = 120L,
            idleLaneDelayMillis = 80L,
        )

        scheduler.request(SyncTrigger.APP_FOREGROUND)
        advanceTimeBy(40L)
        scheduler.request(SyncTrigger.APP_FOREGROUND)
        advanceTimeBy(260L)

        assertEquals(
            listOf(
                "fast:${SyncTrigger.APP_FOREGROUND}",
                "idle:${SyncTrigger.APP_FOREGROUND}",
            ),
            events
        )
    }

    @Test
    fun request_fastLaneWaitsUntilUiNotBusyBeforeTimeout() = runTest {
        val events = mutableListOf<String>()
        var busy = true
        val scheduler = ForegroundSyncScheduler(
            scope = backgroundScope,
            awaitFastLaneStart = {},
            runFastLaneSync = { trigger -> events += "fast:$trigger" },
            runIdleLaneSync = { trigger -> events += "idle:$trigger" },
            isUiBusy = { busy },
            fastLaneDelayMillis = 0L,
            idleLaneDelayMillis = 0L,
            fastLaneMaxBusyWaitMillis = 300L,
            busyPollIntervalMillis = 20L,
        )

        scheduler.request(SyncTrigger.APP_FOREGROUND)
        advanceTimeBy(80L)
        runCurrent()
        assertEquals(emptyList<String>(), events)

        busy = false
        advanceTimeBy(20L)
        runCurrent()

        assertEquals(
            listOf(
                "fast:${SyncTrigger.APP_FOREGROUND}",
                "idle:${SyncTrigger.APP_FOREGROUND}",
            ),
            events
        )
    }

    @Test
    fun request_fastLaneRunsAfterBusyWaitTimeout() = runTest {
        val events = mutableListOf<String>()
        val scheduler = ForegroundSyncScheduler(
            scope = backgroundScope,
            awaitFastLaneStart = {},
            runFastLaneSync = { trigger -> events += "fast:$trigger" },
            runIdleLaneSync = { trigger -> events += "idle:$trigger" },
            isUiBusy = { true },
            fastLaneDelayMillis = 0L,
            idleLaneDelayMillis = 0L,
            fastLaneMaxBusyWaitMillis = 300L,
            busyPollIntervalMillis = 20L,
        )

        scheduler.request(SyncTrigger.APP_FOREGROUND)
        advanceTimeBy(299L)
        runCurrent()
        assertEquals(emptyList<String>(), events)

        advanceTimeBy(1L)
        runCurrent()
        assertEquals(
            listOf(
                "fast:${SyncTrigger.APP_FOREGROUND}",
                "idle:${SyncTrigger.APP_FOREGROUND}",
            ),
            events
        )
    }
}
