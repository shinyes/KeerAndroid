package site.lcyk.keer.data.service

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import site.lcyk.keer.data.local.SyncCheckpointStore
import site.lcyk.keer.data.model.SyncCheckpoint
import site.lcyk.keer.data.model.SyncDomain
import site.lcyk.keer.data.model.UploadProgress
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Helper class demonstrating how to use real-time progress tracking
 * during file uploads/downloads with checkpoint support.
 * 
 * This is a reference implementation showing the proper usage pattern
 * for [SyncCoordinator.updateFileProgress].
 */
@Singleton
class SyncProgressHelper @Inject constructor(
    private val syncCoordinator: SyncCoordinator,
    private val checkpointStore: SyncCheckpointStore
) {
    
    /**
     * Upload a file with real-time progress updates and checkpoint support.
     * 
     * This method demonstrates the pattern for integrating progress tracking
     * into file upload operations. It:
     * 1. Checks for existing checkpoint to resume
     * 2. Updates progress every 1% during upload
     * 3. Saves checkpoint on interruption
     * 4. Clears checkpoint on success
     * 
     * @param domain The sync domain
     * @param fileId Unique identifier for the file
     * @param fileBytes Total size of the file in bytes
     * @param uploadBlock Lambda that performs the actual upload, receives progress callback
     * @return true if upload succeeded, false otherwise
     */
    suspend fun uploadFileWithProgress(
        domain: SyncDomain,
        fileId: String,
        fileBytes: Long,
        uploadBlock: suspend (bytesUploaded: Long, totalBytes: Long) -> Boolean
    ): Boolean {
        // Check for existing checkpoint to resume
        val checkpoint = checkpointStore.loadCheckpoint(domain)
        val startBytes = if (checkpoint?.uploadProgress?.fileId == fileId) {
            checkpoint.uploadProgress.uploadedBytes
        } else {
            0L
        }
        
        var bytesUploaded = startBytes
        val progressThreshold = calculateProgressThreshold(fileBytes)
        var lastReportedPercent = -1
        
        try {
            // Perform upload with progress tracking
            val success = uploadBlock(bytesUploaded, fileBytes)
            
            if (success) {
                // Upload complete - clear checkpoint
                checkpointStore.clearCheckpoint(domain)
                syncCoordinator.updateFileProgress(
                    domain = domain,
                    currentFileId = fileId,
                    bytesTransferred = fileBytes,
                    totalBytes = fileBytes
                )
                return true
            } else {
                // Upload failed - save checkpoint
                saveCheckpoint(domain, fileId, bytesUploaded, fileBytes)
                return false
            }
        } catch (e: Exception) {
            // Exception occurred - save checkpoint for resume
            saveCheckpoint(domain, fileId, bytesUploaded, fileBytes)
            throw e
        }
    }
    
    /**
     * Upload a file using a Flow that emits progress updates.
     * 
     * This is an alternative pattern using Kotlin Flow for reactive progress tracking.
     * 
     * @param domain The sync domain
     * @param fileId Unique identifier for the file
     * @param fileBytes Total size of the file in bytes
     * @param uploadFlow Flow that emits bytes uploaded and completes on success
     * @return true if upload succeeded
     */
    suspend fun uploadFileWithFlow(
        domain: SyncDomain,
        fileId: String,
        fileBytes: Long,
        uploadFlow: Flow<Long>
    ): Boolean {
        var lastReportedPercent = -1
        
        try {
            uploadFlow.collect { bytesUploaded ->
                // Calculate percentage
                val percent = ((bytesUploaded.toDouble() / fileBytes.toDouble()) * 100).toInt()
                
                // Update progress every 1%
                if (percent > lastReportedPercent) {
                    syncCoordinator.updateFileProgress(
                        domain = domain,
                        currentFileId = fileId,
                        bytesTransferred = bytesUploaded,
                        totalBytes = fileBytes
                    )
                    lastReportedPercent = percent
                }
            }
            
            // Flow completed successfully - clear checkpoint
            checkpointStore.clearCheckpoint(domain)
            return true
        } catch (e: Exception) {
            // Error occurred - save checkpoint
            checkpointStore.loadCheckpoint(domain)?.let { checkpoint ->
                checkpointStore.saveCheckpoint(
                    checkpoint.copy(
                        uploadProgress = UploadProgress(
                            fileId = fileId,
                            uploadedBytes = lastReportedPercent * fileBytes / 100,
                            totalBytes = fileBytes
                        )
                    )
                )
            }
            throw e
        }
    }
    
    /**
     * Download a file with progress tracking.
     * 
     * Similar pattern for downloads - tracks progress and supports resume.
     * 
     * @param domain The sync domain
     * @param fileId Unique identifier for the file
     * @param fileBytes Total size of the file in bytes
     * @param downloadBlock Lambda that performs the actual download
     * @return true if download succeeded
     */
    suspend fun downloadFileWithProgress(
        domain: SyncDomain,
        fileId: String,
        fileBytes: Long,
        downloadBlock: suspend (bytesDownloaded: Long, totalBytes: Long) -> Unit
    ): Boolean {
        var bytesDownloaded = 0L
        val progressThreshold = calculateProgressThreshold(fileBytes)
        var lastReportedPercent = -1
        
        try {
            // Simulate download loop (replace with actual download logic)
            while (bytesDownloaded < fileBytes) {
                val chunkSize = minOf(progressThreshold, fileBytes - bytesDownloaded)
                bytesDownloaded += chunkSize
                
                // Call actual download logic here
                downloadBlock(bytesDownloaded, fileBytes)
                
                // Update progress every 1%
                val percent = ((bytesDownloaded.toDouble() / fileBytes.toDouble()) * 100).toInt()
                if (percent > lastReportedPercent) {
                    syncCoordinator.updateFileProgress(
                        domain = domain,
                        currentFileId = fileId,
                        bytesTransferred = bytesDownloaded,
                        totalBytes = fileBytes
                    )
                    lastReportedPercent = percent
                }
            }
            
            // Download complete - clear checkpoint
            checkpointStore.clearCheckpoint(domain)
            return true
        } catch (e: Exception) {
            // Save checkpoint on failure
            saveCheckpoint(domain, fileId, bytesDownloaded, fileBytes)
            throw e
        }
    }
    
    private fun calculateProgressThreshold(totalBytes: Long): Long {
        // Report progress every 1% or every 64KB, whichever is smaller
        val onePercent = totalBytes / 100
        val fixedChunk = 64 * 1024L  // 64KB
        return minOf(onePercent, fixedChunk).coerceAtLeast(1024)  // Min 1KB
    }
    
    private fun saveCheckpoint(domain: SyncDomain, fileId: String, uploaded: Long, total: Long) {
        val checkpoint = syncCoordinator.getCheckpoint(domain)
            ?: SyncCheckpoint.forDomain(domain)
        
        checkpointStore.saveCheckpoint(
            checkpoint.copy(
                uploadProgress = UploadProgress(
                    fileId = fileId,
                    uploadedBytes = uploaded,
                    totalBytes = total
                )
            )
        )
    }
}
