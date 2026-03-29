package site.lcyk.keer.data.service

import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import site.lcyk.keer.data.model.MemosAccount
import site.lcyk.keer.data.model.UserData

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class AuthSessionManagerTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val secureTokenStorage = mockk<SecureTokenStorage>()
    private val authSessionManager = AuthSessionManager(
        context = context,
        okHttpClient = OkHttpClient(),
        secureTokenStorage = secureTokenStorage,
    )

    @Test
    fun parseAccountWithStoredTokens_accessTokenOnly_returnsNull() {
        val userData = sampleUserData()
        every { secureTokenStorage.getTokens(userData.accountKey) } returns SecureTokenStorage.StoredTokens(
            accessToken = "access-token",
            refreshToken = "",
        )

        val restored = authSessionManager.parseAccountWithStoredTokens(userData)

        assertNull(restored)
    }

    @Test
    fun parseAccountWithStoredTokens_refreshTokenOnly_returnsNull() {
        val userData = sampleUserData()
        every { secureTokenStorage.getTokens(userData.accountKey) } returns SecureTokenStorage.StoredTokens(
            accessToken = "",
            refreshToken = "refresh-token",
        )

        val restored = authSessionManager.parseAccountWithStoredTokens(userData)

        assertNull(restored)
    }

    @Test
    fun parseAccountWithStoredTokens_bothTokensPresent_restoresAccount() {
        val userData = sampleUserData()
        every { secureTokenStorage.getTokens(userData.accountKey) } returns SecureTokenStorage.StoredTokens(
            accessToken = "access-token",
            refreshToken = "refresh-token",
        )

        val restored = authSessionManager.parseAccountWithStoredTokens(userData)

        assertNotNull(restored)
        val account = restored as site.lcyk.keer.data.model.Account.KeerV2
        assertEquals("access-token", account.info.accessToken)
        assertEquals("refresh-token", account.info.refreshToken)
    }

    @Test
    fun parseAccountWithStoredTokens_bothTokensMissing_returnsNull() {
        val userData = sampleUserData()
        every { secureTokenStorage.getTokens(userData.accountKey) } returns null

        val restored = authSessionManager.parseAccountWithStoredTokens(userData)

        assertNull(restored)
    }

    private fun sampleUserData(): UserData {
        return UserData(
            accountKey = "memos:https://keer.example:1",
            keerV2 = MemosAccount(
                host = "https://keer.example",
                id = 1L,
                name = "cyk",
            )
        )
    }
}
