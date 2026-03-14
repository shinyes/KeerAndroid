package site.lcyk.keer.data.service
import com.skydoves.sandwich.ApiResponse
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import site.lcyk.keer.data.model.SyncDomain
import site.lcyk.keer.data.model.SyncStatus
import site.lcyk.keer.ext.getErrorMessage

@Singleton
class SyncCoordinator @Inject constructor(
    private val accountService: AccountService,
    private val pendingSyncWorkInspector: PendingSyncWorkInspector,
    private val profileSyncRunner: ProfileSyncRunner,
    private val usersSyncRunner: UsersSyncRunner,
    private val groupsSyncRunner: GroupsSyncRunner,
    private val memosSyncRunner: MemosSyncRunner,
) {
    private val domainExecutionOrder = listOf(
        SyncDomain.PROFILE,
        SyncDomain.USERS,
        SyncDomain.GROUPS,
        SyncDomain.MEMOS,
    )

    private val domainRunners: Map<SyncDomain, suspend (String?) -> ApiResponse<Unit>> = mapOf(
        SyncDomain.PROFILE to { profileSyncRunner.sync() },
        SyncDomain.USERS to { usersSyncRunner.sync() },
        SyncDomain.GROUPS to { groupId -> groupsSyncRunner.sync(groupId) },
        SyncDomain.MEMOS to { memosSyncRunner.sync() },
    )

    private data class SyncRequest(
        val force: Boolean,
        val trigger: SyncTrigger,
        val domains: Set<SyncDomain>,
        val groupId: String?,
    )

    private val syncScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val syncMutex = Mutex()
    private val requestChannel = Channel<SyncRequest>(capacity = Channel.UNLIMITED)
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

    fun requestSync(
        trigger: SyncTrigger = SyncTrigger.AUTO,
        force: Boolean = false,
        domains: Set<SyncDomain> = FULL_DOMAINS,
        groupId: String? = null,
    ) {
        requestChannel.trySend(
            SyncRequest(
                force = force,
                trigger = trigger,
                domains = domains,
                groupId = groupId?.trim()?.takeIf(String::isNotBlank),
            )
        )
    }

    suspend fun sync(
        force: Boolean,
        trigger: SyncTrigger = if (force) SyncTrigger.MANUAL else SyncTrigger.AUTO,
        domains: Set<SyncDomain> = FULL_DOMAINS,
        groupId: String? = null,
    ): ApiResponse<Unit> = withContext(Dispatchers.IO) {
        syncMutex.withLock {
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
                    backoffUntilMillis = backoffUntilTime
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
            val result = runDomains(domains, normalizedGroupId)

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
        }
    }

    private suspend fun runDomains(
        domains: Set<SyncDomain>,
        groupId: String?,
    ): ApiResponse<Unit> {
        for (domain in domainExecutionOrder) {
            if (domain !in domains) {
                continue
            }
            val result = domainRunners.getValue(domain).invoke(groupId)
            if (result !is ApiResponse.Success) {
                return result
            }
        }
        return ApiResponse.Success(Unit)
    }

    private suspend fun processSyncRequests() {
        while (true) {
            val first = requestChannel.receive()
            var merged = first
            while (true) {
                val next = requestChannel.tryReceive().getOrNull() ?: break
                val mergedForce = merged.force || next.force
                val mergedTrigger = if (next.trigger.priority() >= merged.trigger.priority()) {
                    next.trigger
                } else {
                    merged.trigger
                }
                val mergedDomains = merged.domains + next.domains
                val mergedGroupId = mergeGroupId(
                    current = merged.groupId,
                    next = next.groupId,
                    domains = mergedDomains
                )
                merged = SyncRequest(
                    force = mergedForce,
                    trigger = mergedTrigger,
                    domains = mergedDomains,
                    groupId = mergedGroupId
                )
            }
            runCatching {
                sync(
                    force = merged.force,
                    trigger = merged.trigger,
                    domains = merged.domains,
                    groupId = merged.groupId,
                )
            }
        }
    }

    private fun mergeGroupId(
        current: String?,
        next: String?,
        domains: Set<SyncDomain>,
    ): String? {
        if (SyncDomain.GROUPS !in domains) {
            return null
        }
        if (current.isNullOrBlank()) {
            return next
        }
        if (next.isNullOrBlank()) {
            return current
        }
        return if (current == next) current else null
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
        private const val FOREGROUND_SYNC_COALESCE_MILLIS = 3_000L
        private const val PENDING_SYNC_COALESCE_MILLIS = 1_500L
        private const val FAILURE_BACKOFF_BASE_MILLIS = 5_000L
        private const val FAILURE_BACKOFF_MAX_MILLIS = 120_000L
    }
}
