package site.lcyk.keer.data.work

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Initializes and manages the WorkManager-based sync system.
 * 
 * This singleton is responsible for scheduling periodic background sync
 * when the app starts. It should be called from the Application class
 * or early in the app lifecycle.
 */
@Singleton
class SyncInitializer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val syncScheduler: SyncScheduler
) {
    
    /**
     * Initialize periodic sync scheduling.
     * 
     * Call this once during app startup to ensure periodic sync is scheduled.
     * This method is idempotent - calling it multiple times is safe.
     */
    fun initialize() {
        // Schedule periodic background sync
        syncScheduler.schedulePeriodicSync()
    }
    
    /**
     * Request an immediate sync.
     * 
     * Use this for user-initiated sync operations.
     */
    fun requestSync(
        domains: Set<site.lcyk.keer.data.model.SyncDomain> = site.lcyk.keer.data.service.SyncCoordinator.FULL_DOMAINS,
        force: Boolean = false
    ) {
        syncScheduler.requestImmediateSync(domains, force)
    }
    
    companion object {
        @Volatile private var INSTANCE: SyncInitializer? = null
        
        /**
         * Get the singleton instance.
         * 
         * @throws IllegalStateException if initialize() hasn't been called yet
         */
        fun getInstance(): SyncInitializer {
            return INSTANCE ?: throw IllegalStateException(
                "SyncInitializer not initialized. Call initialize() first."
            )
        }
        
        /**
         * Initialize the singleton. Call once during app startup.
         */
        fun initialize(instance: SyncInitializer) {
            INSTANCE = instance
        }
    }
}
