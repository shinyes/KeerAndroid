package site.lcyk.keer.data.service

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import site.lcyk.keer.data.model.Account
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean

@Singleton
class StreamSyncSessionManager @Inject constructor(
    private val accountService: AccountService,
    private val syncCoordinator: SyncCoordinator,
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
        try {
            syncCoordinator.runTailSessionLoop()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (throwable: Throwable) {
            Timber.w(throwable, "Tail stream sync loop terminated unexpectedly")
        }
    }
}
