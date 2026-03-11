package site.lcyk.keer.data.service

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SecureAccountMasterKeyStorage @Inject constructor(
    @ApplicationContext context: Context
) {
    private val sharedPreferences: SharedPreferences by lazy {
        context.getSharedPreferences(KEYS_FILE_NAME, Context.MODE_PRIVATE)
    }

    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
    }

    fun getKey(accountKey: String): ByteArray? {
        val encrypted = sharedPreferences.getString(accountKey.trim(), null) ?: return null
        return decrypt(encrypted) ?: run {
            removeKey(accountKey)
            null
        }
    }

    fun saveKey(accountKey: String, rawKey: ByteArray): Boolean {
        val normalizedAccountKey = accountKey.trim()
        if (normalizedAccountKey.isEmpty() || rawKey.isEmpty()) {
            return false
        }
        val encrypted = encrypt(rawKey.copyOf()) ?: return false
        sharedPreferences.edit()
            .putString(normalizedAccountKey, encrypted)
            .apply()
        return true
    }

    fun removeKey(accountKey: String) {
        sharedPreferences.edit()
            .remove(accountKey.trim())
            .apply()
    }

    private fun encrypt(plainBytes: ByteArray): String? {
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
            val cipherBytes = cipher.doFinal(plainBytes)
            "${base64Encode(cipher.iv)}:${base64Encode(cipherBytes)}"
        } catch (_: GeneralSecurityException) {
            null
        }
    }

    private fun decrypt(payload: String): ByteArray? {
        val parts = payload.split(':', limit = 2)
        if (parts.size != 2) {
            return null
        }

        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateSecretKey(),
                GCMParameterSpec(GCM_TAG_LENGTH_BITS, base64Decode(parts[0])),
            )
            cipher.doFinal(base64Decode(parts[1]))
        } catch (_: GeneralSecurityException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun getOrCreateSecretKey(): SecretKey {
        val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) {
            return existing
        }

        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEY_STORE,
        )
        val keyGenSpec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build()
        keyGenerator.init(keyGenSpec)
        return keyGenerator.generateKey()
    }

    private fun base64Encode(bytes: ByteArray): String {
        return Base64.getEncoder().encodeToString(bytes)
    }

    private fun base64Decode(value: String): ByteArray {
        return Base64.getDecoder().decode(value)
    }

    companion object {
        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "Keer.account_master_keys.aesgcm"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_LENGTH_BITS = 128
        private const val KEYS_FILE_NAME = "secure_account_master_keys"
    }
}
