package site.lcyk.keer.data.repository

import android.content.Context
import android.content.ContextWrapper
import com.skydoves.sandwich.ApiResponse
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import site.lcyk.keer.data.api.GetCurrentUserResponse
import site.lcyk.keer.data.api.KeerV2Api
import site.lcyk.keer.data.api.KeerV2User
import site.lcyk.keer.data.api.KeerV2UserSetting
import site.lcyk.keer.data.api.KeerV2UserSettingGeneralSetting
import site.lcyk.keer.data.api.MemosRole
import site.lcyk.keer.data.model.Account
import site.lcyk.keer.data.model.MemosAccount
import site.lcyk.keer.data.security.AccountKeyManager
import site.lcyk.keer.data.security.AttachmentEncryptionManager
import site.lcyk.keer.data.security.MemoContentCodec
import site.lcyk.keer.data.service.SecureAccountMasterKeyStorage
import java.io.File
import java.lang.reflect.Proxy
import java.nio.file.Files

class KeerV2RepositoryCurrentUserTest {
    @Test
    fun getCurrentUser_mapsAdminRoleFromApiUser() = runTest {
        val api = fakeApi(role = MemosRole.ADMIN)
        val repository = createRepository(api)

        val response = repository.getCurrentUser()

        assertTrue(response is ApiResponse.Success)
        val currentUser = (response as ApiResponse.Success).data
        assertEquals("ADMIN", currentUser.role)
        assertTrue(currentUser.isAdmin)
    }

    @Test
    fun getCurrentUser_keepsUserRoleAsUser() = runTest {
        val api = fakeApi(role = MemosRole.USER)
        val repository = createRepository(api)

        val response = repository.getCurrentUser()

        assertTrue(response is ApiResponse.Success)
        val currentUser = (response as ApiResponse.Success).data
        assertEquals("USER", currentUser.role)
        assertFalse(currentUser.isAdmin)
    }

    private fun createRepository(api: KeerV2Api): KeerV2Repository {
        val filesDir = Files.createTempDirectory("keer-test-files").toFile()
        val context = TestContext(filesDir)
        val secureStorage = SecureAccountMasterKeyStorage(context)
        val accountKeyManager = AccountKeyManager(secureStorage)
        val attachmentEncryptionManager = AttachmentEncryptionManager(context, accountKeyManager)

        return KeerV2Repository(
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
        )
    }

    private fun fakeApi(role: MemosRole): KeerV2Api {
        val currentUserResponse = ApiResponse.Success(
            GetCurrentUserResponse(
                user = KeerV2User(
                    name = "users/1",
                    role = role,
                    username = "cyk",
                )
            )
        )
        val userSettingResponse = ApiResponse.Success(
            KeerV2UserSetting(
                generalSetting = KeerV2UserSettingGeneralSetting()
            )
        )

        return Proxy.newProxyInstance(
            KeerV2Api::class.java.classLoader,
            arrayOf(KeerV2Api::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getCurrentUser" -> currentUserResponse
                "getUserSetting" -> userSettingResponse
                "toString" -> "FakeKeerV2Api"
                "hashCode" -> 1
                "equals" -> false
                else -> throw UnsupportedOperationException("Unexpected api call: ${method.name}")
            }
        } as KeerV2Api
    }

    private class TestContext(private val root: File) : ContextWrapper(null) {
        override fun getFilesDir(): File = root
        override fun getApplicationContext(): Context = this
    }
}
