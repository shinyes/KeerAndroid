package site.lcyk.keer.data.model

import kotlinx.serialization.Serializable

/**
 * Represents a checkpoint in the sync process for resuming interrupted operations.
 * 
 * Checkpoints allow the sync system to resume from where it left off instead
 * of restarting from scratch when sync is interrupted (e.g., app backgrounded,
 * network lost, or device restart).
 */
@Serializable
data class SyncCheckpoint(
    val domain: String,
    val lastSyncTimestamp: Long = 0L,
    val processedIds: Set<String> = emptySet(),
    val pendingMutations: List<PendingMutation> = emptyList(),
    val uploadProgress: UploadProgress? = null,
    val createdAt: Long = System.currentTimeMillis()
) {
    /**
     * Convert domain string to [SyncDomain] enum.
     */
    fun getDomainEnum(): SyncDomain? {
        return try {
            SyncDomain.valueOf(domain)
        } catch (e: IllegalArgumentException) {
            null
        }
    }
    
    companion object {
        fun forDomain(domain: SyncDomain): SyncCheckpoint {
            return SyncCheckpoint(domain = domain.name)
        }
    }
}

/**
 * Represents a pending mutation that needs to be synced to the server.
 */
@Serializable
data class PendingMutation(
    val id: String,
    val entityType: String,
    val operation: OperationType,
    val timestamp: Long,
    val payload: String,
    val retryCount: Int = 0
)

/**
 * Types of mutations that can be performed during sync.
 */
@Serializable
enum class OperationType {
    CREATE,
    UPDATE,
    DELETE
}

/**
 * Tracks progress of an ongoing file upload for checkpoint resume.
 */
@Serializable
data class UploadProgress(
    val fileId: String,
    val uploadedBytes: Long,
    val totalBytes: Long,
    val checkpointData: ByteArrayWrapper? = null,
    val lastUpdateTime: Long = System.currentTimeMillis()
) {
    /**
     * Calculate upload percentage (0.0 to 1.0).
     */
    fun getPercentage(): Float {
        return if (totalBytes > 0) {
            (uploadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }
    }
    
    /**
     * Check if upload is complete.
     */
    fun isComplete(): Boolean {
        return uploadedBytes >= totalBytes && totalBytes > 0
    }
}

/**
 * Wrapper for ByteArray to support serialization.
 */
@Serializable
data class ByteArrayWrapper(
    val data: List<Int>
) {
    constructor(bytes: ByteArray) : this(bytes.toList().map { it.toInt() })
    
    fun toByteArray(): ByteArray {
        return data.map { it.toByte() }.toByteArray()
    }
    
    companion object {
        fun fromByteArray(bytes: ByteArray): ByteArrayWrapper {
            return ByteArrayWrapper(bytes)
        }
    }
}
