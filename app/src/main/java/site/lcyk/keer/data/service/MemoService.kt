package site.lcyk.keer.data.service

import android.content.Context
import com.skydoves.sandwich.ApiResponse
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import site.lcyk.keer.data.local.entity.MemoEntity
import site.lcyk.keer.data.model.SyncStatus
import site.lcyk.keer.data.repository.AbstractMemoRepository
import site.lcyk.keer.ext.settingsDataStore
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
class MemoService @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val accountService: AccountService,
    private val offlineSyncTaskScheduler: OfflineSyncTaskScheduler,
) {
    private data class SyncRequest(val force: Boolean, val trigger: SyncTrigger)

    private var lastSyncAttemptTime = 0L
    private var backoffUntilTime = 0L
    private var consecutiveFailureCount = 0
    private val syncMutex = Mutex()
    private val requestChannel = Channel<SyncRequest>(capacity = Channel.UNLIMITED)
    private val syncScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        syncScope.launch {
            processSyncRequests()
        }
    }

    suspend fun getRepository(): AbstractMemoRepository {
        return accountService.getRepository()
    }

    val syncStatus: Flow<SyncStatus> = accountService.currentAccount.flatMapLatest {
        accountService.getRepository().syncStatus
    }

    val memos: Flow<List<MemoEntity>> = accountService.currentAccount.flatMapLatest {
        accountService.getRepository().observeMemos()
    }

    fun requestSync(
        trigger: SyncTrigger = SyncTrigger.AUTO,
        force: Boolean = false
    ) {
        requestChannel.trySend(SyncRequest(force = force, trigger = trigger))
    }

    suspend fun sync(
        force: Boolean,
        trigger: SyncTrigger = if (force) SyncTrigger.MANUAL else SyncTrigger.AUTO
    ): ApiResponse<Unit> = withContext(Dispatchers.IO) {
        syncMutex.withLock {
            val now = System.currentTimeMillis()
            val hasPendingWork = hasPendingOfflineWork()
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

            val preSyncResult = offlineSyncTaskScheduler.dispatch(
                setOf(
                    OfflineSyncTask.AVATAR,
                    OfflineSyncTask.GROUP_OPERATIONS,
                    OfflineSyncTask.GROUP_TAGS,
                    OfflineSyncTask.GROUP_MESSAGES,
                    OfflineSyncTask.USERS
                )
            )
            val memoSyncResult = offlineSyncTaskScheduler.dispatch(OfflineSyncTask.MEMOS)

            val preSyncFailed = preSyncResult !is ApiResponse.Success
            val memoSyncFailed = memoSyncResult !is ApiResponse.Success
            if (memoSyncFailed || preSyncFailed) {
                if (!force) {
                    consecutiveFailureCount += 1
                    backoffUntilTime = SyncTriggerPolicy.calculateBackoffUntil(
                        nowMillis = now,
                        consecutiveFailures = consecutiveFailureCount,
                        baseBackoffMillis = FAILURE_BACKOFF_BASE_MILLIS,
                        maxBackoffMillis = FAILURE_BACKOFF_MAX_MILLIS
                    )
                }
            } else {
                consecutiveFailureCount = 0
                backoffUntilTime = 0L
            }

            if (memoSyncResult is ApiResponse.Success) {
                return@withLock ApiResponse.Success(Unit)
            }
            memoSyncResult
        }
    }

    companion object {
        // Only applies when there is no local pending work.
        private const val AUTO_SYNC_INTERVAL_MILLIS = 120_000L
        // Avoid duplicate start/resume sync bursts.
        private const val FOREGROUND_SYNC_COALESCE_MILLIS = 3_000L
        // Coalesce rapid mutation-triggered sync requests.
        private const val PENDING_SYNC_COALESCE_MILLIS = 1_500L
        // Exponential retry backoff for automatic sync failures.
        private const val FAILURE_BACKOFF_BASE_MILLIS = 5_000L
        private const val FAILURE_BACKOFF_MAX_MILLIS = 120_000L
    }

    private suspend fun hasPendingOfflineWork(): Boolean {
        if (syncStatus.first().unsyncedCount > 0) {
            return true
        }
        val settings = context.settingsDataStore.data.first()
        val userSettings = settings.usersList
            .firstOrNull { it.accountKey == settings.currentUser }
            ?.settings
            ?: return false
        return userSettings.avatarSyncPending ||
            userSettings.pendingGroupMemos.isNotEmpty() ||
            userSettings.pendingGroupOperations.isNotEmpty()
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
                merged = SyncRequest(
                    force = mergedForce,
                    trigger = mergedTrigger
                )
            }
            runCatching {
                sync(force = merged.force, trigger = merged.trigger)
            }
        }
    }
}
