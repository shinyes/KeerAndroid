package site.lcyk.keer.data.service

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.webkit.MimeTypeMap
import androidx.core.net.toUri
import com.skydoves.sandwich.ApiResponse
import com.skydoves.sandwich.getOrThrow
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import site.lcyk.keer.R
import site.lcyk.keer.data.api.KeerV2Api
import site.lcyk.keer.data.api.UpdateUserAvatarUpload
import site.lcyk.keer.data.api.UpdateUserBody
import site.lcyk.keer.data.api.UpdateUserRequest
import site.lcyk.keer.data.local.FileStorage
import site.lcyk.keer.data.local.KeerDatabase
import site.lcyk.keer.data.local.entity.ResourceEntity
import site.lcyk.keer.data.model.Account
import site.lcyk.keer.data.model.LocalAccount
import site.lcyk.keer.data.model.User
import site.lcyk.keer.data.model.UserData
import site.lcyk.keer.data.model.UserSettings
import site.lcyk.keer.data.repository.AbstractMemoRepository
import site.lcyk.keer.data.repository.LocalDatabaseRepository
import site.lcyk.keer.data.repository.RemoteRepository
import site.lcyk.keer.data.security.AccountKeyManager
import site.lcyk.keer.ext.string
import site.lcyk.keer.ext.settingsDataStore
import net.swiftzer.semver.SemVer
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
) {
    sealed class LoginCompatibility {
        object Supported : LoginCompatibility()
        data class Unsupported(val message: String) : LoginCompatibility()
    }

    sealed class SyncCompatibility {
        object Allowed : SyncCompatibility()
        data class Blocked(val message: String?) : SyncCompatibility()
        data class Unavailable(val message: String?) : SyncCompatibility()
    }

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
            context.settingsDataStore.updateData { settings ->
                settings.copy(currentUser = accountKey)
            }
            updateCurrentAccount(account)
        }
    }

    suspend fun addAccount(account: Account) {
        awaitInitialization()
        mutex.withLock {
            authSessionManager.persistTokens(account)
            context.settingsDataStore.updateData { settings ->
                val users = settings.usersList.toMutableList()
                val index = users.indexOfFirst { it.accountKey == account.accountKey() }
                val currentSettings = users.getOrNull(index)?.settings ?: UserSettings()
                if (index != -1) {
                    users.removeAt(index)
                }
                users.add(account.toPersistedUserData(currentSettings))
                settings.copy(
                    usersList = users,
                    currentUser = account.accountKey(),
                )
            }
            updateCurrentAccount(account)
        }
    }

    suspend fun removeAccount(accountKey: String) {
        awaitInitialization()
        mutex.withLock {
            context.settingsDataStore.updateData { settings ->
                val users = settings.usersList.toMutableList()
                val index = users.indexOfFirst { it.accountKey == accountKey }
                if (index != -1) {
                    users.removeAt(index)
                }
                val newCurrentUser = if (settings.currentUser == accountKey) {
                    users.firstOrNull()?.accountKey ?: ""
                } else {
                    settings.currentUser
                }
                settings.copy(
                    usersList = users,
                    currentUser = newCurrentUser,
                )
            }
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
            context.settingsDataStore.updateData { settings ->
                val index = settings.usersList.indexOfFirst { it.accountKey == accountKey }
                if (index == -1) {
                    return@updateData settings
                }
                val existingUser = settings.usersList[index]
                val current = authSessionManager.parseAccountWithStoredTokens(existingUser) ?: return@updateData settings
                val updated = current.withUser(user)
                val users = settings.usersList.toMutableList()
                users[index] = updated.toPersistedUserData(existingUser.settings)
                settings.copy(usersList = users)
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

    suspend fun checkLoginCompatibility(host: String): LoginCompatibility {
        val keerApiVersion = detectKeerAPIVersion(host) ?: return LoginCompatibility.Unsupported(
            R.string.memos_supported_versions.string
        )
        return if (isCompatibleKeerAPIVersion(keerApiVersion)) {
            LoginCompatibility.Supported
        } else {
            LoginCompatibility.Unsupported(R.string.memos_supported_versions.string)
        }
    }

    suspend fun checkCurrentAccountSyncCompatibility(isAutomatic: Boolean): SyncCompatibility {
        awaitInitialization()
        val account = currentAccount.first() ?: return SyncCompatibility.Allowed
        if (account is Account.Local) {
            return SyncCompatibility.Allowed
        }
        if (account !is Account.KeerV2) {
            return if (isAutomatic) {
                SyncCompatibility.Blocked(null)
            } else {
                SyncCompatibility.Blocked(R.string.memos_supported_versions.string)
            }
        }

        val serverVersion = fetchKeerAPIVersionForAccount(account)
        if (serverVersion == null) {
            return if (isAutomatic) {
                SyncCompatibility.Unavailable(null)
            } else {
                SyncCompatibility.Unavailable(R.string.sync_server_unreachable.string)
            }
        }
        if (!isCompatibleKeerAPIVersion(serverVersion)) {
            return if (isAutomatic) {
                SyncCompatibility.Blocked(null)
            } else {
                SyncCompatibility.Blocked(R.string.memos_supported_versions.string)
            }
        }
        return SyncCompatibility.Allowed
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
            return@withContext ApiResponse.exception(IllegalStateException("Current account does not support avatar sync"))
        }

        val previousAvatarUri = context.settingsDataStore.data.first().usersList
            .firstOrNull { user -> user.accountKey == account.accountKey() }
            ?.settings
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
            context.settingsDataStore.updateData { settings ->
                val users = settings.usersList.toMutableList()
                val index = users.indexOfFirst { it.accountKey == account.accountKey() }
                if (index == -1) {
                    return@updateData settings
                }
                val existing = users[index]
                users[index] = existing.copy(
                    settings = existing.settings.copy(
                        avatarUri = localAvatarUri.toString(),
                        avatarSyncPending = true
                    )
                )
                settings.copy(usersList = users)
            }
        }

        if (previousAvatarUri.isNotBlank() && previousAvatarUri != localAvatarUri.toString()) {
            runCatching { fileStorage.deleteFile(previousAvatarUri.toUri()) }
        }

        return@withContext ApiResponse.Success(Unit)
    }

    suspend fun syncPendingAvatarIfNeeded(): ApiResponse<Unit> = withContext(Dispatchers.IO) {
        val account = currentAccount.first() as? Account.KeerV2 ?: return@withContext ApiResponse.Success(Unit)
        val settingsSnapshot = context.settingsDataStore.data.first()
        val userSettings = settingsSnapshot.usersList
            .firstOrNull { it.accountKey == account.accountKey() }
            ?.settings
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
        mutex.withLock {
            context.settingsDataStore.updateData { settings ->
                val users = settings.usersList.toMutableList()
                val index = users.indexOfFirst { it.accountKey == account.accountKey() }
                if (index == -1) {
                    return@updateData settings
                }
                val existing = users[index]
                val parsed = authSessionManager.parseAccountWithStoredTokens(existing) as? Account.KeerV2
                    ?: return@updateData settings
                val updated = parsed.info.copy(avatarUrl = updatedUser.avatarUrl.orEmpty())
                users[index] = Account.KeerV2(updated).toPersistedUserData(
                    existing.settings.copy(avatarSyncPending = false)
                )
                settings.copy(usersList = users)
            }
        }
        return ApiResponse.Success(Unit)
    }

    private suspend fun detectKeerAPIVersion(host: String): String? {
        val keerV2Profile = createKeerV2Client(host, null).second.getProfile().getOrThrow()
        return keerV2Profile.keerApiVersion.trim().ifEmpty { null }
    }

    private suspend fun fetchKeerAPIVersionForAccount(account: Account.KeerV2): String? {
        val profileResponse = withTimeoutOrNull(SYNC_COMPATIBILITY_TIMEOUT_MILLIS) {
            createKeerV2Client(account.info.host, account.accountKey())
                .second
                .getProfile()
        } ?: return null

        return when (profileResponse) {
            is ApiResponse.Success -> {
                profileResponse.data.keerApiVersion.trim().ifEmpty { null }
            }
            else -> {
                null
            }
        }
    }

    private suspend fun readMemoSyncAnchor(accountKey: String): Instant? {
        val settings = context.settingsDataStore.data.first()
        val raw = settings.usersList
            .firstOrNull { user -> user.accountKey == accountKey }
            ?.settings
            ?.memoSyncAnchor
            .orEmpty()
            .trim()
        if (raw.isEmpty()) {
            return null
        }
        return try {
            Instant.parse(raw)
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun writeMemoSyncAnchor(accountKey: String, anchor: Instant) {
        val normalizedAnchor = anchor.toString()
        context.settingsDataStore.updateData { existing ->
            val index = existing.usersList.indexOfFirst { user -> user.accountKey == accountKey }
            if (index == -1) {
                return@updateData existing
            }
            val users = existing.usersList.toMutableList()
            val target = users[index]
            users[index] = target.copy(
                settings = target.settings.copy(memoSyncAnchor = normalizedAnchor)
            )
            existing.copy(usersList = users)
        }
    }

    private suspend fun readUserSyncAnchor(accountKey: String): Instant? {
        val settings = context.settingsDataStore.data.first()
        val raw = settings.usersList
            .firstOrNull { user -> user.accountKey == accountKey }
            ?.settings
            ?.userSyncAnchor
            .orEmpty()
            .trim()
        if (raw.isEmpty()) {
            return null
        }
        return try {
            Instant.parse(raw)
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun writeUserSyncAnchor(accountKey: String, anchor: Instant) {
        val normalizedAnchor = anchor.toString()
        context.settingsDataStore.updateData { existing ->
            val index = existing.usersList.indexOfFirst { user -> user.accountKey == accountKey }
            if (index == -1) {
                return@updateData existing
            }
            val users = existing.usersList.toMutableList()
            val target = users[index]
            users[index] = target.copy(
                settings = target.settings.copy(userSyncAnchor = normalizedAnchor)
            )
            existing.copy(usersList = users)
        }
    }

    private suspend fun readSyncedUserIDs(accountKey: String): List<String> {
        val settings = context.settingsDataStore.data.first()
        return settings.usersList
            .firstOrNull { user -> user.accountKey == accountKey }
            ?.settings
            ?.syncedUserIds
            .orEmpty()
            .asSequence()
            .map { id -> id.trim() }
            .filter { id -> id.isNotEmpty() }
            .distinct()
            .toList()
    }

    private suspend fun writeSyncedUserIDs(accountKey: String, userIDs: List<String>) {
        val normalizedUserIDs = userIDs
            .asSequence()
            .map { userID -> userID.trim() }
            .filter { userID -> userID.isNotEmpty() }
            .distinct()
            .toList()
        context.settingsDataStore.updateData { existing ->
            val index = existing.usersList.indexOfFirst { user -> user.accountKey == accountKey }
            if (index == -1) {
                return@updateData existing
            }
            val users = existing.usersList.toMutableList()
            val target = users[index]
            users[index] = target.copy(
                settings = target.settings.copy(syncedUserIds = normalizedUserIDs)
            )
            existing.copy(usersList = users)
        }
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

    private fun isCompatibleKeerAPIVersion(raw: String): Boolean {
        val version = parseKeerAPIVersion(raw) ?: return false
        return version in KEER_API_MIN_VERSION..KEER_API_MAX_VERSION
    }

    private fun parseKeerAPIVersion(raw: String): SemVer? {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) {
            return null
        }
        val normalized = if (TWO_SEGMENT_VERSION_REGEX.matches(trimmed)) "$trimmed.0" else trimmed
        return SemVer.parseOrNull(normalized)
    }

    companion object {
        private val KEER_API_MIN_VERSION = SemVer(0, 1, 0)
        private val KEER_API_MAX_VERSION = SemVer(0, 1, 0)
        private val TWO_SEGMENT_VERSION_REGEX = Regex("""^\d+\.\d+$""")
        private const val SYNC_COMPATIBILITY_TIMEOUT_MILLIS = 3500L
        private const val AVATAR_UPLOAD_MAX_BYTES = 10 * 1024 * 1024
        private const val AVATAR_SYNC_RETRY_INTERVAL_MILLIS = 20_000L
    }
}
