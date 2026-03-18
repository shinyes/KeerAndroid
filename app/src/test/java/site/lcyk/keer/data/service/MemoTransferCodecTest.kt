package site.lcyk.keer.data.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import site.lcyk.keer.data.model.MemoVisibility
import java.time.Instant

class MemoTransferCodecTest {
    @Test
    fun decodeImportEntries_readsKeerDocumentAndPreservesCreatedAt() {
        val createdAt = Instant.parse("2024-06-01T10:20:30Z")
        val payload = """
            {
              "format": "keer.memo.transfer.v1",
              "memos": [
                {
                  "content": "hello keer",
                  "createdAt": "${createdAt}",
                  "visibility": "PUBLIC",
                  "tags": ["daily", "work"],
                  "latitude": 31.2304,
                  "longitude": 121.4737
                }
              ]
            }
        """.trimIndent()

        val entries = MemoTransferCodec.decodeImportEntries(payload)

        assertEquals(1, entries.size)
        val entry = entries.first()
        assertEquals("hello keer", entry.content)
        assertEquals(createdAt, entry.createdAt)
        assertEquals(MemoVisibility.PUBLIC, entry.visibility)
        assertEquals(listOf("daily", "work"), entry.tags)
        assertNotNull(entry.latitude)
        assertNotNull(entry.longitude)
        assertEquals(31.2304, entry.latitude!!, 0.0)
        assertEquals(121.4737, entry.longitude!!, 0.0)
    }

    @Test
    fun decodeImportEntries_supportsExternalArrayAndEpochSeconds() {
        val payload = """
            [
              {
                "text": "imported memo",
                "createTime": 1704067200,
                "visibility": "protected",
                "tags": "one, two ;three",
                "lat": 40.7128,
                "lng": -74.0060
              }
            ]
        """.trimIndent()

        val entries = MemoTransferCodec.decodeImportEntries(payload)

        assertEquals(1, entries.size)
        val entry = entries.first()
        assertEquals("imported memo", entry.content)
        assertEquals(Instant.ofEpochSecond(1704067200), entry.createdAt)
        assertEquals(MemoVisibility.PROTECTED, entry.visibility)
        assertEquals(listOf("one", "two", "three"), entry.tags)
        assertNotNull(entry.latitude)
        assertNotNull(entry.longitude)
        assertEquals(40.7128, entry.latitude!!, 0.0)
        assertEquals(-74.0060, entry.longitude!!, 0.0)
    }

    @Test
    fun decodeImportEntries_rejectsUnsupportedPayload() {
        runCatching {
            MemoTransferCodec.decodeImportEntries("""{"foo":"bar"}""")
        }.onSuccess {
            throw AssertionError("Expected decode failure")
        }.onFailure { throwable ->
            assertTrue(throwable is IllegalArgumentException)
        }
    }

    @Test
    fun decodeImportEntries_parsesAttachmentsAndMemoFlags() {
        val payload = """
            {
              "format": "keer.memo.transfer.v2",
              "memos": [
                {
                  "importId": "keer:v1:abc",
                  "content": "memo with file",
                  "pinned": true,
                  "archived": true,
                  "attachments": [
                    {
                      "path": "attachments/memo-0001/001-image.png",
                      "filename": "image.png",
                      "mimeType": "image/png"
                    }
                  ]
                }
              ]
            }
        """.trimIndent()

        val entries = MemoTransferCodec.decodeImportEntries(payload)

        assertEquals(1, entries.size)
        val entry = entries.first()
        assertEquals("keer:v1:abc", entry.importId)
        assertTrue(entry.pinned)
        assertTrue(entry.archived)
        assertEquals(1, entry.attachments.size)
        assertEquals("attachments/memo-0001/001-image.png", entry.attachments.first().path)
        assertEquals("image.png", entry.attachments.first().filename)
        assertEquals("image/png", entry.attachments.first().mimeType)
    }
}
