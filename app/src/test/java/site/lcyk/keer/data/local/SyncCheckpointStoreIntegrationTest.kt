package site.lcyk.keer.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import site.lcyk.keer.data.model.SyncCheckpoint
import site.lcyk.keer.data.model.SyncDomain
import site.lcyk.keer.data.model.UploadProgress

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class SyncCheckpointStoreIntegrationTest {

    private lateinit var context: Context
    private lateinit var checkpointStore: SyncCheckpointStore

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        checkpointStore = SyncCheckpointStore(context)
    }

    @Test
    fun `save and load checkpoint survives process death`() = runTest {
        // Given
        val domain = SyncDomain.MEMOS
        val originalCheckpoint = SyncCheckpoint.forDomain(domain).copy(
            lastSyncTimestamp = 1234567890L,
            processedIds = setOf("memo1", "memo2", "memo3"),
            pendingMutations = emptyList()
        )

        // When - save checkpoint
        checkpointStore.saveCheckpoint(originalCheckpoint)

        // Simulate process death by creating new instance
        val newStore = SyncCheckpointStore(context)

        // Load checkpoint from new instance
        val loadedCheckpoint = newStore.loadCheckpoint(domain)

        // Then
        assertNotNull(loadedCheckpoint)
        assertEquals(originalCheckpoint.domain, loadedCheckpoint!!.domain)
        assertEquals(originalCheckpoint.lastSyncTimestamp, loadedCheckpoint.lastSyncTimestamp)
        assertEquals(originalCheckpoint.processedIds, loadedCheckpoint.processedIds)
    }

    @Test
    fun `checkpoint with upload progress survives process death`() = runTest {
        // Given
        val domain = SyncDomain.GROUPS
        val fileId = "resource-abc-123"
        val uploadedBytes = 524288L // 512KB
        val totalBytes = 1048576L // 1MB

        val originalCheckpoint = SyncCheckpoint.forDomain(domain).copy(
            uploadProgress = UploadProgress(
                fileId = fileId,
                uploadedBytes = uploadedBytes,
                totalBytes = totalBytes
            )
        )

        // When - save checkpoint
        checkpointStore.saveCheckpoint(originalCheckpoint)

        // Simulate process death
        val newStore = SyncCheckpointStore(context)
        val loadedCheckpoint = newStore.loadCheckpoint(domain)

        // Then
        assertNotNull(loadedCheckpoint)
        assertNotNull(loadedCheckpoint!!.uploadProgress)
        assertEquals(fileId, loadedCheckpoint.uploadProgress!!.fileId)
        assertEquals(uploadedBytes, loadedCheckpoint.uploadProgress!!.uploadedBytes)
        assertEquals(totalBytes, loadedCheckpoint.uploadProgress!!.totalBytes)
    }

    @Test
    fun `multiple checkpoints can be saved and loaded independently`() = runTest {
        // Given
        val memosCheckpoint = SyncCheckpoint.forDomain(SyncDomain.MEMOS).copy(
            lastSyncTimestamp = 1000L
        )

        val resourcesCheckpoint = SyncCheckpoint.forDomain(SyncDomain.GROUPS).copy(
            uploadProgress = UploadProgress("file1", 100, 200)
        )

        val usersCheckpoint = SyncCheckpoint.forDomain(SyncDomain.USERS).copy(
            pendingMutations = listOf()
        )

        // When - save all checkpoints
        checkpointStore.saveCheckpoint(memosCheckpoint)
        checkpointStore.saveCheckpoint(resourcesCheckpoint)
        checkpointStore.saveCheckpoint(usersCheckpoint)

        // Simulate process death
        val newStore = SyncCheckpointStore(context)

        // Then - load each independently
        val loadedMemos = newStore.loadCheckpoint(SyncDomain.MEMOS)
        val loadedResources = newStore.loadCheckpoint(SyncDomain.GROUPS)
        val loadedUsers = newStore.loadCheckpoint(SyncDomain.USERS)

        assertNotNull(loadedMemos)
        assertNotNull(loadedResources)
        assertNotNull(loadedUsers)

        assertEquals(SyncDomain.MEMOS.name, loadedMemos!!.domain)
        assertEquals(SyncDomain.GROUPS.name, loadedResources!!.domain)
        assertEquals(SyncDomain.USERS.name, loadedUsers!!.domain)
    }

    @Test
    fun `getAllCheckpoints returns all saved checkpoints`() = runTest {
        // Given
        val memosCheckpoint = SyncCheckpoint.forDomain(SyncDomain.MEMOS).copy(
            lastSyncTimestamp = 1000L
        )

        val resourcesCheckpoint = SyncCheckpoint.forDomain(SyncDomain.GROUPS).copy(
            uploadProgress = UploadProgress("file1", 100, 200)
        )

        // When - save checkpoints
        checkpointStore.saveCheckpoint(memosCheckpoint)
        checkpointStore.saveCheckpoint(resourcesCheckpoint)

        // Simulate process death
        val newStore = SyncCheckpointStore(context)

        // Get all checkpoints
        val allCheckpoints = newStore.getAllCheckpoints()

        // Then
        assertEquals(2, allCheckpoints.size)
        assertTrue(allCheckpoints.containsKey(SyncDomain.MEMOS))
        assertTrue(allCheckpoints.containsKey(SyncDomain.GROUPS))
    }

    @Test
    fun `clearCheckpoint removes specific checkpoint`() = runTest {
        // Given
        val memosCheckpoint = SyncCheckpoint.forDomain(SyncDomain.MEMOS).copy(
            lastSyncTimestamp = 1000L
        )

        val resourcesCheckpoint = SyncCheckpoint.forDomain(SyncDomain.GROUPS).copy(
            uploadProgress = UploadProgress("file1", 100, 200)
        )

        checkpointStore.saveCheckpoint(memosCheckpoint)
        checkpointStore.saveCheckpoint(resourcesCheckpoint)

        // When - clear only MEMOS checkpoint
        checkpointStore.clearCheckpoint(SyncDomain.MEMOS)

        // Simulate process death
        val newStore = SyncCheckpointStore(context)

        // Then
        assertNull(newStore.loadCheckpoint(SyncDomain.MEMOS))
        assertNotNull(newStore.loadCheckpoint(SyncDomain.GROUPS))
    }

    @Test
    fun `clearAllCheckpoints removes everything`() = runTest {
        // Given
        checkpointStore.saveCheckpoint(
            SyncCheckpoint.forDomain(SyncDomain.MEMOS).copy(lastSyncTimestamp = 1000L)
        )
        checkpointStore.saveCheckpoint(
            SyncCheckpoint.forDomain(SyncDomain.GROUPS).copy(
                uploadProgress = UploadProgress("file1", 100, 200)
            )
        )
        checkpointStore.saveCheckpoint(
            SyncCheckpoint.forDomain(SyncDomain.USERS).copy(pendingMutations = emptyList())
        )

        // When - clear all
        checkpointStore.clearAllCheckpoints()

        // Simulate process death
        val newStore = SyncCheckpointStore(context)

        // Then
        assertTrue(newStore.getAllCheckpoints().isEmpty())
    }

    @Test
    fun `loadCheckpoint returns null for non-existent domain`() = runTest {
        // When
        val checkpoint = checkpointStore.loadCheckpoint(SyncDomain.MEMOS)

        // Then
        assertNull(checkpoint)
    }

    @Test
    fun `updating checkpoint preserves other domains`() = runTest {
        // Given
        val memosCheckpoint1 = SyncCheckpoint.forDomain(SyncDomain.MEMOS).copy(
            lastSyncTimestamp = 1000L
        )

        val resourcesCheckpoint = SyncCheckpoint.forDomain(SyncDomain.GROUPS).copy(
            uploadProgress = UploadProgress("file1", 100, 200)
        )

        checkpointStore.saveCheckpoint(memosCheckpoint1)
        checkpointStore.saveCheckpoint(resourcesCheckpoint)

        // When - update MEMOS checkpoint
        val memosCheckpoint2 = SyncCheckpoint.forDomain(SyncDomain.MEMOS).copy(
            lastSyncTimestamp = 2000L
        )
        checkpointStore.saveCheckpoint(memosCheckpoint2)

        // Simulate process death
        val newStore = SyncCheckpointStore(context)

        // Then - RESOURCES should still exist
        val loadedMemos = newStore.loadCheckpoint(SyncDomain.MEMOS)
        val loadedResources = newStore.loadCheckpoint(SyncDomain.GROUPS)

        assertEquals(2000L, loadedMemos!!.lastSyncTimestamp)
        assertNotNull(loadedResources)
        assertEquals("file1", loadedResources!!.uploadProgress?.fileId)
    }

    @Test
    fun `checkpoint persists after app restart simulation`() = runTest {
        // This test simulates the full lifecycle:
        // 1. App starts sync
        // 2. Saves checkpoint mid-sync
        // 3. App is killed
        // 4. App restarts and loads checkpoint

        // Phase 1: Start sync and save checkpoint
        val initialCheckpoint = SyncCheckpoint.forDomain(SyncDomain.GROUPS).copy(
            uploadProgress = UploadProgress("large-video.mp4", 5_000_000L, 10_000_000L)
        )
        checkpointStore.saveCheckpoint(initialCheckpoint)

        // Phase 2: Simulate app restart (new instance)
        val restartedStore = SyncCheckpointStore(context)

        // Phase 3: Load checkpoint and verify resume point
        val resumedCheckpoint = restartedStore.loadCheckpoint(SyncDomain.GROUPS)

        assertNotNull(resumedCheckpoint)
        assertEquals("large-video.mp4", resumedCheckpoint!!.uploadProgress?.fileId)
        assertEquals(5_000_000L, resumedCheckpoint.uploadProgress!!.uploadedBytes)
        assertEquals(10_000_000L, resumedCheckpoint.uploadProgress!!.totalBytes)

        // Phase 4: Complete sync and clear checkpoint
        restartedStore.clearCheckpoint(SyncDomain.GROUPS)

        // Phase 5: Verify checkpoint cleared
        val finalCheckpoint = restartedStore.loadCheckpoint(SyncDomain.GROUPS)
        assertNull(finalCheckpoint)
    }
}
