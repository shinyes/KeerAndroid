package site.lcyk.keer.data.work

import site.lcyk.keer.data.service.StreamSyncSessionManager
import site.lcyk.keer.data.service.SyncCoordinator
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Initializes and manages the SSE-based continuous sync session.
 */
@Singleton
class SyncInitializer @Inject constructor(
    private val streamSyncSessionManager: StreamSyncSessionManager,
    private val syncCoordinator: SyncCoordinator,
) {
    
    /**
     * Start continuous sync session. Idempotent.
     */
    fun initialize() {
        streamSyncSessionManager.start()
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
        syncCoordinator.requestSync(
            trigger = site.lcyk.keer.data.service.SyncTrigger.MANUAL,
            force = force,
            domains = domains,
            bypassCoalesce = true,
        )
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
