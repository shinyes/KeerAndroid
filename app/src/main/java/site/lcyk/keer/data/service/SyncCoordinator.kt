package site.lcyk.keer.data.service
import com.skydoves.sandwich.ApiResponse
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import site.lcyk.keer.data.local.SyncCheckpointStore
import site.lcyk.keer.data.model.SyncCheckpoint
import site.lcyk.keer.data.model.SyncDomain
import site.lcyk.keer.data.model.SyncStatus
import site.lcyk.keer.data.model.UploadProgress
import site.lcyk.keer.ext.getErrorMessage

@Singleton
class SyncCoordinator @Inject constructor(
    private val accountService: AccountService,
    private val pendingSyncWorkInspector: PendingSyncWorkInspector,
    private val pullSyncEngine: PullSyncEngine,
    private val checkpointStore: SyncCheckpointStore,
) {
    private data class SyncRequest(
        val force: Boolean,
        val trigger: SyncTrigger,
        val domains: Set<SyncDomain>,
        val groupId: String?,
        val bypassCoalesce: Boolean,
    )

    private val syncScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val syncMutex = Mutex()
    private val requestFlow = MutableSharedFlow<SyncRequest>(
        replay = 0,
        extraBufferCapacity = 1
    )
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
    
    // Checkpoint tracking for resume capability
    private val activeCheckpoints = mutableMapOf<SyncDomain, SyncCheckpoint>()

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
        bypassCoalesce: Boolean = false,
    ) {
        syncScope.launch {
            requestFlow.emit(
                SyncRequest(
                    force = force,
                    trigger = trigger,
                    domains = domains,
                    groupId = groupId?.trim()?.takeIf(String::isNotBlank),
                    bypassCoalesce = bypassCoalesce,
                )
            )
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
            
            // Load checkpoints for all domains being synced
            activeCheckpoints.clear()
            domains.forEach { domain ->
                checkpointStore.loadCheckpoint(domain)?.let { checkpoint ->
                    activeCheckpoints[domain] = checkpoint
                }
            }
            
            publishStatus()

            val normalizedGroupId = groupId?.trim()?.takeIf(String::isNotBlank)
            val result = pullSyncEngine.run(domains, normalizedGroupId)

            if (result is ApiResponse.Success) {
                consecutiveFailureCount = 0
                backoffUntilTime = 0L
                domainErrorMessage = null
                
                // Clear checkpoints on successful sync
                domains.forEach { domain ->
                    checkpointStore.clearCheckpoint(domain)
                }
            } else {
                domainErrorMessage = result.getErrorMessage()
                
                // Save current checkpoints on failure for resume
                activeCheckpoints.forEach { (domain, checkpoint) ->
                    checkpointStore.saveCheckpoint(checkpoint)
                }
                
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
            activeCheckpoints.clear()
            publishStatus()
            result
        }
    }

    private suspend fun processSyncRequests() {
        requestFlow.collect { request ->
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
    
    /**
     * Update sync progress with file-level granularity.
     * 
     * Call this method during file uploads/downloads to provide real-time
     * progress updates to the UI. This creates smooth progress bar animation.
     * 
     * @param domain The sync domain being processed
     * @param currentFileId ID of the file being uploaded/downloaded
     * @param bytesTransferred Bytes transferred so far for current file
     * @param totalBytes Total bytes for current file
     * @param cumulativeBytes Total bytes transferred across all files
     */
    fun updateFileProgress(
        domain: SyncDomain,
        currentFileId: String,
        bytesTransferred: Long,
        totalBytes: Long,
        cumulativeBytes: Long = 0L
    ) {
        val repositoryStatus = repositoryStatusSnapshot
        
        // Update checkpoint with current progress
        val checkpoint = activeCheckpoints[domain]?.copy(
            uploadProgress = UploadProgress(
                fileId = currentFileId,
                uploadedBytes = bytesTransferred,
                totalBytes = totalBytes
            )
        ) ?: SyncCheckpoint(
            domain = domain.name,
            uploadProgress = UploadProgress(
                fileId = currentFileId,
                uploadedBytes = bytesTransferred,
                totalBytes = totalBytes
            )
        )
        
        activeCheckpoints[domain] = checkpoint
        
        // Calculate total progress
        val adjustedUploadedBytes = if (cumulativeBytes > 0) {
            cumulativeBytes + bytesTransferred
        } else {
            repositoryStatus.uploadedBytes + bytesTransferred
        }
        
        val adjustedTotalBytes = if (cumulativeBytes > 0) {
            cumulativeBytes + totalBytes
        } else {
            repositoryStatus.totalBytes
        }
        
        _syncStatus.value = SyncStatus(
            syncing = syncing || repositoryStatus.syncing,
            activeDomains = activeDomains,
            unsyncedCount = repositoryStatus.unsyncedCount,
            errorMessage = domainErrorMessage ?: repositoryStatus.errorMessage,
            uploadedBytes = adjustedUploadedBytes,
            totalBytes = adjustedTotalBytes,
            uploadedFiles = repositoryStatus.uploadedFiles,
            totalFiles = repositoryStatus.totalFiles,
        )
    }
    
    /**
     * Save checkpoint for a domain.
     * 
     * Call this when sync is interrupted to enable resume.
     */
    fun saveCheckpoint(domain: SyncDomain) {
        activeCheckpoints[domain]?.let { checkpoint ->
            checkpointStore.saveCheckpoint(checkpoint)
        }
    }
    
    /**
     * Get active checkpoint for a domain.
     */
    fun getCheckpoint(domain: SyncDomain): SyncCheckpoint? {
        return activeCheckpoints[domain]
    }

    companion object {
        val FULL_DOMAINS: Set<SyncDomain> = SyncDomain.entries.toSet()

        private const val AUTO_SYNC_INTERVAL_MILLIS = 120_000L
        private const val FOREGROUND_SYNC_COALESCE_MILLIS = 45_000L
        private const val PENDING_SYNC_COALESCE_MILLIS = 1_500L
        private const val FAILURE_BACKOFF_BASE_MILLIS = 5_000L
        private const val FAILURE_BACKOFF_MAX_MILLIS = 120_000L
    }
}
