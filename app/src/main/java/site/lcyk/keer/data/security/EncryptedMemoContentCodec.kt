package site.lcyk.keer.data.security

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import site.lcyk.keer.data.model.Account
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class EncryptedMemoContentCodec(
    private val account: Account.KeerV2,
    private val accountKeyManager: AccountKeyManager,
) : MemoContentCodec {
    override fun encode(plainText: String): String {
        val contentKey = E2eeKeyEnvelope.randomBytes(CONTENT_KEY_SIZE_BYTES)
        val wrappedKey = accountKeyManager.wrapForAccountMasterKey(account, contentKey)
        val iv = E2eeKeyEnvelope.randomBytes(CONTENT_IV_SIZE_BYTES)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(contentKey, KEY_ALGORITHM),
            GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv),
        )
        val ciphertext = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        val envelope = EncryptedMemoContentEnvelope(
            wrappedKeys = listOf(wrappedKey),
            iv = Base64.getEncoder().encodeToString(iv),
            ciphertext = Base64.getEncoder().encodeToString(ciphertext),
        )
        return ENVELOPE_PREFIX + json.encodeToString(
            EncryptedMemoContentEnvelope.serializer(),
            envelope,
        )
    }

    override fun decode(storedText: String): String {
        if (!isEncoded(storedText)) {
            return storedText
        }
        val rawEnvelope = storedText.removePrefix(ENVELOPE_PREFIX)
        val envelope = json.decodeFromString(EncryptedMemoContentEnvelope.serializer(), rawEnvelope)
        require(envelope.version == ENVELOPE_VERSION) { "Unsupported memo content version" }
        require(envelope.algorithm == ALGORITHM) { "Unsupported memo content algorithm" }
        val contentKey = requireNotNull(
            accountKeyManager.unwrapContentKey(account, envelope.wrappedKeys)
        ) {
            "No supported memo content key slot"
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(contentKey, KEY_ALGORITHM),
            GCMParameterSpec(GCM_TAG_LENGTH_BITS, Base64.getDecoder().decode(envelope.iv)),
        )
        val plaintext = cipher.doFinal(Base64.getDecoder().decode(envelope.ciphertext))
        return plaintext.toString(Charsets.UTF_8)
    }

    override fun isEncoded(storedText: String): Boolean {
        return storedText.startsWith(ENVELOPE_PREFIX)
    }

    @Serializable
    private data class EncryptedMemoContentEnvelope(
        val version: Int = ENVELOPE_VERSION,
        val algorithm: String = ALGORITHM,
        val payloadType: String = PAYLOAD_TYPE_TEXT_UTF8,
        val wrappedKeys: List<WrappedContentKey> = emptyList(),
        val iv: String,
        val ciphertext: String,
    )

    companion object {
        private const val ENVELOPE_PREFIX = "keer-e2ee:"
        private const val ENVELOPE_VERSION = 1
        private const val ALGORITHM = "AES_GCM_TEXT_V1"
        private const val PAYLOAD_TYPE_TEXT_UTF8 = "TEXT_UTF8"
        private const val KEY_ALGORITHM = "AES"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val CONTENT_KEY_SIZE_BYTES = 32
        private const val CONTENT_IV_SIZE_BYTES = 12
        private const val GCM_TAG_LENGTH_BITS = 128
        private val json = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
            explicitNulls = false
        }
    }
}
