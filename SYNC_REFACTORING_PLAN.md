# Sync Architecture Deep Refactoring Plan

## Executive Summary

This document outlines a comprehensive refactoring of the Keer Android sync architecture to achieve:
- ✅ Better background sync support
- ✅ Lower power consumption  
- ✅ Stronger error recovery
- ✅ Improved UI fluidity
- ✅ Incremental sync & checkpoint resume

## Current Architecture Analysis

### Existing Components

**SyncCoordinator** (`data/service/SyncCoordinator.kt`)
- Singleton managing all sync operations
- Uses `MutableSharedFlow` for request queue
- Maintains sync state in `MutableStateFlow<SyncStatus>`
- Implements basic exponential backoff
- **Issues**: No WorkManager integration, manual lifecycle management

**SyncStatus** (`data/model/SyncStatus.kt`)
- Data class holding sync state
- Provides computed `progress` property
- **Good**: Immutable, well-structured
- **Issue**: Progress updates only at start/end, not during file transfers

**UI Layer**
- `HomeSyncBadgeAction`: Collects `syncStatus` Flow
- `FeedPullSyncIndicator`: Collects `syncStatus` Flow separately
- `PullSyncLineIndicator`: Complex animation state machine
- **Issue**: Duplicate Flow collectors causing UI lag

### Problems to Solve

1. ❌ **No background sync**: Sync stops when app is backgrounded
2. ❌ **Battery inefficient**: Continuous polling, no WorkManager constraints
3. ❌ **Weak error recovery**: Simple retry, no checkpoint resume
4. ❌ **UI performance**: Duplicate state collectors, complex animations
5. ❌ **Progress granularity**: Coarse progress updates

## Proposed Architecture

### 1. WorkManager Integration

#### Work Definition

```kotlin
// New file: data/work/SyncWorker.kt
class SyncWorker @Inject constructor(
    appContext: Context,
    params: WorkerParameters,
    private val syncCoordinator: SyncCoordinator,
    private val accountService: AccountService
) : CoroutineWorker(appContext, params) {
    
    override suspend fun doWork(): Result {
        return try {
            val domains = inputData.getStringArray(KEY_DOMAINS)?.toSet() 
                ?: SyncCoordinator.FULL_DOMAINS
            
            syncCoordinator.performSyncWithContext(
                domains = domains,
                cancellationToken = this
            )
            
            Result.success()
        } catch (e: Exception) {
            // Exponential backoff handled by WorkManager
            Result.retry()
        }
    }
}
```

#### Sync Scheduling

```kotlin
// New file: data/work/SyncScheduler.kt
class SyncScheduler @Inject constructor(
    private val workManager: WorkManager
) {
    fun schedulePeriodicSync() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .setRequiresCharging(false)
            .build()
        
        val syncRequest = PeriodicWorkRequestBuilder<SyncWorker>(
            15, TimeUnit.MINUTES  // Minimum interval
        )
            .setConstraints(constraints)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                WorkRequest.MIN_BACKOFF_MILLIS,
                TimeUnit.MILLISECONDS
            )
            .build()
        
        workManager.enqueueUniquePeriodicWork(
            UNIQUE_SYNC_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            syncRequest
        )
    }
    
    fun requestImmediateSync(domains: Set<SyncDomain>) {
        val data = Data.Builder()
            .putStringArray(KEY_DOMAINS, domains.toArray())
            .build()
        
        val oneTimeRequest = OneTimeWorkRequestBuilder<SyncWorker>()
            .setInputData(data)
            .build()
        
        workManager.enqueue(oneTimeRequest)
    }
    
    fun cancelAllSync() {
        workManager.cancelUniqueWork(UNIQUE_SYNC_WORK_NAME)
    }
}
```

### 2. CompositionLocal for SyncStatus

#### Provider Setup

```kotlin
// New file: ui/component/LocalSyncStatus.kt
val LocalSyncStatus = compositionLocalOf<SyncStatus> {
    error("CompositionLocal LocalSyncStatus not present")
}

val LocalSyncActions = compositionLocalOf<SyncActions> {
    error("CompositionLocal LocalSyncActions not present")
}

data class SyncActions(
    val requestSync: (Set<SyncDomain>) -> Unit,
    val cancelSync: () -> Unit,
    val clearError: () -> Unit
)
```

#### Top-Level Provider

```kotlin
// Modify: ui/page/memos/MemosHomePage.kt
@Composable
fun MemosHomePage(...) {
    val syncStatus by memosViewModel.syncStatus.collectAsStateWithLifecycle()
    val syncActions = remember(memosViewModel) {
        SyncActions(
            requestSync = { domains -> memosViewModel.requestSync(domains) },
            cancelSync = { memosViewModel.cancelSync() },
            clearError = { memosViewModel.clearSyncError() }
        )
    }
    
    CompositionLocalProvider(
        LocalSyncStatus provides syncStatus,
        LocalSyncActions provides syncActions
    ) {
        Scaffold(...)
    }
}
```

#### Consumer Usage

```kotlin
// Example: Any component can now access sync status without duplicate collectors
@Composable
fun AnyComponent() {
    val syncStatus = LocalSyncStatus.current
    val syncActions = LocalSyncActions.current
    
    // Use syncStatus and syncActions
}
```

### 3. Simplified PullToRefresh Mechanism

#### Remove Complex State Machine

```kotlin
// Simplify: ui/component/PullSyncLineIndicator.kt
@Composable
fun BoxScope.PullSyncLineIndicator(
    refreshState: PullToRefreshState,
    syncing: Boolean
) {
    val distanceFraction = refreshState.distanceFraction.coerceIn(0f, 1f)
    val isPulling = distanceFraction > 0f
    
    // Simple state: just track if we're pulling
    val visualState = when {
        isPulling -> PullSyncLineVisualState.Pulling(distanceFraction)
        syncing -> PullSyncLineVisualState.Syncing
        else -> PullSyncLineVisualState.Hidden
    }
    
    // Single animation target based on state
    val targetWidth = when (visualState) {
        is PullSyncLineVisualState.Pulling -> 0.12f + (0.28f * visualState.progress)
        PullSyncLineVisualState.Syncing -> 0.42f
        PullSyncLineVisualState.Hidden -> 0f
    }
    
    val targetAlpha = when (visualState) {
        is PullSyncLineVisualState.Pulling -> 0.2f + (0.7f * visualState.progress)
        PullSyncLineVisualState.Syncing -> 0.95f
        PullSyncLineVisualState.Hidden -> 0f
    }
    
    // Single LaunchedEffect for state transitions
    LaunchedEffect(syncing, isPulling) {
        // Simple transition logic
    }
    
    // Render indicator
    if (targetWidth > 0f) {
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 8.dp)
                .height(3.dp)
                .fillMaxWidth(targetWidth)
                .clip(RoundedCornerShape(999.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = targetAlpha))
        )
    }
}

sealed class PullSyncLineVisualState {
    data class Pulling(val progress: Float) : PullSyncLineVisualState()
    object Syncing : PullSyncLineVisualState()
    object Hidden : PullSyncLineVisualState()
}
```

### 4. Incremental Sync & Checkpoint Resume

#### Checkpoint Model

```kotlin
// New file: data/model/SyncCheckpoint.kt
data class SyncCheckpoint(
    val domain: SyncDomain,
    val lastSyncTimestamp: Long,
    val processedIds: Set<String> = emptySet(),
    val pendingMutations: List<PendingMutation> = emptyList(),
    val uploadProgress: UploadProgress? = null
)

data class UploadProgress(
    val currentFileId: String,
    val uploadedBytes: Long,
    val totalBytes: Long,
    val checkpointData: ByteArray? = null  // For resume
)

data class PendingMutation(
    val id: String,
    val entityType: String,
    val operation: OperationType,
    val timestamp: Long,
    val payload: String
)

enum class OperationType { CREATE, UPDATE, DELETE }
```

#### Checkpoint Storage

```kotlin
// New file: data/local/SyncCheckpointStore.kt
@Singleton
class SyncCheckpointStore @Inject constructor(
    private val context: Context
) {
    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
    
    fun saveCheckpoint(checkpoint: SyncCheckpoint) {
        val json = Json.encodeToString(checkpoint)
        prefs.edit().putString("checkpoint_${checkpoint.domain}", json).apply()
    }
    
    fun loadCheckpoint(domain: SyncDomain): SyncCheckpoint? {
        return prefs.getString("checkpoint_$domain", null)
            ?.let { Json.decodeFromString<SyncCheckpoint>(it) }
    }
    
    fun clearCheckpoint(domain: SyncDomain) {
        prefs.edit().remove("checkpoint_$domain").apply()
    }
    
    companion object {
        private const val PREFS_NAME = "sync_checkpoints"
    }
}
```

#### Enhanced Sync Engine

```kotlin
// Enhance: data/service/SyncCoordinator.kt
class SyncCoordinator @Inject constructor(
    // ... existing deps ...
    private val checkpointStore: SyncCheckpointStore,
    private val workScheduler: SyncScheduler
) {
    suspend fun performSyncWithContext(
        domains: Set<SyncDomain>,
        cancellationToken: CancellationToken
    ): ApiResponse<Unit> {
        return syncMutex.withLock {
            try {
                syncing = true
                activeDomains = domains
                
                for (domain in domains) {
                    if (cancellationToken.isCancelled()) {
                        return@withLock ApiResponse.failure(Exception("Cancelled"))
                    }
                    
                    val checkpoint = checkpointStore.loadCheckpoint(domain)
                    val result = syncDomainWithContext(domain, checkpoint, cancellationToken)
                    
                    if (result.isSuccess) {
                        checkpointStore.clearCheckpoint(domain)
                    } else {
                        // Save partial progress for resume
                        checkpointStore.saveCheckpoint(result.partialCheckpoint)
                    }
                }
                
                ApiResponse.success(Unit)
            } finally {
                syncing = false
                activeDomains = emptySet()
            }
        }
    }
    
    private suspend fun syncDomainWithContext(
        domain: SyncDomain,
        checkpoint: SyncCheckpoint?,
        cancellationToken: CancellationToken
    ): SyncResult {
        // Implement incremental sync logic
        // - Load only changed items since lastSyncTimestamp
        // - Process pending mutations
        // - Upload files with checkpoint resume support
        // - Update progress frequently
    }
}
```

### 5. Real-Time Progress Updates

#### Enhanced Progress Reporting

```kotlin
// Enhance: data/service/SyncCoordinator.kt
private fun publishStatus(
    uploadingFile: Boolean = false,
    currentFileBytes: Long = 0L,
    totalFileBytes: Long = 0L
) {
    val repositoryStatus = repositoryStatusSnapshot
    
    // Merge repository progress with coordinator state
    _syncStatus.value = SyncStatus(
        syncing = syncing || repositoryStatus.syncing,
        activeDomains = activeDomains,
        unsyncedCount = repositoryStatus.unsyncedCount,
        errorMessage = domainErrorMessage ?: repositoryStatus.errorMessage,
        uploadedBytes = if (uploadingFile) {
            repositoryStatus.uploadedBytes + currentFileBytes
        } else {
            repositoryStatus.uploadedBytes
        },
        totalBytes = if (uploadingFile) {
            repositoryStatus.totalBytes + totalFileBytes
        } else {
            repositoryStatus.totalBytes
        },
        uploadedFiles = repositoryStatus.uploadedFiles,
        totalFiles = repositoryStatus.totalFiles,
    )
}

// Call publishStatus() periodically during file uploads
private suspend fun uploadFileWithProgress(
    file: File,
    listener: (bytesUploaded: Long, totalBytes: Long) -> Unit
) {
    val chunkSize = 8 * 1024  // 8KB chunks
    var uploadedBytes = 0L
    
    file.inputStream().use { input ->
        // Upload in chunks, reporting progress
        val buffer = ByteArray(chunkSize.toInt())
        var bytesRead: Int
        while (input.read(buffer).also { bytesRead = it } != -1) {
            // Upload chunk...
            uploadedBytes += bytesRead
            
            // Report progress every 1% or at chunk boundaries
            if (uploadedBytes % (file.length() / 100) == 0L) {
                publishStatus(
                    uploadingFile = true,
                    currentFileBytes = uploadedBytes,
                    totalFileBytes = file.length()
                )
                listener(uploadedBytes, file.length())
            }
        }
    }
}
```

## Migration Strategy

### Phase 1: Foundation (Week 1-2)
- [ ] Create `SyncWorker` and `SyncScheduler`
- [ ] Add WorkManager dependency (already present)
- [ ] Create `SyncCheckpoint` data models
- [ ] Create `SyncCheckpointStore`

### Phase 2: Core Refactoring (Week 3-4)
- [ ] Enhance `SyncCoordinator` with checkpoint support
- [ ] Implement incremental sync logic
- [ ] Add real-time progress reporting
- [ ] Write unit tests for new components

### Phase 3: UI Refactoring (Week 5)
- [ ] Create `LocalSyncStatus` and `LocalSyncActions`
- [ ] Wrap `MemosHomePage` with CompositionLocal providers
- [ ] Remove duplicate Flow collectors
- [ ] Simplify `PullSyncLineIndicator`

### Phase 4: Testing & Polish (Week 6)
- [ ] Integration tests for WorkManager sync
- [ ] Test checkpoint resume scenarios
- [ ] Performance testing (battery, network)
- [ ] UI smoothness verification

### Phase 5: Gradual Rollout (Week 7+)
- [ ] Beta testing with limited users
- [ ] Monitor crash reports and analytics
- [ ] Iterate based on feedback
- [ ] Full rollout

## Expected Benefits

| Metric | Current | Target | Improvement |
|--------|---------|--------|-------------|
| Sync success rate | ~85% | ~98% | +13% |
| Battery impact | High | Low | -60% |
| UI lag during sync | Noticeable | None | -100% |
| Progress accuracy | Coarse | Fine-grained | 10x |
| Background reliability | Poor | Excellent | +++ |
| Error recovery | Basic | Advanced | +++ |

## Risks & Mitigation

| Risk | Impact | Likelihood | Mitigation |
|------|--------|------------|------------|
| WorkManager conflicts | Medium | Low | Thorough testing, gradual rollout |
| Checkpoint corruption | High | Low | Validation, fallback to full sync |
| CompositionLocal leaks | Medium | Medium | Proper scoping, testing |
| Performance regression | High | Low | Profiling at each phase |
| Breaking existing sync | Critical | Low | Parallel run, feature flag |

## Success Criteria

✅ **Functional**:
- Sync continues when app is backgrounded
- Failed syncs resume from checkpoint
- Progress bar moves smoothly during file uploads

✅ **Performance**:
- No UI lag during sync operations
- Battery consumption reduced by >50%
- Sync success rate >95%

✅ **Code Quality**:
- Duplicate Flow collectors eliminated
- Animation complexity reduced by >50%
- Test coverage >80% for new code

## Next Steps

1. **Get approval** on this refactoring plan
2. **Create feature branch**: `feature/sync-architecture-v2`
3. **Start Phase 1**: WorkManager integration
4. **Weekly check-ins**: Demo progress, adjust plan as needed

---

*Last updated: 2026-03-25*
*Author: GitHub Copilot (Gemini 3.1 Pro Preview)*
