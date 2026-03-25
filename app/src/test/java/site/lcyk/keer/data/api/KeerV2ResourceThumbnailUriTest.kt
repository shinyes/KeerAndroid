package site.lcyk.keer.data.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KeerV2ResourceThumbnailUriTest {
    @Test
    fun thumbnailUri_returnsNullWhenThumbnailNameMissing() {
        val resource = KeerV2Resource(
            name = "attachments/5",
            filename = "blob.bin",
            thumbnailName = null,
            thumbnailFilename = "blob.thumb.bin"
        )

        assertNull(resource.thumbnailUri("https://example.com"))
    }

    @Test
    fun thumbnailUri_usesProvidedThumbnailFilename() {
        val resource = KeerV2Resource(
            name = "attachments/5",
            filename = "blob.bin",
            thumbnailName = "attachments/5/thumbnail",
            thumbnailFilename = "blob.thumb.bin"
        )

        assertEquals(
            "https://example.com/file/attachments/5/thumbnail/blob.thumb.bin",
            resource.thumbnailUri("https://example.com")?.toString()
        )
    }

    @Test
    fun thumbnailUri_fallsBackToMainFilenameWhenThumbnailFilenameMissing() {
        val resource = KeerV2Resource(
            name = "attachments/5",
            filename = "blob.bin",
            thumbnailName = "attachments/5/thumbnail",
            thumbnailFilename = ""
        )

        assertEquals(
            "https://example.com/file/attachments/5/thumbnail/blob.bin",
            resource.thumbnailUri("https://example.com")?.toString()
        )
    }

    @Test
    fun thumbnailUri_fallsBackToLiteralWhenNoFilenameAvailable() {
        val resource = KeerV2Resource(
            name = "attachments/5",
            filename = "",
            thumbnailName = "attachments/5/thumbnail",
            thumbnailFilename = " "
        )

        assertEquals(
            "https://example.com/file/attachments/5/thumbnail/thumbnail",
            resource.thumbnailUri("https://example.com")?.toString()
        )
    }
}
