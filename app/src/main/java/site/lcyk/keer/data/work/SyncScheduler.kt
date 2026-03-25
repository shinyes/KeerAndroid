package site.lcyk.keer.data.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import site.lcyk.keer.data.model.SyncDomain
import site.lcyk.keer.data.service.SyncCoordinator
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Scheduler for managing WorkManager-based sync operations.
 * 
 * Provides methods to schedule periodic background sync and request
 * immediate one-time sync operations.
 */
@Singleton
class SyncScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val workManager: WorkManager
) {
    
    /**
     * Schedule periodic background sync with WorkManager constraints.
     * 
     * This should be called once during app initialization to ensure
     * periodic sync is scheduled.
     */
    fun schedulePeriodicSync() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(false)  // Allow sync even on low battery
            .setRequiresCharging(false)
            .setRequiresDeviceIdle(false)     // Don't wait for idle state
            .build()
        
        // Minimum interval is 15 minutes for periodic work
        val syncRequest = PeriodicWorkRequestBuilder<SyncWorker>(
            15, TimeUnit.MINUTES
        )
            .setConstraints(constraints)
            .setBackoffCriteria(
                androidx.work.BackoffPolicy.EXPONENTIAL,
                WorkRequest.MIN_BACKOFF_MILLIS,
                TimeUnit.MILLISECONDS
            )
            .addTag(TAG_PERIODIC_SYNC)
            .build()
        
        workManager.enqueueUniquePeriodicWork(
            UNIQUE_PERIODIC_SYNC_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            syncRequest
        )
    }
    
    /**
     * Request an immediate one-time sync operation.
     * 
     * Use this for user-initiated sync requests (e.g., pull-to-refresh).
     */
    fun requestImmediateSync(
        domains: Set<SyncDomain> = SyncCoordinator.FULL_DOMAINS,
        force: Boolean = false
    ) {
        val data = androidx.work.Data.Builder()
            .putStringArray(SyncWorker.KEY_DOMAINS, domains.toList().map { it.name }.toTypedArray())
            .putBoolean(SyncWorker.KEY_FORCE, force)
            .build()
        
        val oneTimeRequest = OneTimeWorkRequestBuilder<SyncWorker>()
            .setInputData(data)
            .addTag(TAG_IMMEDIATE_SYNC)
            .build()
        
        workManager.enqueue(oneTimeRequest)
    }
    
    /**
     * Cancel all scheduled sync work.
     * 
     * Call this when user logs out or disables auto-sync.
     */
    fun cancelAllSync() {
        workManager.cancelAllWorkByTag(TAG_PERIODIC_SYNC)
        workManager.cancelAllWorkByTag(TAG_IMMEDIATE_SYNC)
    }
    
    /**
     * Cancel only periodic sync, keeping pending immediate syncs.
     */
    fun cancelPeriodicSync() {
        workManager.cancelUniqueWork(UNIQUE_PERIODIC_SYNC_NAME)
    }
    
    companion object {
        private const val TAG_PERIODIC_SYNC = "periodic_sync"
        private const val TAG_IMMEDIATE_SYNC = "immediate_sync"
        private const val UNIQUE_PERIODIC_SYNC_NAME = "sync_periodic"
    }
}
