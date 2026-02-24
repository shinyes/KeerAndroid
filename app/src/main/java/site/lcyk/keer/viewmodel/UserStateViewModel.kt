package site.lcyk.keer.viewmodel

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.skydoves.sandwich.ApiResponse
import com.skydoves.sandwich.mapSuccess
import com.skydoves.sandwich.suspendOnSuccess
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import site.lcyk.keer.R
import site.lcyk.keer.data.api.KeerV2User
import site.lcyk.keer.data.constant.KeerException
import site.lcyk.keer.data.model.Account
import site.lcyk.keer.data.model.LocalAccount
import site.lcyk.keer.data.model.MemosAccount
import site.lcyk.keer.data.model.User
import site.lcyk.keer.data.service.AccountService
import site.lcyk.keer.ext.string
import site.lcyk.keer.ext.suspendOnNotLogin
import okhttp3.OkHttpClient
import java.time.Instant
import javax.inject.Inject

@HiltViewModel
class UserStateViewModel @Inject constructor(
    private val accountService: AccountService
) : ViewModel() {

    var currentUser: User? by mutableStateOf(null)
        private set

    var host: String = ""
        private set
    val okHttpClient: OkHttpClient get() = accountService.httpClient
    val accounts = accountService.accounts.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val currentAccount = accountService.currentAccount.stateIn(viewModelScope, SharingStarted.Lazily, null)

    init {
        viewModelScope.launch {
            accountService.currentAccount.collectLatest {
                host = when(it) {
                    is Account.KeerV2 -> it.info.host
                    else -> ""
                }
            }
        }
    }

    suspend fun loadCurrentUser(): ApiResponse<User> = withContext(viewModelScope.coroutineContext) {
        accountService.getRepository().getCurrentUser().suspendOnSuccess {
            currentUser = data
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
}

sealed class LoginCompatibility {
    object Supported : LoginCompatibility()
    data class Unsupported(val message: String) : LoginCompatibility()
}

val LocalUserState =
    compositionLocalOf<UserStateViewModel> { error(R.string.user_state_not_found.string) }
