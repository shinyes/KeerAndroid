package site.lcyk.keer.util

import org.junit.Assert.assertEquals
import org.junit.Test

class UploadMediaMetadataResolverTest {
    @Test
    fun sniffMimeType_detectsHeic() {
        val sample = buildFtypSample("heic")

        assertEquals("image/heic", UploadMediaMetadataResolver.sniffMimeType(sample))
    }

    @Test
    fun sniffMimeType_detectsJpeg() {
        val sample = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xEE.toByte())

        assertEquals("image/jpeg", UploadMediaMetadataResolver.sniffMimeType(sample))
    }

    @Test
    fun sniffMimeType_detectsMp4() {
        val sample = buildFtypSample("mp42")

        assertEquals("video/mp4", UploadMediaMetadataResolver.sniffMimeType(sample))
    }

    @Test
    fun resolveFilename_rewritesCapturePlaceholderWithActualMime() {
        val filename = UploadMediaMetadataResolver.resolveFilename(
            displayName = "capture_picture_123.captureimage",
            mimeType = "image/heic",
            fallbackBaseName = "attachment_test",
        )

        assertEquals("capture_picture_123.heic", filename)
    }

    private fun buildFtypSample(brand: String): ByteArray {
        val bytes = ByteArray(16)
        bytes[4] = 'f'.code.toByte()
        bytes[5] = 't'.code.toByte()
        bytes[6] = 'y'.code.toByte()
        bytes[7] = 'p'.code.toByte()
        val paddedBrand = brand.padEnd(4, ' ')
        paddedBrand.forEachIndexed { index, char ->
            bytes[8 + index] = char.code.toByte()
        }
        return bytes
    }
}
