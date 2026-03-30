package site.lcyk.keer.viewmodel

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test
import site.lcyk.keer.data.local.entity.ResourceEntity

class ResourceListUiStateTest {

    @Test
    fun buildResourceListUiState_partitionsResourcesIntoDisplayBuckets() {
        val image = resourceEntity("1", "photo.jpg", "image/jpeg")
        val video = resourceEntity("2", "clip.mp4", "video/mp4")
        val document = resourceEntity("3", "notes.txt", "text/plain")

        val state = buildResourceListUiState(
            listOf(document, image, video)
        )

        assertEquals(listOf(document, image, video), state.resources)
        assertEquals(listOf(image, video), state.imageResources)
        assertEquals(listOf(document), state.otherResources)
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
