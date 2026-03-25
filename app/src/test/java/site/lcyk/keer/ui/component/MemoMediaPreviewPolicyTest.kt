package site.lcyk.keer.ui.component

import java.time.Instant
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import site.lcyk.keer.data.local.entity.ResourceEntity

class MemoMediaPreviewPolicyTest {

    @Test
    fun resolveMemoImagePreviewUri_returnsEmptyWhenOnlyRemoteMainExists() {
        val resource = testResource(
            mimeType = "image/jpeg",
            uri = "https://example.com/original.jpg",
            thumbnailUri = null,
            localUri = null,
            thumbnailLocalUri = null,
        )

        assertEquals("", resolveMemoImagePreviewUri(resource))
    }

    @Test
    fun resolveMemoImagePreviewUri_prefersRemoteThumbnail() {
        val resource = testResource(
            mimeType = "image/jpeg",
            uri = "https://example.com/original.jpg",
            thumbnailUri = "https://example.com/thumb.jpg",
            localUri = null,
            thumbnailLocalUri = null,
        )

        assertEquals("https://example.com/thumb.jpg", resolveMemoImagePreviewUri(resource))
    }

    @Test
    fun resolveMemoVideoPreviewUri_returnsEmptyWhenOnlyRemoteMainExists() {
        val resource = testResource(
            mimeType = "video/mp4",
            uri = "https://example.com/original.mp4",
            thumbnailUri = null,
            localUri = null,
            thumbnailLocalUri = null,
        )

        assertEquals("", resolveMemoVideoPreviewUri(resource))
    }

    @Test
    fun resolveMemoVideoPreviewUri_usesLocalForEncryptedVideo() {
        val resource = testResource(
            mimeType = "video/mp4",
            uri = "https://example.com/original.mp4",
            localUri = "file:///tmp/local.mp4",
            encryptionMetadata = "{}",
            thumbnailUri = null,
            thumbnailLocalUri = null,
        )

        assertEquals("file:///tmp/local.mp4", resolveMemoVideoPreviewUri(resource))
    }

    @Test
    fun isUntrackedMemoScope_matchesExploreAndGroup() {
        assertTrue(testResource(memoId = "explore:42").isUntrackedMemoScope())
        assertTrue(testResource(memoId = "group:g1:100").isUntrackedMemoScope())
        assertFalse(testResource(memoId = "memo-1").isUntrackedMemoScope())
        assertFalse(testResource(memoId = null).isUntrackedMemoScope())
    }

    private fun testResource(
        mimeType: String = "image/jpeg",
        uri: String = "https://example.com/file.bin",
        localUri: String? = null,
        thumbnailUri: String? = null,
        thumbnailLocalUri: String? = null,
        encryptionMetadata: String? = null,
        memoId: String? = "memo-1",
    ): ResourceEntity {
        return ResourceEntity(
            identifier = "res-${UUID.randomUUID()}",
            remoteId = "remote-${UUID.randomUUID()}",
            accountKey = "acc-1",
            date = Instant.EPOCH,
            filename = "file.bin",
            uri = uri,
            localUri = localUri,
            mimeType = mimeType,
            encryptionMetadata = encryptionMetadata,
            thumbnailUri = thumbnailUri,
            thumbnailLocalUri = thumbnailLocalUri,
            memoId = memoId,
        )
    }
}
