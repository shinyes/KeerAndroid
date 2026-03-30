package site.lcyk.keer.util

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import site.lcyk.keer.data.local.entity.ResourceEntity

class ResourceMediaClassifierTest {

    @Before
    fun setUp() {
        clearResourceMediaClassificationCacheForTest()
    }

    @Test
    fun partitionResourcesForDisplay_keepsOrderWithinMediaAndOtherBuckets() {
        val image = resourceEntity(identifier = "1", filename = "photo.jpg", mimeType = "image/jpeg")
        val video = resourceEntity(identifier = "2", filename = "clip.mp4", mimeType = "video/mp4")
        val document = resourceEntity(identifier = "3", filename = "note.txt", mimeType = "text/plain")

        val partitioned = partitionResourcesForDisplay(listOf(document, image, video))

        assertEquals(listOf(image, video), partitioned.mediaResources)
        assertEquals(listOf(document), partitioned.otherResources)
        assertTrue(image.isImageMediaResource())
        assertTrue(video.isMediaDisplayResource())
    }

    private fun resourceEntity(
        identifier: String,
        filename: String,
        mimeType: String?,
    ): ResourceEntity = ResourceEntity(
        identifier = identifier,
        remoteId = null,
        accountKey = "account-key",
        date = Instant.parse("2026-03-30T12:00:00Z"),
        filename = filename,
        uri = "https://example.com/$filename",
        localUri = null,
        mimeType = mimeType,
        encryptionMetadata = null,
        thumbnailUri = null,
        thumbnailLocalUri = null,
        memoId = null,
    )
}
