package site.lcyk.keer.data.local

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import site.lcyk.keer.data.model.SyncCheckpoint
import site.lcyk.keer.data.model.SyncDomain
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persistent storage for sync checkpoints.
 * 
 * Uses SharedPreferences to store checkpoint data that survives app restarts
 * and process death. This enables the sync system to resume from interruptions.
 */
@Singleton
class SyncCheckpointStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    
    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
    
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    
    /**
     * Save a checkpoint for later resumption.
     */
    fun saveCheckpoint(checkpoint: SyncCheckpoint) {
        try {
            val jsonString = json.encodeToString(checkpoint)
            prefs.edit()
                .putString(getKey(checkpoint.domain), jsonString)
                .apply()
        } catch (e: Exception) {
            // Failed to save checkpoint - log but don't crash
            e.printStackTrace()
        }
    }
    
    /**
     * Load a previously saved checkpoint for a domain.
     */
    fun loadCheckpoint(domain: SyncDomain): SyncCheckpoint? {
        return try {
            val jsonString = prefs.getString(getKey(domain.name), null) ?: return null
            json.decodeFromString<SyncCheckpoint>(jsonString)
        } catch (e: Exception) {
            // Invalid checkpoint data - clear it
            e.printStackTrace()
            clearCheckpoint(domain)
            null
        }
    }
    
    /**
     * Clear a checkpoint after successful sync completion.
     */
    fun clearCheckpoint(domain: SyncDomain) {
        prefs.edit().remove(getKey(domain.name)).apply()
    }
    
    /**
     * Clear all checkpoints (e.g., on logout).
     */
    fun clearAllCheckpoints() {
        prefs.edit().clear().apply()
    }
    
    /**
     * Check if a checkpoint exists for a domain.
     */
    fun hasCheckpoint(domain: SyncDomain): Boolean {
        return prefs.contains(getKey(domain.name))
    }
    
    /**
     * Get all domains with pending checkpoints.
     */
    fun getAllCheckpoints(): Map<SyncDomain, SyncCheckpoint> {
        return buildMap {
            SyncDomain.values().forEach { domain ->
                loadCheckpoint(domain)?.let { checkpoint ->
                    put(domain, checkpoint)
                }
            }
        }
    }
    
    private fun getKey(domainName: String): String {
        return "checkpoint_$domainName"
    }
    
    companion object {
        private const val PREFS_NAME = "sync_checkpoints_v1"
    }
}
