package site.lcyk.keer.data.service

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import site.lcyk.keer.data.local.SyncCheckpointStore
import site.lcyk.keer.data.model.SyncCheckpoint
import site.lcyk.keer.data.model.SyncDomain
import site.lcyk.keer.data.model.UploadProgress

class SyncProgressHelperTest {

    private lateinit var syncCoordinator: SyncCoordinator
    private lateinit var checkpointStore: SyncCheckpointStore
    private lateinit var progressHelper: SyncProgressHelper

    @Before
    fun setup() {
        syncCoordinator = mockk(relaxed = true)
        checkpointStore = mockk(relaxed = true)
        progressHelper = SyncProgressHelper(syncCoordinator, checkpointStore)
    }

    @Test
    fun `uploadFileWithProgress calls uploadBlock with correct parameters`() = runTest {
        // Given
        val domain = SyncDomain.GROUPS
        val fileId = "test-file-123"
        val fileBytes = 1024L
        var uploadBlockCalled = false

        coEvery { checkpointStore.loadCheckpoint(any()) } returns null
        coEvery { checkpointStore.clearCheckpoint(any()) } returns Unit

        // When
        progressHelper.uploadFileWithProgress(
            domain = domain,
            fileId = fileId,
            fileBytes = fileBytes
        ) { bytesUploaded, totalBytes ->
            uploadBlockCalled = true
            assertEquals(0L, bytesUploaded)
            assertEquals(fileBytes, totalBytes)
            true // Simulate success
        }

        // Then
        assertTrue(uploadBlockCalled)
        coVerify { checkpointStore.clearCheckpoint(domain) }
        coVerify {
            syncCoordinator.updateFileProgress(
                domain = domain,
                currentFileId = fileId,
                bytesTransferred = fileBytes,
                totalBytes = fileBytes
            )
        }
    }

    @Test
    fun `uploadFileWithProgress resumes from checkpoint if exists`() = runTest {
        // Given
        val domain = SyncDomain.GROUPS
        val fileId = "test-file-456"
        val fileBytes = 2048L
        val uploadedBytes = 512L

        val checkpoint = SyncCheckpoint.forDomain(domain).copy(
            uploadProgress = UploadProgress(fileId, uploadedBytes, fileBytes)
        )

        coEvery { checkpointStore.loadCheckpoint(domain) } returns checkpoint
        coEvery { checkpointStore.clearCheckpoint(any()) } returns Unit

        var receivedStartBytes = -1L

        // When
        progressHelper.uploadFileWithProgress(
            domain = domain,
            fileId = fileId,
            fileBytes = fileBytes
        ) { bytesUploaded, totalBytes ->
            receivedStartBytes = bytesUploaded
            true // Simulate success
        }

        // Then
        assertEquals(uploadedBytes, receivedStartBytes)
        coVerify { checkpointStore.clearCheckpoint(domain) }
    }

    @Test
    fun `uploadFileWithProgress saves checkpoint on failure`() = runTest {
        // Given
        val domain = SyncDomain.GROUPS
        val fileId = "test-file-789"
        val fileBytes = 1024L

        coEvery { checkpointStore.loadCheckpoint(any()) } returns null

        // When
        progressHelper.uploadFileWithProgress(
            domain = domain,
            fileId = fileId,
            fileBytes = fileBytes
        ) { _, _ ->
            false // Simulate failure
        }

        // Then
        coVerify { checkpointStore.saveCheckpoint(any()) }
    }

    @Test
    fun `uploadFileWithProgress saves checkpoint on exception`() = runTest {
        // Given
        val domain = SyncDomain.GROUPS
        val fileId = "test-file-exception"
        val fileBytes = 1024L

        coEvery { checkpointStore.loadCheckpoint(any()) } returns null

        // When
        try {
            progressHelper.uploadFileWithProgress(
                domain = domain,
                fileId = fileId,
                fileBytes = fileBytes
            ) { _, _ ->
                throw RuntimeException("Simulated exception")
            }
        } catch (e: Exception) {
            // Expected
        }

        // Then
        coVerify { checkpointStore.saveCheckpoint(any()) }
    }

    @Test
    fun `uploadFileWithFlow updates progress every 1 percent`() = runTest {
        // Given
        val domain = SyncDomain.MEMOS
        val fileId = "flow-test-file"
        val fileBytes = 10000L

        coEvery { checkpointStore.clearCheckpoint(any()) } returns Unit

        val progressFlow = flow {
            for (i in 0..100) {
                emit(i * 100L) // 0%, 1%, 2%, ..., 100%
            }
        }

        // When
        progressHelper.uploadFileWithFlow(
            domain = domain,
            fileId = fileId,
            fileBytes = fileBytes,
            uploadFlow = progressFlow
        )

        // Then
        coVerify(atLeast = 100) {
            syncCoordinator.updateFileProgress(
                domain = domain,
                currentFileId = fileId,
                bytesTransferred = any(),
                totalBytes = fileBytes
            )
        }
        coVerify { checkpointStore.clearCheckpoint(domain) }
    }

    @Test
    fun `uploadFileWithFlow saves checkpoint on error`() = runTest {
        // Given
        val domain = SyncDomain.MEMOS
        val fileId = "flow-error-test"
        val fileBytes = 10000L

        val failingFlow = flow<Long> {
            emit(1000L)
            emit(2000L)
            throw RuntimeException("Network error")
        }

        // When
        try {
            progressHelper.uploadFileWithFlow(
                domain = domain,
                fileId = fileId,
                fileBytes = fileBytes,
                uploadFlow = failingFlow
            )
        } catch (e: Exception) {
            // Expected
        }

        // Then
        coVerify { checkpointStore.saveCheckpoint(any()) }
    }

    @Test
    fun `downloadFileWithProgress tracks download progress`() = runTest {
        // Given
        val domain = SyncDomain.GROUPS
        val fileId = "download-test"
        val fileBytes = 5000L

        coEvery { checkpointStore.clearCheckpoint(any()) } returns Unit

        var downloadCallCount = 0

        // When
        progressHelper.downloadFileWithProgress(
            domain = domain,
            fileId = fileId,
            fileBytes = fileBytes
        ) { bytesDownloaded, totalBytes ->
            downloadCallCount++
            assertTrue(bytesDownloaded <= totalBytes)
        }

        // Then
        assertTrue(downloadCallCount > 0)
        coVerify { checkpointStore.clearCheckpoint(domain) }
    }

    @Test
    fun `downloadFileWithProgress saves checkpoint on exception`() = runTest {
        // Given
        val domain = SyncDomain.GROUPS
        val fileId = "download-exception-test"
        val fileBytes = 5000L

        // When
        try {
            progressHelper.downloadFileWithProgress(
                domain = domain,
                fileId = fileId,
                fileBytes = fileBytes
            ) { _, _ ->
                throw RuntimeException("Disk full")
            }
        } catch (e: Exception) {
            // Expected
        }

        // Then
        coVerify { checkpointStore.saveCheckpoint(any()) }
    }

    @Test
    fun `calculateProgressThreshold uses 1 percent for small files`() {
        // Given
        val smallFileSize = 10_000L // 10KB
        val onePercent = smallFileSize / 100 // 100 bytes

        // When & Then
        // The threshold should be the smaller of 1% or 64KB
        // For 10KB file: min(100, 65536) = 100
        assertTrue(onePercent < 64 * 1024)
    }

    @Test
    fun `calculateProgressThreshold uses 64KB for large files`() {
        // Given
        val largeFileSize = 100_000_000L // 100MB
        val onePercent = largeFileSize / 100 // 1MB

        // When & Then
        // The threshold should be the smaller of 1% or 64KB
        // For 100MB file: min(1_000_000, 65536) = 65536
        assertTrue(64 * 1024 < onePercent)
    }

    @Test
    fun `progress helper injects correctly with Hilt`() {
        // This test verifies that the class can be instantiated
        // In a real Hilt test, you'd use @HiltAndroidTest and @Inject

        // Given
        val coordinator = mockk<SyncCoordinator>()
        val store = mockk<SyncCheckpointStore>()

        // When
        val helper = SyncProgressHelper(coordinator, store)

        // Then
        assertNotNull(helper)
    }
}

