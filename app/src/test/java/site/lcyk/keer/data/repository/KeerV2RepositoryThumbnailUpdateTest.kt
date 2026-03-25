package site.lcyk.keer.data.repository

import android.content.Context
import android.content.ContextWrapper
import com.skydoves.sandwich.ApiResponse
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import site.lcyk.keer.data.api.KeerV2Api
import site.lcyk.keer.data.api.KeerV2Resource
import site.lcyk.keer.data.api.UpdateResourceThumbnailRequest
import site.lcyk.keer.data.model.Account
import site.lcyk.keer.data.model.MemosAccount
import site.lcyk.keer.data.security.AccountKeyManager
import site.lcyk.keer.data.security.AttachmentEncryptionManager
import site.lcyk.keer.data.security.MemoContentCodec
import site.lcyk.keer.data.service.SecureAccountMasterKeyStorage
import java.io.File
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.atomic.AtomicReference

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
        val sourceFile = createTempFile("source", ".bin", byteArrayOf(9, 8, 7, 6, 5, 4))
        val thumbnailFile = createTempFile("enc-thumb", ".jpg", byteArrayOf(2, 4, 6, 8, 10))
        val encryptionMetadata = fixture.attachmentEncryptionManager.prepareEncryptedUpload(
            accountKey = TEST_ACCOUNT_KEY,
            checkpointKey = "thumb-test-checkpoint",
            sourceFile = sourceFile,
            originalMimeType = "image/jpeg",
            thumbnail = null,
        ).encryptionMetadata

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
        val filesDir = Files.createTempDirectory("keer-thumb-test-files").toFile()
        val context = TestContext(filesDir)
        val secureStorage = SecureAccountMasterKeyStorage(context)
        val accountKeyManager = AccountKeyManager(secureStorage)
        val attachmentEncryptionManager = AttachmentEncryptionManager(context, accountKeyManager)

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

    private class TestContext(private val root: File) : ContextWrapper(null) {
        override fun getFilesDir(): File = root
        override fun getApplicationContext(): Context = this
    }

    companion object {
        private const val TEST_ACCOUNT_KEY = "thumbnail-update-test-account-key"
    }
}
