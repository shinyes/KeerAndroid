package site.lcyk.keer.data.service

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.webkit.MimeTypeMap
import androidx.core.net.toUri
import com.skydoves.sandwich.ApiResponse
import com.skydoves.sandwich.getOrThrow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import dagger.hilt.android.qualifiers.ApplicationContext
import site.lcyk.keer.R
import site.lcyk.keer.data.api.AuthSessionResponse
import site.lcyk.keer.data.api.CreateUserBody
import site.lcyk.keer.data.api.CreateUserRequest
import site.lcyk.keer.data.api.KeerV2Api
import site.lcyk.keer.data.api.PasswordCredentials
import site.lcyk.keer.data.api.SignInRequest
import site.lcyk.keer.data.api.UpdateUserAvatarUpload
import site.lcyk.keer.data.api.UpdateUserBody
import site.lcyk.keer.data.api.UpdateUserRequest
import site.lcyk.keer.data.local.FileStorage
import site.lcyk.keer.data.local.KeerDatabase
import site.lcyk.keer.data.local.entity.ResourceEntity
import site.lcyk.keer.data.model.Account
import site.lcyk.keer.data.model.LocalAccount
import site.lcyk.keer.data.model.MemosAccount
import site.lcyk.keer.data.model.User
import site.lcyk.keer.data.model.UserData
import site.lcyk.keer.data.model.UserSettings
import site.lcyk.keer.data.repository.AbstractMemoRepository
import site.lcyk.keer.data.repository.LocalDatabaseRepository
import site.lcyk.keer.data.repository.RemoteRepository
import site.lcyk.keer.data.security.AccountKeyManager
import site.lcyk.keer.ext.string
import okhttp3.OkHttpClient
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AccountService @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient,
    private val database: KeerDatabase,
    private val fileStorage: FileStorage,
    private val offlineGroupStore: OfflineGroupStore,
    private val accountKeyManager: AccountKeyManager,
    private val authSessionManager: AuthSessionManager,
    private val repositoryFactory: RepositoryFactory,
    private val accountLocalSettingsStore: AccountLocalSettingsStore,
) {
    private val exportDateFormatter: DateTimeFormatter = DateTimeFormatter
        .ofPattern("yyyyMMdd-HHmmss", Locale.US)
        .withZone(ZoneId.systemDefault())

    @Volatile
    var httpClient: OkHttpClient = okHttpClient
        private set

    val accounts = authSessionManager.accounts

    val currentAccount = authSessionManager.currentAccount

    @Volatile
    private var repository: AbstractMemoRepository = LocalDatabaseRepository(
        database.memoDao(),
        fileStorage,
        Account.Local(LocalAccount())
    )

    @Volatile
    private var remoteRepository: RemoteRepository? = null

    private val mutex = Mutex()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val initialization = CompletableDeferred<Unit>()
    @Volatile
    private var lastAvatarSyncAttemptAtMillis = 0L
    @Volatile
    private var lastAvatarSyncAttemptUri = ""

    init {
        serviceScope.launch {
            try {
                mutex.withLock {
                    updateCurrentAccount(currentAccount.first())
                }
                initialization.complete(Unit)
            } catch (e: Throwable) {
                initialization.completeExceptionally(e)
            }
        }
    }

    private fun updateCurrentAccount(account: Account?) {
        repository.close()
        accountKeyManager.onActiveAccountChanged(account)
        val session = repositoryFactory.createSession(
            account = account,
            readMemoSyncAnchor = ::readMemoSyncAnchor,
            writeMemoSyncAnchor = ::writeMemoSyncAnchor,
            readUserSyncAnchor = ::readUserSyncAnchor,
            writeUserSyncAnchor = ::writeUserSyncAnchor,
            readSyncedUserIDs = ::readSyncedUserIDs,
            writeSyncedUserIDs = ::writeSyncedUserIDs,
        ) { accountKey, user ->
            updateAccountFromSyncedUser(accountKey, user)
        }
        repository = session.repository
        remoteRepository = session.remoteRepository
        httpClient = session.httpClient
    }

    suspend fun switchAccount(accountKey: String) {
        awaitInitialization()
        mutex.withLock {
            val account = accounts.first().firstOrNull { it.accountKey() == accountKey }
            accountLocalSettingsStore.selectCurrentAccount(accountKey)
            updateCurrentAccount(account)
        }
    }

    suspend fun addAccount(account: Account) {
        awaitInitialization()
        mutex.withLock {
            authSessionManager.persistTokens(account)
            accountLocalSettingsStore.upsertAccount(account, makeCurrent = true)
            updateCurrentAccount(account)
        }
    }

    suspend fun removeAccount(accountKey: String) {
        awaitInitialization()
        mutex.withLock {
            accountLocalSettingsStore.removeAccount(accountKey)
            updateCurrentAccount(currentAccount.first())
            purgeAccountData(accountKey)
            authSessionManager.removeTokens(accountKey)
            accountKeyManager.removeAccountState(accountKey)
        }
    }

    suspend fun exportLocalAccountZip(destinationUri: Uri) {
        val accountKey = Account.Local().accountKey()
        val memoDao = database.memoDao()
        val memos = memoDao.getAllMemosForSync(accountKey)
            .filterNot { it.isDeleted }
            .sortedWith(compareBy({ it.date }, { it.content }))

        if (memos.isEmpty()) {
            throw IllegalStateException("No local memos to export")
        }

        context.contentResolver.openOutputStream(destinationUri)?.use { output ->
            ZipOutputStream(output).use { zip ->
                val collisionMap = hashMapOf<String, Int>()
                for (memo in memos) {
                    val memoBaseName = uniqueMemoBaseName(memo.date, collisionMap)
                    zip.putNextEntry(ZipEntry("$memoBaseName.md"))
                    zip.write(memo.content.toByteArray(Charsets.UTF_8))
                    zip.closeEntry()

                    val resources = memoDao.getMemoResources(memo.identifier, accountKey)
                        .sortedWith(compareBy<ResourceEntity>({ it.filename }, { it.uri }))
                    resources.forEachIndexed { index, resource ->
                        val sourceFile = localFileForResource(resource)
                            ?: throw IllegalStateException("Missing resource file: ${resource.filename}")
                        if (!sourceFile.exists()) {
                            throw IllegalStateException("Missing resource file: ${resource.filename}")
                        }
                        val ext = exportFileExtension(resource, sourceFile)
                        val attachmentName = if (ext.isBlank()) {
                            "$memoBaseName-${index + 1}"
                        } else {
                            "$memoBaseName-${index + 1}.$ext"
                        }
                        zip.putNextEntry(ZipEntry(attachmentName))
                        sourceFile.inputStream().use { input -> input.copyTo(zip) }
                        zip.closeEntry()
                    }
                }
            }
        } ?: throw IllegalStateException("Unable to open export destination")
    }

    private fun uniqueMemoBaseName(date: Instant, collisionMap: MutableMap<String, Int>): String {
        val base = exportDateFormatter.format(date)
        val count = collisionMap[base] ?: 0
        collisionMap[base] = count + 1
        return if (count == 0) base else "${base}_$count"
    }

    private fun localFileForResource(resource: ResourceEntity): File? {
        val uri = (resource.localUri ?: resource.uri).toUri()
        if (uri.scheme != "file") {
            return null
        }
        val path = uri.path ?: return null
        return File(path)
    }

    private fun exportFileExtension(resource: ResourceEntity, sourceFile: File): String {
        val filenameExt = resource.filename.substringAfterLast('.', "")
        if (filenameExt.isNotBlank()) {
            return filenameExt.lowercase(Locale.US)
        }
        val sourceExt = sourceFile.extension
        if (sourceExt.isNotBlank()) {
            return sourceExt.lowercase(Locale.US)
        }
        val fromMime = resource.mimeType?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
        return fromMime?.lowercase(Locale.US) ?: ""
    }

    private suspend fun purgeAccountData(accountKey: String) {
        val memoDao = database.memoDao()
        memoDao.deleteResourcesByAccount(accountKey)
        memoDao.deleteMemoTagsByAccount(accountKey)
        memoDao.deleteMemosByAccount(accountKey)
        memoDao.deleteTagsByAccount(accountKey)
        offlineGroupStore.purgeAccount(accountKey)
        fileStorage.deleteAccountFiles(accountKey)
    }

    private suspend fun updateAccountFromSyncedUser(accountKey: String, user: User) {
        mutex.withLock {
            val existingUser = accountLocalSettingsStore.userData(accountKey) ?: return
            val current = authSessionManager.parseAccountWithStoredTokens(existingUser) ?: return
            accountLocalSettingsStore.updateUserData(accountKey) {
                val updated = current.withUser(user)
                updated.toPersistedUserData(existingUser.settings)
            }
        }
    }

    fun createKeerV2Client(host: String, accountKey: String? = null): Pair<OkHttpClient, KeerV2Api> {
        val clientBundle = authSessionManager.createKeerV2Client(host, accountKey)
        return clientBundle.httpClient to clientBundle.api
    }

    fun createKeerV2ClientWithAccessToken(host: String, accessToken: String): Pair<OkHttpClient, KeerV2Api> {
        val clientBundle = authSessionManager.createKeerV2ClientWithAccessToken(host, accessToken)
        return clientBundle.httpClient to clientBundle.api
    }

    suspend fun ensureAccountKeysReady(
        account: Account.KeerV2,
        password: String,
        accessToken: String,
    ): ApiResponse<Unit> = withContext(Dispatchers.IO) {
        val api = createKeerV2ClientWithAccessToken(account.info.host, accessToken).second
        accountKeyManager.ensureAccountKeysReady(account, api, password)
    }

    suspend fun signInKeerV2WithPassword(
        host: String,
        username: String,
        password: String,
    ): ApiResponse<Account.KeerV2> = withContext(Dispatchers.IO) {
        val response = createKeerV2Client(host).second.signIn(
            SignInRequest(
                passwordCredentials = PasswordCredentials(
                    username = username,
                    password = password,
                )
            )
        )
        when (response) {
            is ApiResponse.Success -> Unit
            is ApiResponse.Failure.Error -> return@withContext response
            is ApiResponse.Failure.Exception -> return@withContext response
        }
        bootstrapAuthenticatedKeerV2Account(
            host = host,
            password = password,
            session = response.data,
        )
    }

    suspend fun registerKeerV2WithPassword(
        host: String,
        username: String,
        password: String,
    ): ApiResponse<Account.KeerV2> = withContext(Dispatchers.IO) {
        val trimmedUsername = username.trim()
        val createResponse = createKeerV2Client(host).second.createUser(
            CreateUserRequest(
                user = CreateUserBody(
                    username = trimmedUsername,
                    password = password,
                )
            )
        )
        when (createResponse) {
            is ApiResponse.Success -> Unit
            is ApiResponse.Failure.Error -> return@withContext createResponse
            is ApiResponse.Failure.Exception -> return@withContext createResponse
        }
        signInKeerV2WithPassword(
            host = host,
            username = trimmedUsername,
            password = password,
        )
    }

    suspend fun getRepository(): AbstractMemoRepository {
        awaitInitialization()
        mutex.withLock {
            return repository
        }
    }

    suspend fun getRemoteRepository(): RemoteRepository? {
        awaitInitialization()
        mutex.withLock {
            return remoteRepository
        }
    }

    suspend fun uploadCurrentUserAvatar(uri: Uri): ApiResponse<Unit> = withContext(Dispatchers.IO) {
        val account = currentAccount.first()
        if (account !is Account.KeerV2) {
            return@withContext ApiResponse.exception(IllegalStateException(R.string.current_account_no_avatar_sync.string))
        }

        val previousAvatarUri = accountLocalSettingsStore.userSettings(account.accountKey())
            ?.avatarUri
            .orEmpty()

        val localAvatarUri = fileStorage.saveImageThumbnailFromUri(
            accountKey = account.accountKey(),
            sourceUri = uri,
            filename = "avatar_local_${account.info.id}.jpg",
            maxEdge = 640,
            quality = 82
        ) ?: runCatching {
            val extension = resolveAvatarFileExtension(uri)
            fileStorage.savePersistentFileFromUri(
                accountKey = account.accountKey(),
                sourceUri = uri,
                filename = "avatar_local_${account.info.id}.$extension"
            )
        }.getOrNull()
            ?: return@withContext ApiResponse.exception(IllegalStateException("Cannot save avatar locally"))

        mutex.withLock {
            accountLocalSettingsStore.markAvatarSyncPending(account.accountKey(), localAvatarUri.toString())
        }

        if (previousAvatarUri.isNotBlank() && previousAvatarUri != localAvatarUri.toString()) {
            runCatching { fileStorage.deleteFile(previousAvatarUri.toUri()) }
        }

        return@withContext ApiResponse.Success(Unit)
    }

    suspend fun syncPendingAvatarIfNeeded(): ApiResponse<Unit> = withContext(Dispatchers.IO) {
        val account = currentAccount.first() as? Account.KeerV2 ?: return@withContext ApiResponse.Success(Unit)
        val userSettings = accountLocalSettingsStore.userSettings(account.accountKey())
            ?: return@withContext ApiResponse.Success(Unit)
        if (!userSettings.avatarSyncPending || userSettings.avatarUri.isBlank()) {
            return@withContext ApiResponse.Success(Unit)
        }
        val now = System.currentTimeMillis()
        if (
            userSettings.avatarUri == lastAvatarSyncAttemptUri &&
            now - lastAvatarSyncAttemptAtMillis < AVATAR_SYNC_RETRY_INTERVAL_MILLIS
        ) {
            return@withContext ApiResponse.Success(Unit)
        }
        lastAvatarSyncAttemptUri = userSettings.avatarUri
        lastAvatarSyncAttemptAtMillis = now
        val avatarUri = runCatching { userSettings.avatarUri.toUri() }.getOrNull()
            ?: return@withContext ApiResponse.exception(IllegalStateException("Invalid local avatar uri"))
        syncAvatarForAccount(account, avatarUri)
    }

    private suspend fun syncAvatarForAccount(account: Account.KeerV2, sourceUri: Uri): ApiResponse<Unit> {
        val thumbnailUri = fileStorage.saveImageThumbnailFromUri(
            accountKey = account.accountKey(),
            sourceUri = sourceUri,
            filename = "avatar_upload_${account.info.id}.jpg",
            maxEdge = 320,
            quality = 82
        )
        var avatarBytes: ByteArray? = null
        var avatarType: String? = null

        if (thumbnailUri != null) {
            val thumbnailFile = thumbnailUri.path?.let(::File)
            val thumbnailBytes = thumbnailFile?.let { file ->
                runCatching { file.readBytes() }.getOrNull()
            }
            if (thumbnailBytes != null && thumbnailBytes.isNotEmpty() && thumbnailBytes.size <= AVATAR_UPLOAD_MAX_BYTES) {
                avatarBytes = thumbnailBytes
                avatarType = "image/jpeg"
            }
            fileStorage.deleteFile(thumbnailUri)
        }

        if (avatarBytes == null) {
            val originalBytes = runCatching {
                readUriBytesWithLimit(sourceUri, AVATAR_UPLOAD_MAX_BYTES)
            }.getOrElse { throwable ->
                return ApiResponse.exception(
                    IllegalStateException(
                        throwable.message ?: "Cannot read avatar file",
                        throwable
                    )
                )
            }
            if (originalBytes.isEmpty()) {
                return ApiResponse.exception(IllegalStateException("Avatar file is empty"))
            }
            avatarBytes = originalBytes
            avatarType = context.contentResolver
                .getType(sourceUri)
                ?.trim()
                ?.takeIf { it.startsWith("image/") }
        }

        val avatarContent = Base64.encodeToString(avatarBytes, Base64.NO_WRAP)

        val api = createKeerV2Client(account.info.host, account.accountKey()).second
        val response = api.updateUser(
            account.info.id.toString(),
            UpdateUserRequest(
                user = UpdateUserBody(
                    avatar = UpdateUserAvatarUpload(
                        content = avatarContent,
                        type = avatarType
                    )
                )
            )
        )
        if (response !is ApiResponse.Success) {
            return ApiResponse.exception(
                IllegalStateException("upload avatar failed: $response")
            )
        }
        lastAvatarSyncAttemptUri = ""
        lastAvatarSyncAttemptAtMillis = 0L

        val updatedUser = response.data
        val existing = mutex.withLock {
            accountLocalSettingsStore.userData(account.accountKey())
        } ?: return ApiResponse.exception(IllegalStateException("Current account data missing"))
        val parsed = authSessionManager.parseAccountWithStoredTokens(existing) as? Account.KeerV2
            ?: return ApiResponse.exception(IllegalStateException("Current account data invalid"))
        mutex.withLock {
            val updated = Account.KeerV2(parsed.info.copy(avatarUrl = updatedUser.avatarUrl.orEmpty()))
            accountLocalSettingsStore.clearAvatarSyncPending(account.accountKey(), updated)
        }
        return ApiResponse.Success(Unit)
    }

    private suspend fun readMemoSyncAnchor(accountKey: String): Instant? {
        return accountLocalSettingsStore.readMemoSyncAnchor(accountKey)
    }

    private suspend fun writeMemoSyncAnchor(accountKey: String, anchor: Instant) {
        accountLocalSettingsStore.writeMemoSyncAnchor(accountKey, anchor)
    }

    private suspend fun readUserSyncAnchor(accountKey: String): Instant? {
        return accountLocalSettingsStore.readUserSyncAnchor(accountKey)
    }

    private suspend fun writeUserSyncAnchor(accountKey: String, anchor: Instant) {
        accountLocalSettingsStore.writeUserSyncAnchor(accountKey, anchor)
    }

    private suspend fun readSyncedUserIDs(accountKey: String): List<String> {
        return accountLocalSettingsStore.readSyncedUserIDs(accountKey)
    }

    private suspend fun writeSyncedUserIDs(accountKey: String, userIDs: List<String>) {
        accountLocalSettingsStore.writeSyncedUserIDs(accountKey, userIDs)
    }

    private fun Account.toPersistedUserData(settings: UserSettings): UserData {
        return when (this) {
            is Account.KeerV2 -> UserData(
                settings = settings,
                accountKey = accountKey(),
                keerV2 = info.copy(
                    accessToken = "",
                    refreshToken = "",
                )
            )
            is Account.Local -> UserData(
                settings = settings,
                accountKey = accountKey(),
                local = info
            )
        }
    }

    private suspend fun bootstrapAuthenticatedKeerV2Account(
        host: String,
        password: String,
        session: AuthSessionResponse,
    ): ApiResponse<Account.KeerV2> {
        val account = buildAuthenticatedAccount(host, session)
        val keyBootstrap = ensureAccountKeysReady(
            account = account,
            password = password,
            accessToken = session.accessToken,
        )
        when (keyBootstrap) {
            is ApiResponse.Success -> Unit
            is ApiResponse.Failure.Error -> return keyBootstrap
            is ApiResponse.Failure.Exception -> return keyBootstrap
        }
        addAccount(account)
        return ApiResponse.Success(account)
    }

    private fun buildAuthenticatedAccount(
        host: String,
        session: AuthSessionResponse,
    ): Account.KeerV2 {
        return Account.KeerV2(
            info = MemosAccount(
                host = host,
                accessToken = session.accessToken,
                refreshToken = session.refreshToken,
                id = session.user.name.substringAfterLast('/').toLong(),
                name = session.user.username,
                avatarUrl = session.user.avatarUrl ?: "",
                startDateEpochSecond = session.user.createTime?.epochSecond ?: 0L,
            )
        )
    }

    private fun resolveAvatarFileExtension(uri: Uri): String {
        val mimeType = context.contentResolver.getType(uri).orEmpty()
        val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType)
            ?.trim()
            .orEmpty()
        return extension.ifEmpty { "jpg" }
    }

    private fun readUriBytesWithLimit(uri: Uri, maxBytes: Int): ByteArray {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("Cannot open avatar file")
        input.use { stream ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8 * 1024)
            var totalBytes = 0
            while (true) {
                val read = stream.read(buffer)
                if (read <= 0) {
                    break
                }
                totalBytes += read
                if (totalBytes > maxBytes) {
                    throw IllegalStateException("Avatar file is too large")
                }
                output.write(buffer, 0, read)
            }
            return output.toByteArray()
        }
    }

    private suspend fun awaitInitialization() {
        initialization.await()
    }

    companion object {
        private const val AVATAR_UPLOAD_MAX_BYTES = 10 * 1024 * 1024
        private const val AVATAR_SYNC_RETRY_INTERVAL_MILLIS = 20_000L
    }
}
