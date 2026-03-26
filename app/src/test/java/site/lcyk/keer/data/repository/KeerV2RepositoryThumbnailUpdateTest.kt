package site.lcyk.keer.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.skydoves.sandwich.ApiResponse
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import io.mockk.every
import io.mockk.mockk
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import site.lcyk.keer.data.api.KeerV2Api
import site.lcyk.keer.data.api.KeerV2Resource
import site.lcyk.keer.data.api.UpdateResourceThumbnailRequest
import site.lcyk.keer.data.model.Account
import site.lcyk.keer.data.model.MemosAccount
import site.lcyk.keer.data.security.AttachmentEncryptionManager
import site.lcyk.keer.data.security.AttachmentEncryptionMetadata
import site.lcyk.keer.data.security.EncryptedBlobMetadata
import site.lcyk.keer.data.security.MemoContentCodec
import site.lcyk.keer.data.security.PreparedEncryptedThumbnail
import site.lcyk.keer.data.security.PreparedEncryptedThumbnailUpdate
import site.lcyk.keer.data.security.WrappedContentKey
import java.io.File
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KeerV2RepositoryThumbnailUpdateTest {
    @Test
    fun updateResourceThumbnail_plainUploadKeepsJpegPayload() = runTest {
        val captured = AtomicReference<CapturedThumbnailRequest?>()
        val fixture = createRepositoryFixture(fakeApiForThumbnailUpdate(captured))
        val repository = fixture.repository
        val thumbnailFile = createTempFile("plain-thumb", ".jpg", byteArrayOf(1, 2, 3, 4, 5))

        val response = repository.updateResourceThumbnail(
            remoteId = "attachments/9",
            thumbnailFile = thumbnailFile,
            encryptionMetadata = null,
        )

        assertTrue(response is ApiResponse.Success)
        val request = captured.get()
        assertNotNull(request)
        assertEquals("9", request!!.resourceId)
        assertEquals("image/jpeg", request.body.type)
        assertNull(request.body.thumbnailBlobEncryption)
        assertEquals(Base64.getEncoder().encodeToString(thumbnailFile.readBytes()), request.body.content)
    }

    @Test
    fun updateResourceThumbnail_encryptedUploadAddsThumbnailBlobEncryption() = runTest {
        val captured = AtomicReference<CapturedThumbnailRequest?>()
        val fixture = createRepositoryFixture(fakeApiForThumbnailUpdate(captured))
        val repository = fixture.repository
        val thumbnailFile = createTempFile("enc-thumb", ".jpg", byteArrayOf(2, 4, 6, 8, 10))
        val encryptionMetadata = sampleEncryptionMetadata()
        every {
            fixture.attachmentEncryptionManager.prepareEncryptedThumbnailForExistingAttachment(
                accountKey = any(),
                rawMetadata = encryptionMetadata,
                thumbnailFilename = "blob.thumb.bin",
                thumbnailContent = any(),
            )
        } returns PreparedEncryptedThumbnailUpdate(
            thumbnail = PreparedEncryptedThumbnail(
                filename = "blob.thumb.bin",
                type = AttachmentEncryptionManager.ENCRYPTED_MIME_TYPE,
                content = Base64.getEncoder().encodeToString(byteArrayOf(8, 6, 4, 2)),
            ),
            blobMetadata = EncryptedBlobMetadata(
                wrappedKeys = emptyList(),
                noncePrefix = Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3, 4)),
                plaintextSize = thumbnailFile.length(),
                chunkSize = 64 * 1024,
            ),
        )

        val response = repository.updateResourceThumbnail(
            remoteId = "attachments/10",
            thumbnailFile = thumbnailFile,
            encryptionMetadata = encryptionMetadata,
        )

        assertTrue(response is ApiResponse.Success)
        val request = captured.get()
        assertNotNull(request)
        assertEquals("10", request!!.resourceId)
        assertEquals("blob.thumb.bin", request.body.filename)
        assertEquals(AttachmentEncryptionManager.ENCRYPTED_MIME_TYPE, request.body.type)
        assertTrue(request.body.thumbnailBlobEncryption?.isNotBlank() == true)
        assertNotEquals(Base64.getEncoder().encodeToString(thumbnailFile.readBytes()), request.body.content)
    }

    private fun createRepositoryFixture(api: KeerV2Api): RepositoryFixture {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val accountKeyManager = mockk<site.lcyk.keer.data.security.AccountKeyManager>(relaxed = true)
        val attachmentEncryptionManager = mockk<AttachmentEncryptionManager>()

        return RepositoryFixture(
            repository = KeerV2Repository(
                memosApi = api,
                account = Account.KeerV2(
                    MemosAccount(
                        host = "http://127.0.0.1:1284",
                        id = 1L,
                        name = "cyk",
                    )
                ),
                okHttpClient = OkHttpClient(),
                appContext = context,
                attachmentEncryptionManager = attachmentEncryptionManager,
                accountKeyManager = accountKeyManager,
                memoContentCodec = object : MemoContentCodec {
                    override fun encode(plainText: String): String = plainText
                    override fun decode(storedText: String): String = storedText
                },
            ),
            attachmentEncryptionManager = attachmentEncryptionManager,
        )
    }

    private fun sampleEncryptionMetadata(): String {
        return Json.encodeToString(
            AttachmentEncryptionMetadata(
                originalMimeType = "image/jpeg",
                main = EncryptedBlobMetadata(
                    wrappedKeys = listOf(
                        WrappedContentKey(
                            slotType = "account_master",
                            slotRef = "amk:test",
                            wrapAlgorithm = "AES_GCM_ACCOUNT_MASTER_KEY_V1",
                            wrappedKey = "ZmFrZV93cmFwcGVkX2tleQ==",
                        )
                    ),
                    noncePrefix = Base64.getEncoder().encodeToString(byteArrayOf(9, 9, 9, 9)),
                    plaintextSize = 128,
                    chunkSize = 64 * 1024,
                ),
            )
        )
    }

    private fun fakeApiForThumbnailUpdate(
        captured: AtomicReference<CapturedThumbnailRequest?>
    ): KeerV2Api {
        return Proxy.newProxyInstance(
            KeerV2Api::class.java.classLoader,
            arrayOf(KeerV2Api::class.java),
        ) { _, method, args ->
            when (method.name) {
                "updateResourceThumbnail" -> {
                    val resourceId = args?.get(0) as String
                    val request = args[1] as UpdateResourceThumbnailRequest
                    captured.set(CapturedThumbnailRequest(resourceId, request))
                    ApiResponse.Success(
                        KeerV2Resource(
                            name = "attachments/$resourceId",
                            filename = "blob.bin",
                            externalLink = "https://example.com/file/$resourceId/blob.bin",
                            type = request.type,
                            thumbnailName = "attachments/$resourceId/thumbnail",
                            thumbnailFilename = request.filename,
                            thumbnailType = request.type,
                            thumbnailExternalLink = "https://example.com/file/$resourceId/thumbnail/${request.filename}",
                        )
                    )
                }
                "toString" -> "FakeKeerV2Api"
                "hashCode" -> 1
                "equals" -> false
                else -> throw UnsupportedOperationException("Unexpected api call: ${method.name}")
            }
        } as KeerV2Api
    }

    private fun createTempFile(prefix: String, suffix: String, content: ByteArray): File {
        val file = Files.createTempFile(prefix, suffix).toFile()
        file.writeBytes(content)
        return file
    }

    private data class RepositoryFixture(
        val repository: KeerV2Repository,
        val attachmentEncryptionManager: AttachmentEncryptionManager,
    )

    private data class CapturedThumbnailRequest(
        val resourceId: String,
        val body: UpdateResourceThumbnailRequest,
    )

}
