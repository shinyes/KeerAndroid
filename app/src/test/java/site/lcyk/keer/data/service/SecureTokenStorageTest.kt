package site.lcyk.keer.data.service

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class SecureTokenStorageTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun saveTokens_immediatelyReadable() {
        val accountKey = "test:${UUID.randomUUID()}"
        val storage = createStorage(accountKey)

        storage.saveTokens(
            accountKey = accountKey,
            accessToken = "access-token",
            refreshToken = "refresh-token",
        )

        val stored = storage.getTokens(accountKey)

        assertNotNull(stored)
        assertEquals("access-token", stored!!.accessToken)
        assertEquals("refresh-token", stored.refreshToken)

        storage.removeToken(accountKey)
    }

    @Test
    fun removeToken_clearsPersistedValue() {
        val accountKey = "test:${UUID.randomUUID()}"
        val storage = createStorage(accountKey)

        storage.saveTokens(
            accountKey = accountKey,
            accessToken = "access-token",
            refreshToken = "refresh-token",
        )

        storage.removeToken(accountKey)

        assertNull(storage.getTokens(accountKey))
    }

    private fun createStorage(accountKey: String): SecureTokenStorage {
        val sharedPreferences = context.getSharedPreferences(
            "secure_tokens_test_${accountKey.hashCode()}",
            Context.MODE_PRIVATE,
        )
        sharedPreferences.edit().clear().commit()
        return SecureTokenStorage(
            context = context,
            sharedPreferences = sharedPreferences,
            encryptor = { plainText -> "enc::$plainText" },
            decryptor = { payload ->
                if (payload.startsWith("enc::")) {
                    payload.removePrefix("enc::")
                } else {
                    null
                }
            },
        )
    }
}
