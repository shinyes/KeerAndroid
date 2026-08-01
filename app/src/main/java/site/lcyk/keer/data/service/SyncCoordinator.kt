package site.lcyk.keer.data.service
import com.skydoves.sandwich.ApiResponse
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import site.lcyk.keer.data.model.Account
import site.lcyk.keer.data.model.SyncDomain
import site.lcyk.keer.data.model.SyncStatus
import site.lcyk.keer.ext.getErrorMessage
import timber.log.Timber

@Singleton
class SyncCoordinator @Inject constructor(
    private val accountService: AccountService,
    private val pendingSyncWorkInspector: PendingSyncWorkInspector,
    private val pullSyncEngine: PullSyncEngine,
) {
    private data class SyncRequest(
        val force: Boolean,
        val trigger: SyncTrigger,
        val domains: Set<SyncDomain>,
        val groupId: String?,
        val bypassCoalesce: Boolean,
    )

    private val syncScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // 维护"账号激活时保持 tail 会话"的独立作用域（曾由 StreamSyncSessionManager 承担）。
    private val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var activeSessionJob: Job? = null
    private val syncMutex = Mutex()
    private val pendingRequestMutex = Mutex()
    private val tailSessionMutex = Mutex()
    private var pendingRequest: SyncRequest? = null
    private var activeTailAttempt: kotlinx.coroutines.Deferred<ApiResponse<Unit>>? = null
    private val requestSignal = Channel<Unit>(capacity = Channel.CONFLATED)
    private val _syncStatus = MutableStateFlow(SyncStatus())
    val syncStatus: StateFlow<SyncStatus> = _syncStatus.asStateFlow()

    @Volatile
    private var lastSyncAttemptTime = 0L
    @Volatile
    private var backoffUntilTime = 0L
    @Volatile
    private var consecutiveFailureCount = 0
    @Volatile
    private var syncing = false
    @Volatile
    private var activeDomains = emptySet<SyncDomain>()
    @Volatile
    private var domainErrorMessage: String? = null
    @Volatile
    private var repositoryStatusSnapshot = SyncStatus()
    @Volatile
    private var foregroundSyncRunning = false

    init {
        syncScope.launch {
            processSyncRequests()
        }
        syncScope.launch {
            accountService.currentAccount.collectLatest {
                accountService.getRepository().syncStatus.collect { repositoryStatus ->
                    repositoryStatusSnapshot = repositoryStatus
                    publishStatus()
                }
            }
        }
    }

    fun startStreamSessions() {
        sessionScope.launch {
            accountService.currentAccount.collectLatest { account ->
                activeSessionJob?.cancel()
                activeSessionJob = null
                if (account !is Account.KeerV2) {
                    return@collectLatest
                }
                activeSessionJob = launch {
                    try {
                        runTailSessionLoop()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (throwable: Throwable) {
                        Timber.w(throwable, "Tail stream sync loop terminated unexpectedly")
                    }
                }
            }
        }
    }

    fun requestSync(
        trigger: SyncTrigger = SyncTrigger.AUTO,
        force: Boolean = false,
        domains: Set<SyncDomain> = FULL_DOMAINS,
        groupId: String? = null,
        bypassCoalesce: Boolean = false,
    ) {
        if (domains.isEmpty()) {
            return
        }
        val normalizedRequest = SyncRequest(
            force = force,
            trigger = trigger,
            domains = domains,
            groupId = groupId?.trim()?.takeIf(String::isNotBlank),
            bypassCoalesce = bypassCoalesce,
        )
        syncScope.launch {
            pendingRequestMutex.withLock {
                pendingRequest = pendingRequest?.mergeWith(normalizedRequest) ?: normalizedRequest
            }
            requestSignal.trySend(Unit)
        }
    }

    suspend fun sync(
        force: Boolean,
        trigger: SyncTrigger = if (force) SyncTrigger.MANUAL else SyncTrigger.AUTO,
        domains: Set<SyncDomain> = FULL_DOMAINS,
        groupId: String? = null,
        bypassCoalesce: Boolean = false,
    ): ApiResponse<Unit> = withContext(Dispatchers.IO) {
        syncMutex.withLock {
            foregroundSyncRunning = true
            try {
                preemptTailSessionIfActive()
                if (domains.isEmpty()) {
                    return@withLock ApiResponse.Success(Unit)
                }

                val now = System.currentTimeMillis()
                val hasPendingWork = pendingSyncWorkInspector.hasPendingWork(repositoryStatusSnapshot)
                if (SyncTriggerPolicy.shouldSkipSync(
                        force = force,
                        trigger = trigger,
                        hasPendingWork = hasPendingWork,
                        nowMillis = now,
                        lastSyncAttemptMillis = lastSyncAttemptTime,
                        idleSyncIntervalMillis = AUTO_SYNC_INTERVAL_MILLIS,
                        pendingCoalesceMillis = PENDING_SYNC_COALESCE_MILLIS,
                        foregroundCoalesceMillis = FOREGROUND_SYNC_COALESCE_MILLIS,
                        backoffUntilMillis = backoffUntilTime,
                        bypassCoalesce = bypassCoalesce,
                    )
                ) {
                    return@withLock ApiResponse.Success(Unit)
                }

                lastSyncAttemptTime = now
                syncing = true
                activeDomains = domains
                domainErrorMessage = null
                publishStatus()

                val normalizedGroupId = groupId?.trim()?.takeIf(String::isNotBlank)
                val result = pullSyncEngine.run(
                    domains = domains,
                    groupId = normalizedGroupId,
                    trigger = trigger,
                )

                if (result is ApiResponse.Success) {
                    consecutiveFailureCount = 0
                    backoffUntilTime = 0L
                    domainErrorMessage = null
                } else {
                    domainErrorMessage = result.getErrorMessage()
                    if (!force) {
                        consecutiveFailureCount += 1
                        backoffUntilTime = SyncTriggerPolicy.calculateBackoffUntil(
                            nowMillis = now,
                            consecutiveFailures = consecutiveFailureCount,
                            baseBackoffMillis = FAILURE_BACKOFF_BASE_MILLIS,
                            maxBackoffMillis = FAILURE_BACKOFF_MAX_MILLIS
                        )
                    }
                }

                syncing = false
                activeDomains = emptySet()
                publishStatus()
                result
            } finally {
                foregroundSyncRunning = false
            }
        }
    }

    suspend fun runTailSessionLoop() = supervisorScope {
        var reconnectDelay = STREAM_RECONNECT_BASE_DELAY_MILLIS
        while (currentCoroutineContext().isActive) {
            awaitForegroundSyncIdle()
            val attempt = async(start = CoroutineStart.UNDISPATCHED) {
                pullSyncEngine.runUnifiedTailSession()
            }
            tailSessionMutex.withLock {
                activeTailAttempt = attempt
            }
            val result = try {
                attempt.await()
            } catch (cancelled: CancellationException) {
                if (!currentCoroutineContext().isActive) {
                    throw cancelled
                }
                if (cancelled is TailSessionPreemptedCancellation || foregroundSyncRunning) {
                    reconnectDelay = STREAM_RECONNECT_BASE_DELAY_MILLIS
                    continue
                }
                throw cancelled
            } finally {
                tailSessionMutex.withLock {
                    if (activeTailAttempt === attempt) {
                        activeTailAttempt = null
                    }
                }
            }

            when (result) {
                is ApiResponse.Success -> {
                    reconnectDelay = STREAM_RECONNECT_BASE_DELAY_MILLIS
                    delay(STREAM_RECONNECT_SUCCESS_DELAY_MILLIS)
                }
                is ApiResponse.Failure.Error -> {
                    delay(reconnectDelay)
                    reconnectDelay = (reconnectDelay * 2).coerceAtMost(STREAM_RECONNECT_MAX_DELAY_MILLIS)
                }
                is ApiResponse.Failure.Exception -> {
                    val throwable = result.throwable
                    if (throwable is CancellationException) {
                        if (!currentCoroutineContext().isActive) {
                            throw throwable
                        }
                        if (throwable is TailSessionPreemptedCancellation || foregroundSyncRunning) {
                            reconnectDelay = STREAM_RECONNECT_BASE_DELAY_MILLIS
                            continue
                        }
                    }
                    delay(reconnectDelay)
                    reconnectDelay = (reconnectDelay * 2).coerceAtMost(STREAM_RECONNECT_MAX_DELAY_MILLIS)
                }
            }
        }
    }

    private suspend fun processSyncRequests() {
        for (ignored in requestSignal) {
            while (true) {
                val request = pendingRequestMutex.withLock {
                    pendingRequest.also {
                        pendingRequest = null
                    }
                } ?: break
                runCatching {
                    sync(
                        force = request.force,
                        trigger = request.trigger,
                        domains = request.domains,
                        groupId = request.groupId,
                        bypassCoalesce = request.bypassCoalesce,
                    )
                }
            }
        }
    }

    private fun SyncRequest.mergeWith(other: SyncRequest): SyncRequest {
        val mergedDomains = domains + other.domains
        return SyncRequest(
            force = force || other.force,
            trigger = if (other.trigger.priority() > trigger.priority()) other.trigger else trigger,
            domains = mergedDomains,
            groupId = mergeGroupId(
                currentGroupId = groupId,
                currentDomains = domains,
                incomingGroupId = other.groupId,
                incomingDomains = other.domains,
                mergedDomains = mergedDomains,
            ),
            bypassCoalesce = bypassCoalesce || other.bypassCoalesce,
        )
    }

    private fun mergeGroupId(
        currentGroupId: String?,
        currentDomains: Set<SyncDomain>,
        incomingGroupId: String?,
        incomingDomains: Set<SyncDomain>,
        mergedDomains: Set<SyncDomain>,
    ): String? {
        if (SyncDomain.GROUPS !in mergedDomains) {
            return null
        }
        val normalizedCurrentGroupId = currentGroupId.takeIf { SyncDomain.GROUPS in currentDomains }
        val normalizedIncomingGroupId = incomingGroupId.takeIf { SyncDomain.GROUPS in incomingDomains }
        return when {
            SyncDomain.GROUPS in currentDomains && normalizedCurrentGroupId == null -> null
            SyncDomain.GROUPS in incomingDomains && normalizedIncomingGroupId == null -> null
            normalizedCurrentGroupId == null -> normalizedIncomingGroupId
            normalizedIncomingGroupId == null -> normalizedCurrentGroupId
            normalizedCurrentGroupId == normalizedIncomingGroupId -> normalizedCurrentGroupId
            else -> null
        }
    }

    private suspend fun preemptTailSessionIfActive() {
        val attempt = tailSessionMutex.withLock { activeTailAttempt } ?: return
        if (attempt.isCompleted) {
            return
        }
        attempt.cancel(TailSessionPreemptedCancellation())
        withTimeoutOrNull(TAIL_PREEMPT_WAIT_MILLIS) {
            attempt.join()
        }
    }

    private suspend fun awaitForegroundSyncIdle() {
        while (foregroundSyncRunning && currentCoroutineContext().isActive) {
            delay(TAIL_PREEMPT_POLL_INTERVAL_MILLIS)
        }
    }

    private fun publishStatus() {
        val repositoryStatus = repositoryStatusSnapshot
        _syncStatus.value = SyncStatus(
            syncing = syncing || repositoryStatus.syncing,
            activeDomains = activeDomains,
            unsyncedCount = repositoryStatus.unsyncedCount,
            errorMessage = domainErrorMessage ?: repositoryStatus.errorMessage,
            uploadedBytes = repositoryStatus.uploadedBytes,
            totalBytes = repositoryStatus.totalBytes,
            uploadedFiles = repositoryStatus.uploadedFiles,
            totalFiles = repositoryStatus.totalFiles,
        )
    }

    companion object {
        val FULL_DOMAINS: Set<SyncDomain> = SyncDomain.entries.toSet()

        private const val AUTO_SYNC_INTERVAL_MILLIS = 120_000L
        private const val FOREGROUND_SYNC_COALESCE_MILLIS = 45_000L
        private const val PENDING_SYNC_COALESCE_MILLIS = 1_500L
        private const val FAILURE_BACKOFF_BASE_MILLIS = 5_000L
        private const val FAILURE_BACKOFF_MAX_MILLIS = 120_000L
        private const val STREAM_RECONNECT_BASE_DELAY_MILLIS = 1_000L
        private const val STREAM_RECONNECT_SUCCESS_DELAY_MILLIS = 3_000L
        private const val STREAM_RECONNECT_MAX_DELAY_MILLIS = 30_000L
        private const val TAIL_PREEMPT_WAIT_MILLIS = 1_500L
        private const val TAIL_PREEMPT_POLL_INTERVAL_MILLIS = 50L
    }
}

private class TailSessionPreemptedCancellation : CancellationException("Tail sync preempted by foreground sync")
