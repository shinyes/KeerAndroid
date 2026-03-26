package site.lcyk.keer.data.service

import com.skydoves.sandwich.ApiResponse
import com.skydoves.sandwich.retrofit.statusCode
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import site.lcyk.keer.data.model.Account
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean

@Singleton
class StreamSyncSessionManager @Inject constructor(
    private val accountService: AccountService,
    private val pullSyncEngine: PullSyncEngine,
) {
    private val started = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var activeSessionJob: Job? = null

    fun start() {
        if (!started.compareAndSet(false, true)) {
            return
        }
        scope.launch {
            accountService.currentAccount.collectLatest { account ->
                activeSessionJob?.cancel()
                activeSessionJob = null
                if (account !is Account.KeerV2) {
                    return@collectLatest
                }
                activeSessionJob = launch {
                    runSessionLoop()
                }
            }
        }
    }

    fun stop() {
        activeSessionJob?.cancel()
        scope.cancel()
        started.set(false)
    }

    private suspend fun runSessionLoop() {
        var reconnectDelay = STREAM_RECONNECT_BASE_DELAY_MILLIS
        while (currentCoroutineContext().isActive) {
            when (val result = pullSyncEngine.runUnifiedTailSession()) {
                is ApiResponse.Success -> {
                    reconnectDelay = STREAM_RECONNECT_BASE_DELAY_MILLIS
                    delay(STREAM_RECONNECT_SUCCESS_DELAY_MILLIS)
                }
                is ApiResponse.Failure.Error -> {
                    Timber.w("Tail stream sync failed: HTTP %s", result.statusCode.code)
                    delay(reconnectDelay)
                    reconnectDelay = (reconnectDelay * 2).coerceAtMost(STREAM_RECONNECT_MAX_DELAY_MILLIS)
                }
                is ApiResponse.Failure.Exception -> {
                    if (result.throwable is CancellationException) {
                        throw result.throwable
                    }
                    Timber.w(result.throwable, "Tail stream sync exception")
                    delay(reconnectDelay)
                    reconnectDelay = (reconnectDelay * 2).coerceAtMost(STREAM_RECONNECT_MAX_DELAY_MILLIS)
                }
            }
        }
    }

    private companion object {
        private const val STREAM_RECONNECT_BASE_DELAY_MILLIS = 1_000L
        private const val STREAM_RECONNECT_SUCCESS_DELAY_MILLIS = 3_000L
        private const val STREAM_RECONNECT_MAX_DELAY_MILLIS = 30_000L
    }
}
