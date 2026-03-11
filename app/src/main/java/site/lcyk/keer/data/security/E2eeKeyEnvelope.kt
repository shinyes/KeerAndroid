package site.lcyk.keer.data.security

import kotlinx.serialization.Serializable
import site.lcyk.keer.data.service.SecureAccountMasterKeyStorage
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

@Serializable
data class WrappedContentKey(
    val slotType: String,
    val slotRef: String,
    val wrapAlgorithm: String,
    val wrappedKey: String,
)

internal object E2eeKeyEnvelope {
    const val SLOT_TYPE_ACCOUNT_MASTER = "account_master"
    const val SLOT_TYPE_ACCOUNT_PUBLIC = "account_public"
    const val SLOT_TYPE_GROUP_KEY_VERSION = "group_key_version"

    const val ACCOUNT_MASTER_KEY_WRAP_ALGORITHM = "AES_GCM_ACCOUNT_MASTER_KEY_V1"
    const val ACCOUNT_PUBLIC_KEY_WRAP_ALGORITHM = "RSA_OAEP_SHA256_V1"
    const val GROUP_KEY_WRAP_ALGORITHM = "AES_GCM_GROUP_KEY_V1"

    fun wrapForAccountMasterKey(
        accountKey: String,
        rawKey: ByteArray,
        secureAccountMasterKeyStorage: SecureAccountMasterKeyStorage,
    ): WrappedContentKey {
        val normalizedAccountKey = accountKey.trim()
        require(normalizedAccountKey.isNotEmpty()) { "Missing account key" }
        val accountMasterKey = requireNotNull(secureAccountMasterKeyStorage.getKey(normalizedAccountKey)) {
            "Missing account master key"
        }
        return WrappedContentKey(
            slotType = SLOT_TYPE_ACCOUNT_MASTER,
            slotRef = accountMasterKeyRef(normalizedAccountKey),
            wrapAlgorithm = ACCOUNT_MASTER_KEY_WRAP_ALGORITHM,
            wrappedKey = wrapWithAesGcm(accountMasterKey, rawKey),
        )
    }

    fun wrapForPublicKey(
        slotRef: String,
        publicKeyPemBase64: String,
        rawKey: ByteArray,
    ): WrappedContentKey {
        val publicKey = decodePublicKey(publicKeyPemBase64)
        return wrapForPublicKey(slotRef, publicKey, rawKey)
    }

    fun wrapForPublicKey(
        slotRef: String,
        publicKey: PublicKey,
        rawKey: ByteArray,
    ): WrappedContentKey {
        val cipher = Cipher.getInstance(RSA_WRAP_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, publicKey)
        return WrappedContentKey(
            slotType = SLOT_TYPE_ACCOUNT_PUBLIC,
            slotRef = slotRef.trim(),
            wrapAlgorithm = ACCOUNT_PUBLIC_KEY_WRAP_ALGORITHM,
            wrappedKey = base64Encode(cipher.doFinal(rawKey)),
        )
    }

    fun wrapForGroupKey(
        slotRef: String,
        groupKey: ByteArray,
        rawKey: ByteArray,
    ): WrappedContentKey {
        return WrappedContentKey(
            slotType = SLOT_TYPE_GROUP_KEY_VERSION,
            slotRef = slotRef.trim(),
            wrapAlgorithm = GROUP_KEY_WRAP_ALGORITHM,
            wrappedKey = wrapWithAesGcm(groupKey, rawKey),
        )
    }

    fun unwrapFirstSupportedKey(
        accountKey: String?,
        wrappedKeys: List<WrappedContentKey>,
        secureAccountMasterKeyStorage: SecureAccountMasterKeyStorage,
        sharingPrivateKeyResolver: ((String) -> ByteArray?)? = null,
        groupKeyResolver: ((String) -> ByteArray?)? = null,
    ): ByteArray? {
        val normalizedAccountKey = accountKey?.trim().orEmpty()
        val accountMasterKey = if (normalizedAccountKey.isNotEmpty()) {
            secureAccountMasterKeyStorage.getKey(normalizedAccountKey)
        } else {
            null
        }
        val expectedAccountSlotRef = if (normalizedAccountKey.isNotEmpty()) {
            accountMasterKeyRef(normalizedAccountKey)
        } else {
            ""
        }
        wrappedKeys.forEach { wrappedKey ->
            when (wrappedKey.slotType) {
                SLOT_TYPE_ACCOUNT_MASTER -> {
                    if (
                        wrappedKey.wrapAlgorithm != ACCOUNT_MASTER_KEY_WRAP_ALGORITHM ||
                        accountMasterKey == null ||
                        wrappedKey.slotRef != expectedAccountSlotRef
                    ) {
                        return@forEach
                    }
                    runCatching {
                        return unwrapWithAesGcm(accountMasterKey, wrappedKey.wrappedKey)
                    }
                }

                SLOT_TYPE_ACCOUNT_PUBLIC -> {
                    if (wrappedKey.wrapAlgorithm != ACCOUNT_PUBLIC_KEY_WRAP_ALGORITHM) {
                        return@forEach
                    }
                    val privateKeyBytes = sharingPrivateKeyResolver?.invoke(wrappedKey.slotRef) ?: return@forEach
                    runCatching {
                        val cipher = Cipher.getInstance(RSA_WRAP_TRANSFORMATION)
                        cipher.init(Cipher.DECRYPT_MODE, decodePrivateKey(privateKeyBytes))
                        return cipher.doFinal(base64Decode(wrappedKey.wrappedKey))
                    }
                }

                SLOT_TYPE_GROUP_KEY_VERSION -> {
                    if (wrappedKey.wrapAlgorithm != GROUP_KEY_WRAP_ALGORITHM) {
                        return@forEach
                    }
                    val groupKey = groupKeyResolver?.invoke(wrappedKey.slotRef) ?: return@forEach
                    runCatching {
                        return unwrapWithAesGcm(groupKey, wrappedKey.wrappedKey)
                    }
                }
            }
        }
        return null
    }

    fun accountMasterKeyRef(accountKey: String): String {
        return "amk:${sha256Hex(accountKey.trim())}"
    }

    fun accountPublicKeyRef(userIdentifier: String): String {
        val normalized = userIdentifier.trim().trim('/').ifEmpty { userIdentifier.trim() }
        return if (normalized.startsWith("users/")) normalized else "users/$normalized"
    }

    fun groupKeyVersionRef(groupIdentifier: String, version: Int): String {
        val normalizedGroup = groupIdentifier.trim().trim('/').ifEmpty { groupIdentifier.trim() }
        val groupRef = if (normalizedGroup.startsWith("groups/")) normalizedGroup else "groups/$normalizedGroup"
        return "$groupRef/keyVersions/$version"
    }

    fun decodePublicKey(encoded: String): PublicKey {
        val spec = X509EncodedKeySpec(base64Decode(encoded))
        return KeyFactory.getInstance(RSA_KEY_ALGORITHM).generatePublic(spec)
    }

    fun decodePrivateKey(encoded: ByteArray): PrivateKey {
        val spec = PKCS8EncodedKeySpec(encoded)
        return KeyFactory.getInstance(RSA_KEY_ALGORITHM).generatePrivate(spec)
    }

    fun randomBytes(size: Int): ByteArray {
        val bytes = ByteArray(size)
        SecureRandom().nextBytes(bytes)
        return bytes
    }

    private fun wrapWithAesGcm(wrapKey: ByteArray, rawKey: ByteArray): String {
        val cipher = Cipher.getInstance(AES_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(wrapKey, AES_KEY_ALGORITHM))
        return "${base64Encode(cipher.iv)}:${base64Encode(cipher.doFinal(rawKey))}"
    }

    private fun unwrapWithAesGcm(wrapKey: ByteArray, payload: String): ByteArray {
        val parts = payload.split(':', limit = 2)
        require(parts.size == 2) { "Invalid wrapped key payload" }
        val iv = base64Decode(parts[0])
        val ciphertext = base64Decode(parts[1])
        val cipher = Cipher.getInstance(AES_TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(wrapKey, AES_KEY_ALGORITHM),
            GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv),
        )
        return cipher.doFinal(ciphertext)
    }

    private fun sha256Hex(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(value.toByteArray(StandardCharsets.UTF_8))
        return buildString(bytes.size * 2) {
            bytes.forEach { byte ->
                val b = byte.toInt() and 0xFF
                append("0123456789abcdef"[b ushr 4])
                append("0123456789abcdef"[b and 0x0F])
            }
        }
    }

    private fun base64Encode(bytes: ByteArray): String {
        return Base64.getEncoder().encodeToString(bytes)
    }

    private fun base64Decode(value: String): ByteArray {
        return Base64.getDecoder().decode(value)
    }

    private const val AES_KEY_ALGORITHM = "AES"
    private const val AES_TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_LENGTH_BITS = 128
    private const val RSA_KEY_ALGORITHM = "RSA"
    private const val RSA_WRAP_TRANSFORMATION = "RSA/ECB/OAEPWithSHA-256AndMGF1Padding"
}
