package site.lcyk.keer.viewmodel

import android.net.Uri
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.skydoves.sandwich.ApiResponse
import com.skydoves.sandwich.getOrNull
import com.skydoves.sandwich.mapSuccess
import com.skydoves.sandwich.suspendOnSuccess
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import site.lcyk.keer.R
import site.lcyk.keer.data.api.KeerV2User
import site.lcyk.keer.data.constant.KeerException
import site.lcyk.keer.data.model.Account
import site.lcyk.keer.data.model.CollaboratorProfile
import site.lcyk.keer.data.model.LocalAccount
import site.lcyk.keer.data.model.MemosAccount
import site.lcyk.keer.data.model.User
import site.lcyk.keer.data.service.AccountService
import site.lcyk.keer.data.service.OfflineSyncTask
import site.lcyk.keer.data.service.OfflineSyncTaskScheduler
import site.lcyk.keer.ext.string
import site.lcyk.keer.ext.suspendOnNotLogin
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.time.Instant
import javax.inject.Inject

@HiltViewModel
class UserStateViewModel @Inject constructor(
    private val accountService: AccountService,
    private val offlineSyncTaskScheduler: OfflineSyncTaskScheduler
) : ViewModel() {

    var currentUser: User? by mutableStateOf(null)
        private set

    var host: String = ""
        private set
    val okHttpClient: OkHttpClient get() = accountService.httpClient
    private val collaboratorAvatarMutex = Mutex()
    private val _collaboratorProfiles = MutableStateFlow<Map<String, CollaboratorProfile>>(emptyMap())
    val collaboratorProfiles: StateFlow<Map<String, CollaboratorProfile>> = _collaboratorProfiles.asStateFlow()
    val accounts = accountService.accounts.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val currentAccount = accountService.currentAccount.stateIn(viewModelScope, SharingStarted.Lazily, null)

    init {
        viewModelScope.launch {
            accountService.currentAccount.collectLatest {
                host = when(it) {
                    is Account.KeerV2 -> it.info.host
                    else -> ""
                }
                _collaboratorProfiles.value = emptyMap()
            }
        }
    }

    suspend fun loadCurrentUser(): ApiResponse<User> = withContext(viewModelScope.coroutineContext) {
        accountService.getRepository().getCurrentUser().suspendOnSuccess {
            currentUser = data
            offlineSyncTaskScheduler.dispatch(OfflineSyncTask.AVATAR)
        }.suspendOnNotLogin {
            currentUser = null
        }
    }

    suspend fun hasAnyAccount(): Boolean = withContext(viewModelScope.coroutineContext) {
        accountService.accounts.first().isNotEmpty()
    }

    suspend fun checkLoginCompatibility(host: String): LoginCompatibility = withContext(viewModelScope.coroutineContext) {
        try {
            when (val compatibility = accountService.checkLoginCompatibility(host)) {
                is AccountService.LoginCompatibility.Supported -> LoginCompatibility.Supported
                is AccountService.LoginCompatibility.Unsupported -> LoginCompatibility.Unsupported(compatibility.message)
            }
        } catch (e: Throwable) {
            LoginCompatibility.Unsupported(e.localizedMessage ?: e.message ?: "")
        }
    }

    suspend fun loginMemosWithAccessToken(
        host: String,
        accessToken: String
    ): ApiResponse<Unit> = withContext(viewModelScope.coroutineContext) {
        try {
            when (val compatibility = accountService.checkLoginCompatibility(host)) {
                is AccountService.LoginCompatibility.Supported -> loginKeerV2WithAccessToken(host, accessToken)
                is AccountService.LoginCompatibility.Unsupported -> {
                    ApiResponse.exception(KeerException(compatibility.message))
                }
            }
        } catch (e: Throwable) {
            ApiResponse.exception(e)
        }
    }

    private suspend fun loginKeerV2WithAccessToken(host: String, accessToken: String): ApiResponse<Unit> = withContext(viewModelScope.coroutineContext) {
        try {
            val resp = accountService.createKeerV2Client(host, accessToken).second.getCurrentUser()
            if (resp !is ApiResponse.Success) {
                return@withContext resp.mapSuccess {}
            }
            val user = resp.data.user
            if (user == null) {
                return@withContext ApiResponse.exception(KeerException.notLogin)
            }
            accountService.addAccount(getAccount(host, accessToken, user))
            loadCurrentUser().mapSuccess {}
        } catch (e: Throwable) {
            ApiResponse.exception(e)
        }
    }

    suspend fun logout(accountKey: String) = withContext(viewModelScope.coroutineContext) {
        if (currentAccount.first()?.accountKey() == accountKey) {
            currentUser = null
        }
        accountService.removeAccount(accountKey)
    }

    suspend fun switchAccount(accountKey: String) = withContext(viewModelScope.coroutineContext) {
        accountService.switchAccount(accountKey)
        loadCurrentUser()
    }

    suspend fun addLocalAccount(): ApiResponse<Unit> = withContext(viewModelScope.coroutineContext) {
        try {
            accountService.addAccount(
                Account.Local(
                    LocalAccount(startDateEpochSecond = Instant.now().epochSecond)
                )
            )
            loadCurrentUser().mapSuccess {}
        } catch (e: Throwable) {
            ApiResponse.exception(e)
        }
    }

    suspend fun uploadCurrentUserAvatar(uri: Uri): ApiResponse<Unit> = withContext(viewModelScope.coroutineContext) {
        val response = accountService.uploadCurrentUserAvatar(uri)
        if (response is ApiResponse.Success) {
            offlineSyncTaskScheduler.dispatch(OfflineSyncTask.AVATAR)
        }
        loadCurrentUser()
        response
    }

    suspend fun prefetchCollaboratorAvatars(userIds: List<String>) = withContext(viewModelScope.coroutineContext) {
        val account = currentAccount.first() as? Account.KeerV2 ?: return@withContext
        val normalizedIds = userIds
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { it.substringAfterLast('/') }
            .filter { it.isNotEmpty() }
            .distinct()
            .toList()
        if (normalizedIds.isEmpty()) {
            return@withContext
        }

        val missingIds = collaboratorAvatarMutex.withLock {
            normalizedIds.filterNot { _collaboratorProfiles.value.containsKey(it) }
        }
        if (missingIds.isEmpty()) {
            return@withContext
        }

        val api = accountService.createKeerV2Client(account.info.host, account.info.accessToken).second
        val currentUserID = account.info.id.toString()
        val remoteIDs = missingIds.filterNot { userId -> userId == currentUserID }
        val remoteUsersByID = hashMapOf<String, KeerV2User>()
        if (remoteIDs.isNotEmpty()) {
            val batch = api.getUsersBatch(remoteIDs.joinToString(",")).getOrNull()
            batch?.users?.forEach { user ->
                val userID = user.name.substringAfterLast('/')
                if (userID.isNotBlank()) {
                    remoteUsersByID[userID] = user
                }
            }
            val unresolved = remoteIDs.filterNot { userID -> remoteUsersByID.containsKey(userID) }
            if (unresolved.isNotEmpty()) {
                val fallbackUsers = kotlinx.coroutines.coroutineScope {
                    unresolved.map { userId ->
                        async { userId to api.getUser(userId).getOrNull() }
                    }.awaitAll()
                }
                fallbackUsers.forEach { (userId, user) ->
                    if (user != null) {
                        remoteUsersByID[userId] = user
                    }
                }
            }
        }

        val fetched = missingIds.associateWith { userId ->
            if (userId == currentUserID) {
                val current = currentUser
                CollaboratorProfile(
                    id = userId,
                    name = current?.name?.takeIf { it.isNotBlank() }
                        ?: account.info.name.ifBlank { userId },
                    avatarUrl = resolveAvatarUrl(
                        account.info.host,
                        current?.avatarUrl.orEmpty().ifBlank { account.info.avatarUrl }
                    )
                )
            } else {
                val user = remoteUsersByID[userId]
                CollaboratorProfile(
                    id = userId,
                    name = user?.displayName?.takeIf { it.isNotBlank() }
                        ?: user?.username?.takeIf { it.isNotBlank() }
                        ?: userId,
                    avatarUrl = resolveAvatarUrl(account.info.host, user?.avatarUrl.orEmpty())
                )
            }
        }

        collaboratorAvatarMutex.withLock {
            val merged = _collaboratorProfiles.value.toMutableMap()
            missingIds.forEach { userId ->
                val profile = fetched[userId]
                if (profile != null) {
                    merged[userId] = profile
                }
            }
            _collaboratorProfiles.value = merged
        }
    }

    private fun getAccount(host: String, accessToken: String, user: KeerV2User): Account = Account.KeerV2(
        info = MemosAccount(
            host = host,
            accessToken = accessToken,
            id = user.name.substringAfterLast('/').toLong(),
            name = user.username,
            avatarUrl = user.avatarUrl ?: "",
            startDateEpochSecond = user.createTime?.epochSecond ?: 0L,
        )
    )

    private fun resolveAvatarUrl(host: String, avatarUrl: String): String? {
        if (avatarUrl.isBlank()) {
            return null
        }
        if (avatarUrl.toHttpUrlOrNull() != null || "://" in avatarUrl) {
            return avatarUrl
        }
        val baseUrl = host.toHttpUrlOrNull() ?: return avatarUrl
        return runCatching {
            baseUrl.toUrl().toURI().resolve(avatarUrl).toString()
        }.getOrDefault(avatarUrl)
    }
}

sealed class LoginCompatibility {
    object Supported : LoginCompatibility()
    data class Unsupported(val message: String) : LoginCompatibility()
}

val LocalUserState =
    compositionLocalOf<UserStateViewModel> { error(R.string.user_state_not_found.string) }
