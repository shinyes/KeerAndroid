package site.lcyk.keer.viewmodel

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.skydoves.sandwich.getOrNull
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import site.lcyk.keer.data.api.MemosProfile
import site.lcyk.keer.data.api.KeerV2Api
import site.lcyk.keer.data.model.Account
import site.lcyk.keer.data.service.AccountService

@HiltViewModel(assistedFactory = AccountViewModel.AccountViewModelFactory::class)
class AccountViewModel @AssistedInject constructor(
    @Assisted val selectedAccountKey: String,
    private val accountService: AccountService
): ViewModel() {
    sealed class RemoteApi {
        class KeerV2(val api: KeerV2Api): RemoteApi()
    }

    @AssistedFactory
    interface AccountViewModelFactory {
        fun create(selectedAccountKey: String): AccountViewModel
    }

    private val selectedAccount = accountService.accounts.map { accounts ->
        accounts.firstOrNull { it.accountKey() == selectedAccountKey }
    }
    val selectedAccountState = selectedAccount.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), null)

    private val memosApi = selectedAccount.map { account ->
        when (account) {
            is Account.KeerV2 -> {
                val (_, api) = accountService.createKeerV2Client(account.info.host, account.info.accessToken)
                return@map RemoteApi.KeerV2(api)
            }
            else -> null
        }
    }

    var instanceProfile: MemosProfile? by mutableStateOf(null)
        private set

    suspend fun loadInstanceProfile() = withContext(viewModelScope.coroutineContext) {
        when (val memosApi = memosApi.firstOrNull()) {
            is RemoteApi.KeerV2 -> {
                val profile = memosApi.api.getProfile().getOrNull()
                instanceProfile = profile
            }
            else -> {
                instanceProfile = null
            }
        }
    }

    suspend fun exportLocalAccount(destinationUri: Uri): Result<Unit> = withContext(viewModelScope.coroutineContext) {
        if (selectedAccountKey != Account.Local().accountKey()) {
            return@withContext Result.failure(IllegalStateException("Export is available for local account only"))
        }
        runCatching {
            accountService.exportLocalAccountZip(destinationUri)
        }
    }
}
