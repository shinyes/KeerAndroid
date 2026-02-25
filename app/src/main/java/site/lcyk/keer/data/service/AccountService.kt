package site.lcyk.keer.data.service

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.core.net.toUri
import com.skydoves.sandwich.getOrNull
import com.skydoves.sandwich.getOrThrow
import com.skydoves.sandwich.retrofit.adapters.ApiResponseCallAdapterFactory
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import site.lcyk.keer.R
import site.lcyk.keer.data.api.KeerV2Api
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
import site.lcyk.keer.data.repository.KeerV2Repository
import site.lcyk.keer.data.repository.RemoteRepository
import site.lcyk.keer.data.repository.SyncingRepository
import site.lcyk.keer.ext.string
import site.lcyk.keer.ext.settingsDataStore
import net.swiftzer.semver.SemVer
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
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
    private val secureTokenStorage: SecureTokenStorage,
) {
    sealed class LoginCompatibility {
        object Supported : LoginCompatibility()
        data class Unsupported(val message: String) : LoginCompatibility()
    }

    sealed class SyncCompatibility {
        object Allowed : SyncCompatibility()
        data class Blocked(val message: String?) : SyncCompatibility()
    }

    private val exportDateFormatter: DateTimeFormatter = DateTimeFormatter
        .ofPattern("yyyyMMdd-HHmmss", Locale.US)
        .withZone(ZoneId.systemDefault())

    private val networkJson = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
    }

    @Volatile
    var httpClient: OkHttpClient = okHttpClient
        private set

    val accounts = context.settingsDataStore.data.map { settings ->
        settings.usersList.mapNotNull(::parseAccountWithSecureToken)
    }

    val currentAccount = context.settingsDataStore.data.map { settings ->
        settings.usersList.firstOrNull { it.accountKey == settings.currentUser }
            ?.let(::parseAccountWithSecureToken)
    }

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
        when (account) {
            null -> {
                this.repository = LocalDatabaseRepository(database.memoDao(), fileStorage, Account.Local(LocalAccount()))
                this.remoteRepository = null
                httpClient = okHttpClient
            }
            is Account.Local -> {
                this.repository = LocalDatabaseRepository(database.memoDao(), fileStorage, account)
                this.remoteRepository = null
                httpClient = okHttpClient
            }
            is Account.KeerV2 -> {
                val (client, memosApi) = createKeerV2Client(account.info.host, account.info.accessToken)
                val remote = KeerV2Repository(memosApi, account, client)
                this.repository = SyncingRepository(
                    database.memoDao(),
                    fileStorage,
                    remote,
                    account
                ) { user ->
                    updateAccountFromSyncedUser(account.accountKey(), user)
                }
                this.remoteRepository = remote
                this.httpClient = client
            }
        }
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
            persistAccessToken(account)
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
            secureTokenStorage.removeToken(accountKey)
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
                val current = parseAccountWithSecureToken(existingUser) ?: return@updateData settings
                val updated = current.withUser(user)
                val users = settings.usersList.toMutableList()
                users[index] = updated.toPersistedUserData(existingUser.settings)
                settings.copy(usersList = users)
            }
        }
    }

    fun createKeerV2Client(host: String, accessToken: String?): Pair<OkHttpClient, KeerV2Api> {
        val client = okHttpClient.newBuilder().apply {
            if (!accessToken.isNullOrBlank()) {
                addNetworkInterceptor { chain ->
                    var request = chain.request()
                    if (shouldAttachAccessToken(request.url, host)) {
                        request = request.newBuilder()
                            .addHeader("Authorization", "Bearer $accessToken")
                            .build()
                    }
                    chain.proceed(request)
                }
            }
        }.build()

        return client to Retrofit.Builder()
            .baseUrl(host)
            .client(client)
            .addConverterFactory(networkJson.asConverterFactory("application/json".toMediaType()))
            .addCallAdapterFactory(ApiResponseCallAdapterFactory.create())
            .build()
            .create(KeerV2Api::class.java)
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
            ?: return if (isAutomatic) {
                SyncCompatibility.Blocked(null)
            } else {
                SyncCompatibility.Blocked(R.string.memos_supported_versions.string)
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

    private suspend fun detectKeerAPIVersion(host: String): String? {
        val keerV2Profile = createKeerV2Client(host, null).second.getProfile().getOrThrow()
        return keerV2Profile.keerApiVersion.trim().ifEmpty { null }
    }

    private suspend fun fetchKeerAPIVersionForAccount(account: Account.KeerV2): String? {
        return createKeerV2Client(account.info.host, account.info.accessToken)
            .second
            .getProfile()
            .getOrNull()
            ?.keerApiVersion
            ?.trim()
            ?.ifEmpty { null }
    }

    private fun parseAccountWithSecureToken(userData: UserData): Account? {
        val account = Account.parseUserData(userData) ?: return null
        val token = secureTokenStorage.getToken(userData.accountKey)
            .orEmpty()
        return when (account) {
            is Account.KeerV2 -> Account.KeerV2(account.info.copy(accessToken = token))
            is Account.Local -> account
        }
    }

    private fun Account.toPersistedUserData(settings: UserSettings): UserData {
        return when (this) {
            is Account.KeerV2 -> UserData(
                settings = settings,
                accountKey = accountKey(),
                keerV2 = info.copy(accessToken = "")
            )
            is Account.Local -> UserData(
                settings = settings,
                accountKey = accountKey(),
                local = info
            )
        }
    }

    private fun persistAccessToken(account: Account) {
        when (account) {
            is Account.KeerV2 -> secureTokenStorage.saveToken(account.accountKey(), account.info.accessToken)
            is Account.Local -> Unit
        }
    }

    private fun shouldAttachAccessToken(requestUrl: HttpUrl, host: String): Boolean {
        val baseUrl = host.toHttpUrlOrNull() ?: return false
        return requestUrl.scheme == baseUrl.scheme &&
            requestUrl.host == baseUrl.host &&
            requestUrl.port == baseUrl.port
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
    }
}
