package site.lcyk.keer.util

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import site.lcyk.keer.data.service.SyncTrigger

internal enum class ForegroundSyncSchedulerPhase {
    IDLE,
    FAST_WAITING,
    FAST_RUNNING,
    IDLE_WAITING,
    IDLE_RUNNING,
}

internal data class ForegroundSyncSchedulerState(
    val requestVersion: Long = 0L,
    val trigger: SyncTrigger? = null,
    val phase: ForegroundSyncSchedulerPhase = ForegroundSyncSchedulerPhase.IDLE,
)

internal class ForegroundSyncScheduler(
    private val scope: CoroutineScope,
    private val awaitFastLaneStart: suspend () -> Unit,
    private val runFastLaneSync: suspend (SyncTrigger) -> Unit,
    private val runIdleLaneSync: suspend (SyncTrigger) -> Unit,
    private val isUiBusy: () -> Boolean = { false },
    private val fastLaneDelayMillis: Long = FAST_LANE_DEFER_MILLIS,
    private val idleLaneDelayMillis: Long = IDLE_LANE_DEFER_MILLIS,
    private val fastLaneMaxBusyWaitMillis: Long = FAST_LANE_MAX_BUSY_WAIT_MILLIS,
    private val busyPollIntervalMillis: Long = FAST_LANE_BUSY_POLL_INTERVAL_MILLIS,
) {
    private var suppressNextResume = false
    private var requestVersion = 0L
    private var pipelineJob: Job? = null

    private val _state = MutableStateFlow(ForegroundSyncSchedulerState())
    val state: StateFlow<ForegroundSyncSchedulerState> = _state.asStateFlow()

    fun request(trigger: SyncTrigger) {
        if (trigger == SyncTrigger.APP_FOREGROUND && suppressNextResume) {
            suppressNextResume = false
            return
        }
        if (trigger == SyncTrigger.APP_START) {
            suppressNextResume = true
        }

        val nextVersion = requestVersion + 1L
        requestVersion = nextVersion
        pipelineJob?.cancel()
        pipelineJob = scope.launch {
            runPipeline(
                version = nextVersion,
                trigger = trigger,
            )
        }
    }

    fun cancelPending() {
        pipelineJob?.cancel()
        pipelineJob = null
        _state.value = ForegroundSyncSchedulerState(
            requestVersion = requestVersion,
            trigger = null,
            phase = ForegroundSyncSchedulerPhase.IDLE,
        )
    }

    private suspend fun runPipeline(
        version: Long,
        trigger: SyncTrigger,
    ) {
        try {
            setState(version, trigger, ForegroundSyncSchedulerPhase.FAST_WAITING)
            awaitFastLaneStart()
            if (fastLaneDelayMillis > 0L) {
                delay(fastLaneDelayMillis)
            }
            waitUntilUiNotBusyOrTimeout(version)
            ensureLatest(version)

            setState(version, trigger, ForegroundSyncSchedulerPhase.FAST_RUNNING)
            runFastLaneSync(trigger)
            ensureLatest(version)

            setState(version, trigger, ForegroundSyncSchedulerPhase.IDLE_WAITING)
            if (idleLaneDelayMillis > 0L) {
                delay(idleLaneDelayMillis)
            }
            ensureLatest(version)

            setState(version, trigger, ForegroundSyncSchedulerPhase.IDLE_RUNNING)
            runIdleLaneSync(trigger)
            ensureLatest(version)
        } catch (_: CancellationException) {
            // The latest request always wins. Older pipeline cancellations are expected.
        } finally {
            if (version == requestVersion) {
                setState(version, null, ForegroundSyncSchedulerPhase.IDLE)
            }
        }
    }

    private fun setState(
        version: Long,
        trigger: SyncTrigger?,
        phase: ForegroundSyncSchedulerPhase,
    ) {
        _state.value = ForegroundSyncSchedulerState(
            requestVersion = version,
            trigger = trigger,
            phase = phase,
        )
    }

    private fun ensureLatest(version: Long) {
        if (version != requestVersion) {
            throw CancellationException("Foreground sync pipeline superseded by newer request")
        }
    }

    private suspend fun waitUntilUiNotBusyOrTimeout(version: Long) {
        val maxWaitMillis = fastLaneMaxBusyWaitMillis.coerceAtLeast(0L)
        if (maxWaitMillis == 0L) {
            return
        }
        val pollIntervalMillis = busyPollIntervalMillis.coerceAtLeast(FAST_LANE_BUSY_POLL_MIN_MILLIS)
        var remainingWaitMillis = maxWaitMillis
        while (isUiBusy()) {
            ensureLatest(version)
            if (remainingWaitMillis <= 0L) {
                return
            }
            val waitMillis = minOf(pollIntervalMillis, remainingWaitMillis)
            delay(waitMillis)
            remainingWaitMillis -= waitMillis
        }
    }
}

internal const val FAST_LANE_DEFER_MILLIS = 160L
internal const val IDLE_LANE_DEFER_MILLIS = 1_200L
internal const val FAST_LANE_MAX_BUSY_WAIT_MILLIS = 300L
internal const val FAST_LANE_BUSY_POLL_INTERVAL_MILLIS = 16L
private const val FAST_LANE_BUSY_POLL_MIN_MILLIS = 8L
