package site.lcyk.keer.ui.component

import androidx.compose.runtime.compositionLocalOf
import site.lcyk.keer.data.model.SyncDomain
import site.lcyk.keer.data.model.SyncStatus

/**
 * CompositionLocal providing the current sync status throughout the UI tree.
 * 
 * This eliminates the need for duplicate Flow collectors in multiple components.
 * Access the current sync status anywhere in the Composable tree:
 * 
 * ```
 * @Composable
 * fun MyComponent() {
 *     val syncStatus = LocalSyncStatus.current
 *     val isSyncing = syncStatus.syncing
 * }
 * ```
 */
val LocalSyncStatus = compositionLocalOf<SyncStatus> {
    error("CompositionLocal LocalSyncStatus not present. Make sure MemosHomePage wraps content with CompositionLocalProvider.")
}

/**
 * Data class containing sync-related actions that can be triggered from the UI.
 */
data class SyncActions(
    /**
     * Request a manual sync operation.
     * @param domains Set of sync domains to sync. Defaults to all domains.
     * @param force If true, bypasses coalescing and triggers immediately.
     */
    val requestSync: (domains: Set<SyncDomain>, force: Boolean) -> Unit,
    
    /**
     * Cancel any ongoing sync operation.
     */
    val cancelSync: () -> Unit,
    
    /**
     * Clear the current sync error message.
     */
    val clearError: () -> Unit
)

/**
 * CompositionLocal providing sync actions throughout the UI tree.
 * 
 * These actions allow any component to trigger sync operations without
 * needing direct access to the ViewModel.
 * 
 * ```
 * @Composable
 * fun SyncButton() {
 *     val syncActions = LocalSyncActions.current
 *     
 *     Button(onClick = { syncActions.requestSync(force = true) }) {
 *         Text("Sync Now")
 *     }
 * }
 * ```
 */
val LocalSyncActions = compositionLocalOf<SyncActions> {
    error("CompositionLocal LocalSyncActions not present. Make sure MemosHomePage wraps content with CompositionLocalProvider.")
}
