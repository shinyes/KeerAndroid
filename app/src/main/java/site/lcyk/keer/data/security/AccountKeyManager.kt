package site.lcyk.keer.data.security

import com.skydoves.sandwich.ApiResponse
import com.skydoves.sandwich.StatusCode
import com.skydoves.sandwich.mapSuccess
import com.skydoves.sandwich.retrofit.statusCode
import site.lcyk.keer.R
import site.lcyk.keer.data.api.CreateGroupKeyVersionBody
import site.lcyk.keer.data.api.CreateGroupKeyVersionRequest
import site.lcyk.keer.data.api.KeerV2Api
import site.lcyk.keer.data.api.KeerV2Group
import site.lcyk.keer.data.api.KeerV2GroupKeyVersion
import site.lcyk.keer.data.api.KeerV2RecoveryBundle
import site.lcyk.keer.data.api.KeerV2UserEncryptionSetting
import site.lcyk.keer.data.api.KeerV2UserPublicKey
import site.lcyk.keer.data.api.UpdateUserPasswordRequest
import site.lcyk.keer.data.api.UpdateUserEncryptionSettingBody
import site.lcyk.keer.data.api.UpdateUserEncryptionSettingRequest
import site.lcyk.keer.data.model.Account
import site.lcyk.keer.data.service.SecureAccountMasterKeyStorage
import site.lcyk.keer.ext.formatString
import site.lcyk.keer.ext.string
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.security.SecureRandom
import java.security.KeyPairGenerator
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AccountKeyManager @Inject constructor(
    private val secureAccountMasterKeyStorage: SecureAccountMasterKeyStorage,
) {
    private val sharingPrivateKeyCache = ConcurrentHashMap<AccountScopedCacheKey, ByteArray>()
    private val groupKeyCache = ConcurrentHashMap<AccountScopedCacheKey, ByteArray>()

    fun onActiveAccountChanged(account: Account?) {
        val activeAccountKey = account?.accountKey().orEmpty()
        if (activeAccountKey.isBlank()) {
            sharingPrivateKeyCache.clear()
            groupKeyCache.clear()
            return
        }
        sharingPrivateKeyCache.keys
            .filter { it.accountKey != activeAccountKey }
            .forEach(sharingPrivateKeyCache::remove)
        groupKeyCache.keys
            .filter { it.accountKey != activeAccountKey }
            .forEach(groupKeyCache::remove)
    }

    fun removeAccountState(accountKey: String) {
        sharingPrivateKeyCache.keys
            .filter { it.accountKey == accountKey }
            .forEach(sharingPrivateKeyCache::remove)
        groupKeyCache.keys
            .filter { it.accountKey == accountKey }
            .forEach(groupKeyCache::remove)
        secureAccountMasterKeyStorage.removeKey(accountKey)
    }

    suspend fun ensureAccountEncryptionKey(
        account: Account.KeerV2,
        api: KeerV2Api,
        password: String,
    ): ApiResponse<Unit> {
        val normalizedAccountKey = account.accountKey()
        val settingResponse = api.getUserEncryptionSetting(account.info.id.toString())
        val remoteSetting = when (settingResponse) {
            is ApiResponse.Success -> settingResponse.data.encryptionSetting
            is ApiResponse.Failure.Error -> {
                if (settingResponse.statusCode == StatusCode.NotFound) {
                    null
                } else {
                    return ApiResponse.exception(
                        IllegalStateException(R.string.load_account_encryption_key_failed_http.formatString(settingResponse.statusCode.code))
                    )
                }
            }
            is ApiResponse.Failure.Exception -> {
                return ApiResponse.exception(
                    IllegalStateException(R.string.load_account_encryption_key_failed.string, settingResponse.throwable)
                )
            }
        }

        val localAccountMasterKey = secureAccountMasterKeyStorage.getKey(normalizedAccountKey)
        if (localAccountMasterKey != null) {
            if (remoteSetting == null) {
                return uploadAccountEncryptionKey(
                    api = api,
                    userId = account.info.id.toString(),
                    password = password,
                    accountMasterKey = localAccountMasterKey,
                )
            }
            return ApiResponse.Success(Unit)
        }

        if (remoteSetting != null) {
            val recoveredAccountMasterKey = runCatching {
                unwrapAccountMasterKey(password, remoteSetting)
            }.getOrElse { throwable ->
                return ApiResponse.exception(
                    IllegalStateException(R.string.recover_account_encryption_key_failed.string, throwable)
                )
            }
            if (!secureAccountMasterKeyStorage.saveKey(normalizedAccountKey, recoveredAccountMasterKey)) {
                return ApiResponse.exception(
                    IllegalStateException(R.string.persist_account_encryption_key_failed.string)
                )
            }
            return ApiResponse.Success(Unit)
        }

        val generatedAccountMasterKey = randomBytes(ACCOUNT_MASTER_KEY_SIZE_BYTES)
        if (!secureAccountMasterKeyStorage.saveKey(normalizedAccountKey, generatedAccountMasterKey)) {
            return ApiResponse.exception(
                IllegalStateException(R.string.persist_account_encryption_key_failed.string)
            )
        }
        return uploadAccountEncryptionKey(
            api = api,
            userId = account.info.id.toString(),
            password = password,
            accountMasterKey = generatedAccountMasterKey,
        )
    }

    suspend fun ensureAccountKeysReady(
        account: Account.KeerV2,
        api: KeerV2Api,
        password: String,
    ): ApiResponse<Unit> {
        val accountKeyResponse = ensureAccountEncryptionKey(account, api, password)
        if (accountKeyResponse !is ApiResponse.Success) {
            return accountKeyResponse
        }
        return ensureSharingKeysReady(account, api)
    }

    suspend fun ensureSharingKeysReady(
        account: Account.KeerV2,
        api: KeerV2Api,
    ): ApiResponse<Unit> {
        val settingResponse = api.getUserEncryptionSetting(account.info.id.toString())
        val setting = when (settingResponse) {
            is ApiResponse.Success -> settingResponse.data.encryptionSetting
            is ApiResponse.Failure.Error -> {
                return ApiResponse.exception(
                    IllegalStateException(R.string.load_user_encryption_setting_failed_http.formatString(settingResponse.statusCode.code))
                )
            }
            is ApiResponse.Failure.Exception -> return ApiResponse.exception(settingResponse.throwable)
        }

        if (
            setting.sharingPublicKey.isNotBlank() &&
            setting.wrappedSharingPrivateKey.isNotBlank()
        ) {
            val privateKeyBytes = unwrapSharingPrivateKey(account.accountKey(), setting)
            sharingPrivateKeyCache[sharingCacheKey(account.accountKey(), account.info.id.toString())] = privateKeyBytes
            return ApiResponse.Success(Unit)
        }

        if (secureAccountMasterKeyStorage.getKey(account.accountKey()) == null) {
            return ApiResponse.exception(IllegalStateException(R.string.missing_account_master_key.string))
        }
        val keyPairGenerator = KeyPairGenerator.getInstance("RSA")
        keyPairGenerator.initialize(2048)
        val keyPair = keyPairGenerator.generateKeyPair()
        val wrappedPrivateKey = E2eeKeyEnvelope.wrapForAccountMasterKey(
            accountKey = account.accountKey(),
            rawKey = keyPair.private.encoded,
            secureAccountMasterKeyStorage = secureAccountMasterKeyStorage,
        )
        val updateResponse = api.updateUserEncryptionSetting(
            account.info.id.toString(),
            UpdateUserEncryptionSettingRequest(
                encryptionSetting = UpdateUserEncryptionSettingBody(
                    recoveryBundle = setting.recoveryBundle,
                    sharingPublicKey = java.util.Base64.getEncoder().encodeToString(keyPair.public.encoded),
                    wrappedSharingPrivateKey = wrappedPrivateKey.wrappedKey,
                    keyVersion = setting.keyVersion.takeIf { it > 0 } ?: 1,
                    algorithms = defaultAlgorithmsJson,
                )
            )
        )
        if (updateResponse !is ApiResponse.Success) {
            return when (updateResponse) {
                is ApiResponse.Failure.Error -> ApiResponse.exception(
                    IllegalStateException(R.string.upload_sharing_key_pair_failed_http.formatString(updateResponse.statusCode.code))
                )
                is ApiResponse.Failure.Exception -> ApiResponse.exception(updateResponse.throwable)
            }
        }
        sharingPrivateKeyCache[sharingCacheKey(account.accountKey(), account.info.id.toString())] = keyPair.private.encoded
        return ApiResponse.Success(Unit)
    }

    suspend fun changePassword(
        account: Account.KeerV2,
        api: KeerV2Api,
        currentPassword: String,
        newPassword: String,
    ): ApiResponse<Unit> {
        val normalizedCurrentPassword = currentPassword.trim()
        val normalizedNewPassword = newPassword.trim()
        val settingResponse = api.getUserEncryptionSetting(account.info.id.toString())
        val setting = when (settingResponse) {
            is ApiResponse.Success -> settingResponse.data.encryptionSetting
            is ApiResponse.Failure.Error -> {
                return ApiResponse.exception(
                    IllegalStateException(R.string.load_user_encryption_setting_failed_http.formatString(settingResponse.statusCode.code))
                )
            }
            is ApiResponse.Failure.Exception -> return ApiResponse.exception(settingResponse.throwable)
        }

        val accountMasterKey = secureAccountMasterKeyStorage.getKey(account.accountKey()) ?: runCatching {
            unwrapAccountMasterKey(normalizedCurrentPassword, setting)
        }.getOrElse { throwable ->
            return ApiResponse.exception(
                IllegalStateException(R.string.recover_account_encryption_key_failed.string, throwable)
            )
        }
        if (secureAccountMasterKeyStorage.getKey(account.accountKey()) == null) {
            if (!secureAccountMasterKeyStorage.saveKey(account.accountKey(), accountMasterKey)) {
                return ApiResponse.exception(
                    IllegalStateException(R.string.persist_account_encryption_key_failed.string)
                )
            }
        }

        val updateResponse = api.updateUserPassword(
            account.info.id.toString(),
            UpdateUserPasswordRequest(
                currentPassword = normalizedCurrentPassword,
                newPassword = normalizedNewPassword,
                encryptionSetting = buildWrappedAccountKeySetting(
                    password = normalizedNewPassword,
                    accountMasterKey = accountMasterKey,
                ).copy(
                    sharingPublicKey = setting.sharingPublicKey,
                    wrappedSharingPrivateKey = setting.wrappedSharingPrivateKey,
                    keyVersion = setting.keyVersion.takeIf { it > 0 } ?: 1,
                    algorithms = setting.algorithms,
                )
            )
        )
        return updateResponse.mapSuccess { Unit }
    }

    suspend fun encryptForCollaborators(
        account: Account.KeerV2,
        api: KeerV2Api,
        collaboratorIds: List<String>,
        rawKey: ByteArray,
    ): List<WrappedContentKey> {
        ensureSharingKeysReady(account, api).getOrThrow()
        val userIds = (collaboratorIds + account.info.id.toString())
            .map { normalizeUserId(it) }
            .filter { it.isNotBlank() }
            .distinct()
        if (userIds.isEmpty()) {
            return listOf(
                E2eeKeyEnvelope.wrapForAccountMasterKey(
                    accountKey = account.accountKey(),
                    rawKey = rawKey,
                    secureAccountMasterKeyStorage = secureAccountMasterKeyStorage,
                )
            )
        }
        val users = getUserPublicKeys(api, userIds)
        return users.values.map { user ->
            E2eeKeyEnvelope.wrapForPublicKey(
                slotRef = user.name,
                publicKeyPemBase64 = user.sharingPublicKey,
                rawKey = rawKey,
            )
        }
    }

    suspend fun resolveGroupKey(
        account: Account.KeerV2,
        api: KeerV2Api,
        group: KeerV2Group,
    ): Pair<KeerV2GroupKeyVersion, ByteArray> {
        val currentVersion = ensureWritableGroupKeyVersion(account, api, group)
        groupKeyCache[groupCacheKey(account.accountKey(), currentVersion.name)]?.let { return currentVersion to it }
        ensureSharingKeysReady(account, api).getOrThrow()
        val privateKeyBytes = sharingPrivateKeyCache[sharingCacheKey(account.accountKey(), account.info.id.toString())]
            ?: unwrapSharingPrivateKey(
                account.accountKey(),
                (api.getUserEncryptionSetting(account.info.id.toString()) as? ApiResponse.Success)
                    ?.data
                    ?.encryptionSetting
                    ?: throw IllegalStateException(R.string.missing_user_encryption_setting.string)
            )
        val wrappedKey = currentVersion.wrappedKeys.firstOrNull { wrapped ->
            normalizeUserId(wrapped.slotRef) == account.info.id.toString()
        } ?: throw IllegalStateException(R.string.current_user_missing_group_key_access.string)
        val groupKey = E2eeKeyEnvelope.unwrapFirstSupportedKey(
            accountKey = account.accountKey(),
            wrappedKeys = listOf(
                WrappedContentKey(
                    slotType = wrappedKey.slotType,
                    slotRef = wrappedKey.slotRef,
                    wrapAlgorithm = wrappedKey.wrapAlgorithm,
                    wrappedKey = wrappedKey.wrappedKey,
                )
            ),
            secureAccountMasterKeyStorage = secureAccountMasterKeyStorage,
            sharingPrivateKeyResolver = { slotRef ->
                if (normalizeUserId(slotRef) == account.info.id.toString()) privateKeyBytes else null
            },
        ) ?: throw IllegalStateException(R.string.unwrap_current_group_key_failed.string)
        groupKeyCache[groupCacheKey(account.accountKey(), currentVersion.name)] = groupKey
        return currentVersion to groupKey
    }

    suspend fun loadCurrentGroupKey(
        account: Account.KeerV2,
        api: KeerV2Api,
        groupId: String,
    ): Pair<KeerV2GroupKeyVersion, ByteArray>? {
        ensureSharingKeysReady(account, api).getOrThrow()
        val response = api.getCurrentGroupKeyVersion(groupId.substringAfterLast('/'))
        val version = when (response) {
            is ApiResponse.Success -> response.data.groupKeyVersion
            is ApiResponse.Failure.Error -> {
                if (response.statusCode == StatusCode.NotFound) {
                    return null
                }
                throw IllegalStateException(R.string.load_current_group_key_version_failed_http.formatString(response.statusCode.code))
            }
            is ApiResponse.Failure.Exception -> throw response.throwable
        }
        groupKeyCache[groupCacheKey(account.accountKey(), version.name)]?.let { return version to it }
        val privateKeyBytes = sharingPrivateKeyCache[sharingCacheKey(account.accountKey(), account.info.id.toString())]
            ?: unwrapSharingPrivateKey(
                account.accountKey(),
                (api.getUserEncryptionSetting(account.info.id.toString()) as ApiResponse.Success).data.encryptionSetting,
            )
        val wrappedKey = version.wrappedKeys.firstOrNull { wrapped ->
            normalizeUserId(wrapped.slotRef) == account.info.id.toString()
        } ?: return null
        val groupKey = E2eeKeyEnvelope.unwrapFirstSupportedKey(
            accountKey = account.accountKey(),
            wrappedKeys = listOf(
                WrappedContentKey(
                    slotType = wrappedKey.slotType,
                    slotRef = wrappedKey.slotRef,
                    wrapAlgorithm = wrappedKey.wrapAlgorithm,
                    wrappedKey = wrappedKey.wrappedKey,
                )
            ),
            secureAccountMasterKeyStorage = secureAccountMasterKeyStorage,
            sharingPrivateKeyResolver = { slotRef ->
                if (normalizeUserId(slotRef) == account.info.id.toString()) privateKeyBytes else null
            },
        ) ?: return null
        groupKeyCache[groupCacheKey(account.accountKey(), version.name)] = groupKey
        return version to groupKey
    }

    suspend fun encryptForGroupVersion(
        account: Account.KeerV2,
        api: KeerV2Api,
        group: KeerV2Group,
        rawKey: ByteArray,
    ): WrappedContentKey {
        val (version, groupKey) = resolveGroupKey(account, api, group)
        return E2eeKeyEnvelope.wrapForGroupKey(
            slotRef = version.name,
            groupKey = groupKey,
            rawKey = rawKey,
        )
    }

    fun unwrapContentKey(
        account: Account.KeerV2,
        wrappedKeys: List<WrappedContentKey>,
    ): ByteArray? {
        return E2eeKeyEnvelope.unwrapFirstSupportedKey(
            accountKey = account.accountKey(),
            wrappedKeys = wrappedKeys,
            secureAccountMasterKeyStorage = secureAccountMasterKeyStorage,
            sharingPrivateKeyResolver = { slotRef ->
                sharingPrivateKeyCache[sharingCacheKey(account.accountKey(), slotRef)]
            },
            groupKeyResolver = { slotRef ->
                groupKeyCache[groupCacheKey(account.accountKey(), slotRef)]
            },
        )
    }

    fun wrapForAccountMasterKey(
        account: Account.KeerV2,
        rawKey: ByteArray,
    ): WrappedContentKey {
        return E2eeKeyEnvelope.wrapForAccountMasterKey(
            accountKey = account.accountKey(),
            rawKey = rawKey,
            secureAccountMasterKeyStorage = secureAccountMasterKeyStorage,
        )
    }

    fun getCachedSharingPrivateKey(accountKey: String, slotRef: String): ByteArray? {
        val normalizedAccountKey = accountKey.trim()
        if (normalizedAccountKey.isEmpty()) {
            return null
        }
        val cacheKey = sharingCacheKey(normalizedAccountKey, slotRef)
        return sharingPrivateKeyCache[cacheKey]?.copyOf()
    }

    fun getCachedGroupKey(accountKey: String, slotRef: String): ByteArray? {
        val normalizedAccountKey = accountKey.trim()
        if (normalizedAccountKey.isEmpty()) {
            return null
        }
        val cacheKey = groupCacheKey(normalizedAccountKey, slotRef)
        return groupKeyCache[cacheKey]?.copyOf()
    }

    fun applyStreamEncryptionSetting(
        account: Account.KeerV2,
        setting: KeerV2UserEncryptionSetting,
    ): Boolean {
        val accountKey = account.accountKey()
        if (setting.sharingPublicKey.isBlank() || setting.wrappedSharingPrivateKey.isBlank()) {
            sharingPrivateKeyCache.remove(sharingCacheKey(accountKey, account.info.id.toString()))
            return false
        }
        val privateKey = runCatching {
            unwrapSharingPrivateKey(accountKey, setting)
        }.getOrNull() ?: return false
        sharingPrivateKeyCache[sharingCacheKey(accountKey, account.info.id.toString())] = privateKey
        return true
    }

    fun applyStreamGroupKeyVersion(
        account: Account.KeerV2,
        version: KeerV2GroupKeyVersion,
    ): Boolean {
        val accountKey = account.accountKey()
        val currentUserId = account.info.id.toString()
        val privateKeyBytes = sharingPrivateKeyCache[sharingCacheKey(accountKey, currentUserId)]
            ?: return false
        val visibleWrappedKey = version.wrappedKeys.firstOrNull { wrapped ->
            normalizeUserId(wrapped.slotRef) == currentUserId
        } ?: return false
        val groupKey = E2eeKeyEnvelope.unwrapFirstSupportedKey(
            accountKey = accountKey,
            wrappedKeys = listOf(
                WrappedContentKey(
                    slotType = visibleWrappedKey.slotType,
                    slotRef = visibleWrappedKey.slotRef,
                    wrapAlgorithm = visibleWrappedKey.wrapAlgorithm,
                    wrappedKey = visibleWrappedKey.wrappedKey,
                )
            ),
            secureAccountMasterKeyStorage = secureAccountMasterKeyStorage,
            sharingPrivateKeyResolver = { slotRef ->
                if (normalizeUserId(slotRef) == currentUserId) privateKeyBytes else null
            },
        ) ?: return false
        groupKeyCache[groupCacheKey(accountKey, version.name)] = groupKey
        return true
    }

    fun removeCachedGroupKeyVersion(
        account: Account.KeerV2,
        versionName: String,
    ) {
        val normalizedName = versionName.trim()
        if (normalizedName.isEmpty()) {
            return
        }
        groupKeyCache.remove(groupCacheKey(account.accountKey(), normalizedName))
    }

    private suspend fun ensureWritableGroupKeyVersion(
        account: Account.KeerV2,
        api: KeerV2Api,
        group: KeerV2Group,
    ): KeerV2GroupKeyVersion {
        val currentResponse = api.getCurrentGroupKeyVersion(group.name.substringAfterLast('/'))
        if (currentResponse is ApiResponse.Success) {
            val current = currentResponse.data.groupKeyVersion
            val memberRefs = group.members.map { member -> member.name }.sorted()
            val wrappedRefs = current.wrappedKeys.map { wrapped -> wrapped.slotRef }.sorted()
            if (memberRefs == wrappedRefs && wrappedRefs.isNotEmpty()) {
                return current
            }
        } else if (currentResponse is ApiResponse.Failure.Error && currentResponse.statusCode != StatusCode.NotFound) {
            throw IllegalStateException(R.string.load_current_group_key_version_failed_http.formatString(currentResponse.statusCode.code))
        }
        return rotateGroupKeyVersion(account, api, group)
    }

    private suspend fun rotateGroupKeyVersion(
        account: Account.KeerV2,
        api: KeerV2Api,
        group: KeerV2Group,
    ): KeerV2GroupKeyVersion {
        ensureSharingKeysReady(account, api).getOrThrow()
        val publicKeys = getUserPublicKeys(api, group.members.map { it.name })
        val groupKey = E2eeKeyEnvelope.randomBytes(32)
        val wrappedKeys = publicKeys.values.map { user ->
            val wrapped = E2eeKeyEnvelope.wrapForPublicKey(
                slotRef = user.name,
                publicKeyPemBase64 = user.sharingPublicKey,
                rawKey = groupKey,
            )
            site.lcyk.keer.data.api.KeerV2WrappedKeySlot(
                slotType = wrapped.slotType,
                slotRef = wrapped.slotRef,
                wrapAlgorithm = wrapped.wrapAlgorithm,
                wrappedKey = wrapped.wrappedKey,
            )
        }
        val response = api.createGroupKeyVersion(
            group.name.substringAfterLast('/'),
            CreateGroupKeyVersionRequest(
                groupKeyVersion = CreateGroupKeyVersionBody(
                    algorithm = E2eeKeyEnvelope.GROUP_KEY_WRAP_ALGORITHM,
                    wrappedKeys = wrappedKeys,
                )
            )
        )
        val created = when (response) {
            is ApiResponse.Success -> response.data.groupKeyVersion
            is ApiResponse.Failure.Error -> throw IllegalStateException(
                R.string.create_group_key_version_failed_http.formatString(response.statusCode.code)
            )
            is ApiResponse.Failure.Exception -> throw response.throwable
        }
        groupKeyCache[groupCacheKey(account.accountKey(), created.name)] = groupKey
        return created
    }

    private suspend fun getUserPublicKeys(
        api: KeerV2Api,
        userIds: List<String>,
    ): Map<String, KeerV2UserPublicKey> {
        if (userIds.isEmpty()) {
            return emptyMap()
        }
        val response = api.getUserPublicKeysBatch(userIds.joinToString(","))
        val users = when (response) {
            is ApiResponse.Success -> response.data.users
            is ApiResponse.Failure.Error -> throw IllegalStateException(
                R.string.load_user_public_keys_failed_http.formatString(response.statusCode.code)
            )
            is ApiResponse.Failure.Exception -> throw response.throwable
        }
        return users.associateBy { user -> normalizeUserId(user.name) }
    }

    private suspend fun uploadAccountEncryptionKey(
        api: KeerV2Api,
        userId: String,
        password: String,
        accountMasterKey: ByteArray,
    ): ApiResponse<Unit> {
        return api.updateUserEncryptionSetting(
            userId,
            UpdateUserEncryptionSettingRequest(
                encryptionSetting = buildWrappedAccountKeySetting(
                    password = password,
                    accountMasterKey = accountMasterKey,
                )
            )
        ).mapSuccess { }
    }

    private fun buildWrappedAccountKeySetting(
        password: String,
        accountMasterKey: ByteArray,
    ): UpdateUserEncryptionSettingBody {
        val salt = randomBytes(ACCOUNT_MASTER_KEY_KDF_SALT_SIZE_BYTES)
        val derivedKey = derivePasswordWrappingKey(
            password = password,
            salt = salt,
            timeCost = ACCOUNT_MASTER_KEY_KDF_TIME_COST,
            memoryKiB = ACCOUNT_MASTER_KEY_KDF_MEMORY_KIB,
            parallelism = ACCOUNT_MASTER_KEY_KDF_PARALLELISM,
        )
        val iv = randomBytes(ACCOUNT_MASTER_KEY_WRAP_IV_SIZE_BYTES)
        val cipher = Cipher.getInstance(ACCOUNT_MASTER_KEY_WRAP_TRANSFORMATION)
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(derivedKey, ACCOUNT_MASTER_KEY_WRAP_KEY_ALGORITHM),
            GCMParameterSpec(ACCOUNT_MASTER_KEY_GCM_TAG_LENGTH_BITS, iv),
        )
        val wrappedBytes = cipher.doFinal(accountMasterKey)
        return UpdateUserEncryptionSettingBody(
            recoveryBundle = KeerV2RecoveryBundle(
                version = ACCOUNT_MASTER_KEY_SYNC_VERSION,
                kdfAlgorithm = ACCOUNT_MASTER_KEY_KDF_ALGORITHM,
                kdfSalt = Base64.getEncoder().encodeToString(salt),
                kdfTimeCost = ACCOUNT_MASTER_KEY_KDF_TIME_COST,
                kdfMemoryKiB = ACCOUNT_MASTER_KEY_KDF_MEMORY_KIB,
                kdfParallelism = ACCOUNT_MASTER_KEY_KDF_PARALLELISM,
                wrapAlgorithm = ACCOUNT_MASTER_KEY_WRAP_ALGORITHM,
                wrappedAccountKey = listOf(
                    Base64.getEncoder().encodeToString(iv),
                    Base64.getEncoder().encodeToString(wrappedBytes),
                ).joinToString(":"),
            ),
            sharingPublicKey = "",
            wrappedSharingPrivateKey = "",
            keyVersion = 1,
            algorithms = "",
        )
    }

    private fun unwrapAccountMasterKey(
        password: String,
        setting: KeerV2UserEncryptionSetting,
    ): ByteArray {
        val bundle = setting.recoveryBundle
        require(bundle.version == ACCOUNT_MASTER_KEY_SYNC_VERSION) {
            "Unsupported account encryption key version"
        }
        require(bundle.kdfAlgorithm == ACCOUNT_MASTER_KEY_KDF_ALGORITHM) {
            "Unsupported account encryption key derivation"
        }
        require(bundle.wrapAlgorithm == ACCOUNT_MASTER_KEY_WRAP_ALGORITHM) {
            "Unsupported account encryption key wrapping algorithm"
        }
        require(bundle.kdfTimeCost > 0) {
            "Invalid account encryption key time cost"
        }
        require(bundle.kdfMemoryKiB > 0) {
            "Invalid account encryption key memory cost"
        }
        require(bundle.kdfParallelism > 0) {
            "Invalid account encryption key parallelism"
        }
        val parts = bundle.wrappedAccountKey.split(':', limit = 2)
        require(parts.size == 2) { "Invalid wrapped account key payload" }
        val salt = Base64.getDecoder().decode(bundle.kdfSalt)
        val iv = Base64.getDecoder().decode(parts[0])
        val ciphertext = Base64.getDecoder().decode(parts[1])
        val derivedKey = derivePasswordWrappingKey(
            password = password,
            salt = salt,
            timeCost = bundle.kdfTimeCost,
            memoryKiB = bundle.kdfMemoryKiB,
            parallelism = bundle.kdfParallelism,
        )
        val cipher = Cipher.getInstance(ACCOUNT_MASTER_KEY_WRAP_TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(derivedKey, ACCOUNT_MASTER_KEY_WRAP_KEY_ALGORITHM),
            GCMParameterSpec(ACCOUNT_MASTER_KEY_GCM_TAG_LENGTH_BITS, iv),
        )
        return cipher.doFinal(ciphertext)
    }

    private fun derivePasswordWrappingKey(
        password: String,
        salt: ByteArray,
        timeCost: Int,
        memoryKiB: Int,
        parallelism: Int,
    ): ByteArray {
        val passwordBytes = password.toByteArray(Charsets.UTF_8)
        return try {
            val parameters = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withSalt(salt)
                .withIterations(timeCost)
                .withMemoryAsKB(memoryKiB)
                .withParallelism(parallelism)
                .build()
            val generator = Argon2BytesGenerator()
            generator.init(parameters)
            ByteArray(ACCOUNT_MASTER_KEY_SIZE_BYTES).also { derivedKey ->
                generator.generateBytes(passwordBytes, derivedKey, 0, derivedKey.size)
            }
        } finally {
            passwordBytes.fill(0)
        }
    }

    private fun unwrapSharingPrivateKey(accountKey: String, setting: KeerV2UserEncryptionSetting): ByteArray {
        val wrappedKey = WrappedContentKey(
            slotType = E2eeKeyEnvelope.SLOT_TYPE_ACCOUNT_MASTER,
            slotRef = E2eeKeyEnvelope.accountMasterKeyRef(accountKey),
            wrapAlgorithm = E2eeKeyEnvelope.ACCOUNT_MASTER_KEY_WRAP_ALGORITHM,
            wrappedKey = setting.wrappedSharingPrivateKey,
        )
        return requireNotNull(
            E2eeKeyEnvelope.unwrapFirstSupportedKey(
                accountKey = accountKey,
                wrappedKeys = listOf(wrappedKey),
                secureAccountMasterKeyStorage = secureAccountMasterKeyStorage,
            )
        ) {
            "Unable to unwrap sharing private key"
        }
    }

    private fun normalizeUserId(raw: String): String {
        return raw.trim().substringBefore('|').substringAfterLast('/').trim()
    }

    private fun sharingCacheKey(accountKey: String, userIdOrRef: String): AccountScopedCacheKey {
        return AccountScopedCacheKey(
            accountKey = accountKey,
            ref = normalizeUserId(userIdOrRef),
        )
    }

    private fun groupCacheKey(accountKey: String, versionName: String): AccountScopedCacheKey {
        return AccountScopedCacheKey(
            accountKey = accountKey,
            ref = versionName.trim(),
        )
    }

    private fun randomBytes(size: Int): ByteArray {
        val bytes = ByteArray(size)
        SecureRandom().nextBytes(bytes)
        return bytes
    }

        private companion object {
        const val defaultAlgorithmsJson =
            "{\"accountMasterWrap\":\"AES_GCM_ACCOUNT_MASTER_KEY_V1\",\"accountPublicWrap\":\"RSA_OAEP_SHA256_V1\",\"groupKeyWrap\":\"AES_GCM_GROUP_KEY_V1\"}"
        private const val ACCOUNT_MASTER_KEY_SYNC_VERSION = 2
        private const val ACCOUNT_MASTER_KEY_SIZE_BYTES = 32
        private const val ACCOUNT_MASTER_KEY_KDF_SALT_SIZE_BYTES = 16
        private const val ACCOUNT_MASTER_KEY_WRAP_IV_SIZE_BYTES = 12
        private const val ACCOUNT_MASTER_KEY_KDF_TIME_COST = 3
        private const val ACCOUNT_MASTER_KEY_KDF_MEMORY_KIB = 32 * 1024
        private const val ACCOUNT_MASTER_KEY_KDF_PARALLELISM = 1
        private const val ACCOUNT_MASTER_KEY_GCM_TAG_LENGTH_BITS = 128
        private const val ACCOUNT_MASTER_KEY_KDF_ALGORITHM = "ARGON2ID"
        private const val ACCOUNT_MASTER_KEY_WRAP_ALGORITHM = "AES_GCM"
        private const val ACCOUNT_MASTER_KEY_WRAP_KEY_ALGORITHM = "AES"
        private const val ACCOUNT_MASTER_KEY_WRAP_TRANSFORMATION = "AES/GCM/NoPadding"
    }
}

private data class AccountScopedCacheKey(
    val accountKey: String,
    val ref: String,
)

private fun ApiResponse<Unit>.getOrThrow() {
    when (this) {
        is ApiResponse.Success -> return
        is ApiResponse.Failure.Error -> throw IllegalStateException("HTTP ${statusCode.code}")
        is ApiResponse.Failure.Exception -> throw throwable
    }
}
