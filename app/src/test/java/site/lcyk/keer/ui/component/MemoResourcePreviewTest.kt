package site.lcyk.keer.ui.component

import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import site.lcyk.keer.data.local.entity.ResourceEntity

@OptIn(ExperimentalCoroutinesApi::class)
class MemoResourcePreviewTest {

    @Test
    fun buildObservedResourceFlow_skipsSubscriptionWhenObservationDisabled() = runTest {
        var observeInvocations = 0
        val flow = buildObservedResourceFlow(
            identifier = "res-1",
            observeLiveResource = false,
            observeResource = {
                observeInvocations += 1
                flowOf(testResource(it))
            },
        )

        assertNull(flow.first())
        assertEquals(0, observeInvocations)
    }

    @Test
    fun buildObservedResourceFlow_skipsSubscriptionWhenIdentifierBlank() = runTest {
        var observeInvocations = 0
        val flow = buildObservedResourceFlow(
            identifier = "   ",
            observeLiveResource = true,
            observeResource = {
                observeInvocations += 1
                flowOf(testResource(it))
            },
        )

        assertNull(flow.first())
        assertEquals(0, observeInvocations)
    }

    @Test
    fun buildObservedResourceFlow_subscribesAndEmitsTrackedResourceWhenEnabled() = runTest {
        var observeInvocations = 0
        val expected = testResource("res-42")
        val flow = buildObservedResourceFlow(
            identifier = " res-42 ",
            observeLiveResource = true,
            observeResource = { identifier ->
                observeInvocations += 1
                flowOf(testResource(identifier))
            },
        )

        val actual = flow.first()
        assertEquals(expected.identifier, actual?.identifier)
        assertEquals(1, observeInvocations)
    }

    @Test
    fun resolveObservedMemoResource_freezeKeepsLastObservedResource() {
        val source = testResource("res-1", localUri = "")
        val lastObserved = testResource("res-1", localUri = "file:///tmp/thumb.jpg")

        val resolved = resolveObservedMemoResource(
            sourceResource = source,
            observedResource = null,
            lastObservedResource = lastObserved,
            observeLiveResource = false,
        )
        val resolvedResource = resolved.resource as ResourceEntity

        assertEquals(lastObserved.identifier, resolvedResource.identifier)
        assertEquals(lastObserved.localUri, resolvedResource.localUri)
        assertTrue(resolved.tracked)
    }

    @Test
    fun resolveObservedMemoResource_unfreezeWithoutFreshEmissionStillKeepsLastObservedResource() {
        val source = testResource("res-2", localUri = "")
        val lastObserved = testResource("res-2", localUri = "file:///tmp/preview.jpg")

        val resolved = resolveObservedMemoResource(
            sourceResource = source,
            observedResource = null,
            lastObservedResource = lastObserved,
            observeLiveResource = true,
        )
        val resolvedResource = resolved.resource as ResourceEntity

        assertEquals(lastObserved.localUri, resolvedResource.localUri)
        assertTrue(resolved.tracked)
    }

    @Test
    fun resolveStablePreviewModelState_blankCandidateDoesNotOverrideUsableModel() {
        val state = resolveStablePreviewModelState(
            candidateModel = "   ",
            lastStableModel = "file:///tmp/usable.jpg",
        )

        assertEquals("file:///tmp/usable.jpg", state.model)
        assertEquals("file:///tmp/usable.jpg", state.retainedModel)
    }

    @Test
    fun resolveThumbnailSourceMimeTypeForTest_usesFilenameExtensionWhenMimeMissing() {
        val resource = testResource(
            identifier = "res-heic",
            mimeType = null,
            filename = "capture.heic",
        )

        val resolved = resolveThumbnailSourceMimeTypeForTest(
            resource = resource,
            downloadedPath = "file:///tmp/downloaded.bin",
        )

        assertEquals("image/heic", resolved)
    }

    @Test
    fun resolveThumbnailSourceMimeTypeForTest_fallsBackToDownloadedPathWhenFilenameUnknown() {
        val resource = testResource(
            identifier = "res-video",
            mimeType = null,
            filename = "resource",
        )

        val resolved = resolveThumbnailSourceMimeTypeForTest(
            resource = resource,
            downloadedPath = "file:///tmp/preview.mov",
        )

        assertEquals("video/quicktime", resolved)
    }

    @Test
    fun resolveThumbnailSourceMimeTypeForTest_prefersOriginalMimeTypeFromEncryptionMetadata() {
        val resource = testResource(
            identifier = "res-encrypted",
            mimeType = "application/octet-stream",
            filename = "blob.bin",
        ).copy(
            encryptionMetadata = encryptedMetadataWithOriginalMimeType("image/heic")
        )

        val resolved = resolveThumbnailSourceMimeTypeForTest(
            resource = resource,
            downloadedPath = "file:///tmp/blob.bin",
        )

        assertEquals("image/heic", resolved)
    }

    @Test
    fun resolveThumbnailSourceMimeTypeForTest_ignoresGenericMimeTypeAndFallsBackToPath() {
        val resource = testResource(
            identifier = "res-generic",
            mimeType = "application/octet-stream",
            filename = "blob.bin",
        )

        val resolved = resolveThumbnailSourceMimeTypeForTest(
            resource = resource,
            downloadedPath = "file:///tmp/preview.mov",
        )

        assertEquals("video/quicktime", resolved)
    }

    @Test
    fun resolveThumbnailGenerationModesForTest_encryptedImagePrefersImageThenVideo() {
        val resource = testResource(
            identifier = "res-enc-image",
            mimeType = "application/octet-stream",
            filename = "blob.bin",
        ).copy(
            encryptionMetadata = encryptedMetadataWithOriginalMimeType("image/jpeg")
        )

        val modes = resolveThumbnailGenerationModesForTest(
            resource = resource,
            downloadedPath = "file:///tmp/blob.bin",
        )

        assertEquals(
            listOf(ThumbnailGenerationMode.IMAGE, ThumbnailGenerationMode.VIDEO),
            modes
        )
    }

    @Test
    fun resolveThumbnailGenerationModesForTest_encryptedVideoPrefersVideoThenImage() {
        val resource = testResource(
            identifier = "res-enc-video",
            mimeType = "application/octet-stream",
            filename = "blob.bin",
        ).copy(
            encryptionMetadata = encryptedMetadataWithOriginalMimeType("video/mp4")
        )

        val modes = resolveThumbnailGenerationModesForTest(
            resource = resource,
            downloadedPath = "file:///tmp/blob.bin",
        )

        assertEquals(
            listOf(ThumbnailGenerationMode.VIDEO, ThumbnailGenerationMode.IMAGE),
            modes
        )
    }

    @Test
    fun shouldTriggerRemoteThumbnailUpload_requiresRemoteAndMissingRemoteThumbnail() {
        val resource = testResource("res-upload")

        assertTrue(
            shouldTriggerRemoteThumbnailUpload(
                resource = resource.copy(thumbnailUri = null),
                localThumbnailUri = "file:///tmp/thumb.jpg",
            )
        )
        assertFalse(
            shouldTriggerRemoteThumbnailUpload(
                resource = resource.copy(remoteId = null, thumbnailUri = null),
                localThumbnailUri = "file:///tmp/thumb.jpg",
            )
        )
        assertFalse(
            shouldTriggerRemoteThumbnailUpload(
                resource = resource.copy(thumbnailUri = "https://example.com/thumb.jpg"),
                localThumbnailUri = "file:///tmp/thumb.jpg",
            )
        )
        assertTrue(
            shouldTriggerRemoteThumbnailUpload(
                resource = resource.copy(
                    uri = "https://example.com/file/attachments/88/blob.bin",
                    thumbnailUri = "https://example.com/file/attachments/88/blob.bin",
                ),
                localThumbnailUri = "file:///tmp/thumb.jpg",
            )
        )
        assertFalse(
            shouldTriggerRemoteThumbnailUpload(
                resource = resource.copy(thumbnailUri = null),
                localThumbnailUri = "   ",
            )
        )
    }

    @Test
    fun resolveUsableRemoteThumbnailUri_returnsNullWhenEqualToRemoteMain() {
        val resource = testResource(identifier = "res-remote-thumb-equal-main").copy(
            uri = "https://example.com/file/attachments/99/blob.bin",
            thumbnailUri = "https://example.com/file/attachments/99/blob.bin",
        )

        assertNull(resource.resolveUsableRemoteThumbnailUri())
    }

    @Test
    fun resolveUsableRemoteThumbnailUri_keepsTrueThumbnailPath() {
        val resource = testResource(identifier = "res-remote-thumb-real").copy(
            uri = "https://example.com/file/attachments/100/blob.bin",
            thumbnailUri = "https://example.com/file/attachments/100/thumbnail/blob.thumb.bin",
        )

        assertEquals(
            "https://example.com/file/attachments/100/thumbnail/blob.thumb.bin",
            resource.resolveUsableRemoteThumbnailUri()
        )
    }

    @Test
    fun thumbnailUploadKickoffGate_deduplicatesWithinInterval() = runTest {
        ThumbnailUploadKickoffGate.clearForTest()

        assertTrue(
            ThumbnailUploadKickoffGate.tryAcquire(
                resourceIdentifier = "res-kick",
                nowMillis = 1_000L,
                minIntervalMillis = 10_000L,
            )
        )
        assertFalse(
            ThumbnailUploadKickoffGate.tryAcquire(
                resourceIdentifier = "res-kick",
                nowMillis = 2_000L,
                minIntervalMillis = 10_000L,
            )
        )
        assertTrue(
            ThumbnailUploadKickoffGate.tryAcquire(
                resourceIdentifier = "res-kick",
                nowMillis = 12_001L,
                minIntervalMillis = 10_000L,
            )
        )
    }

    @Test
    fun thumbnailUploadKickoffGate_deduplicatesByRemoteIdAcrossDifferentIdentifiers() = runTest {
        ThumbnailUploadKickoffGate.clearForTest()

        assertTrue(
            ThumbnailUploadKickoffGate.tryAcquire(
                resourceIdentifier = "res-a",
                remoteId = "attachments/42",
                nowMillis = 1_000L,
                minIntervalMillis = 10_000L,
            )
        )
        assertFalse(
            ThumbnailUploadKickoffGate.tryAcquire(
                resourceIdentifier = "res-b",
                remoteId = "attachments/42",
                nowMillis = 1_500L,
                minIntervalMillis = 10_000L,
            )
        )
        assertTrue(
            ThumbnailUploadKickoffGate.tryAcquire(
                resourceIdentifier = "res-c",
                remoteId = "attachments/42",
                nowMillis = 12_000L,
                minIntervalMillis = 10_000L,
            )
        )
    }

    private fun testResource(
        identifier: String,
        localUri: String? = null,
        mimeType: String? = "image/jpeg",
        filename: String = "$identifier.jpg",
    ): ResourceEntity {
        return ResourceEntity(
            identifier = identifier,
            remoteId = "remote-$identifier",
            accountKey = "acc-1",
            date = Instant.EPOCH,
            filename = filename,
            uri = "https://example.com/$identifier",
            localUri = localUri,
            mimeType = mimeType,
            memoId = "memo-1",
        )
    }

    private fun encryptedMetadataWithOriginalMimeType(originalMimeType: String): String {
        return """
            {
              "version": 1,
              "algorithm": "AES_GCM_CHUNKED_V1",
              "originalMimeType": "$originalMimeType",
              "main": {
                "wrappedKeys": [
                  {
                    "slotType": "account_master",
                    "slotRef": "test-account",
                    "wrapAlgorithm": "AES_GCM_ACCOUNT_MASTER_KEY_V1",
                    "wrappedKey": "AA=="
                  }
                ],
                "noncePrefix": "AAAAAAAAAAA=",
                "plaintextSize": 1,
                "chunkSize": 1024,
                "tagSize": 16
              }
            }
        """.trimIndent()
    }
}
