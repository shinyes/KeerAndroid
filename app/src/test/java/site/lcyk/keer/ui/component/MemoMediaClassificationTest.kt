package site.lcyk.keer.ui.component

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import site.lcyk.keer.data.model.ResourceRepresentable
import java.time.Instant

class MemoMediaClassificationTest {
    @After
    fun tearDown() {
        clearMediaTypeClassificationCacheForTest()
    }

    @Test
    fun isImageResource_prefersOriginalMimeTypeFromEncryptionMetadata() {
        val resource = FakeResource(
            remoteId = "r-1",
            filename = "blob.bin",
            mimeType = "application/octet-stream",
            encryptionMetadata = encryptionMetadataWithOriginalMime("image/webp"),
            uri = "https://example.com/r-1",
        )

        assertTrue(resource.isImageResource())
        assertFalse(resource.isVideoResource())
        assertTrue(resource.isMediaResource())
    }

    @Test
    fun isVideoResource_fallsBackToMimeTypeWhenMetadataMissing() {
        val resource = FakeResource(
            remoteId = "r-2",
            filename = "unknown.dat",
            mimeType = "video/mp4",
            encryptionMetadata = null,
            uri = "https://example.com/r-2",
        )

        assertTrue(resource.isVideoResource())
        assertFalse(resource.isImageResource())
        assertTrue(resource.isMediaResource())
    }

    @Test
    fun isMediaResource_fallsBackToFilenameExtension() {
        val image = FakeResource(
            remoteId = "r-3",
            filename = "photo.JPEG",
            mimeType = "application/octet-stream",
            encryptionMetadata = null,
            uri = "https://example.com/r-3",
        )
        val other = FakeResource(
            remoteId = "r-4",
            filename = "notes.pdf",
            mimeType = "application/pdf",
            encryptionMetadata = null,
            uri = "https://example.com/r-4",
        )

        assertTrue(image.isImageResource())
        assertTrue(image.isMediaResource())
        assertFalse(other.isImageResource())
        assertFalse(other.isVideoResource())
        assertFalse(other.isMediaResource())
    }

    @Test
    fun mediaClassificationCache_reusesCachedResultForSameResource() {
        val resource = FakeResource(
            remoteId = "r-5",
            filename = "cached.png",
            mimeType = "image/png",
            encryptionMetadata = null,
            uri = "https://example.com/r-5",
        )

        clearMediaTypeClassificationCacheForTest()
        assertEquals(0, mediaTypeClassificationCacheSizeForTest())

        assertTrue(resource.isImageResource())
        val firstSize = mediaTypeClassificationCacheSizeForTest()
        assertEquals(1, firstSize)

        assertTrue(resource.isImageResource())
        assertEquals(firstSize, mediaTypeClassificationCacheSizeForTest())
    }

    private fun encryptionMetadataWithOriginalMime(originalMimeType: String): String {
        return """
            {
              "version": 1,
              "algorithm": "AES_GCM_CHUNKED_V1",
              "originalMimeType": "$originalMimeType",
              "main": {
                "wrappedKeys": [
                  {
                    "slotType": "account_master",
                    "slotRef": "slot",
                    "wrapAlgorithm": "AES_GCM_ACCOUNT_MASTER_KEY_V1",
                    "wrappedKey": "payload"
                  }
                ],
                "noncePrefix": "AAAA",
                "plaintextSize": 1,
                "chunkSize": 1,
                "tagSize": 16
              }
            }
        """.trimIndent()
    }

    private data class FakeResource(
        override val remoteId: String?,
        override val filename: String,
        override val mimeType: String?,
        override val encryptionMetadata: String?,
        override val uri: String,
        override val localUri: String? = null,
        override val thumbnailUri: String? = null,
        override val thumbnailLocalUri: String? = null,
        override val date: Instant = Instant.EPOCH,
    ) : ResourceRepresentable
}
