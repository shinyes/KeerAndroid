package site.lcyk.keer.viewmodel

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import site.lcyk.keer.R
import site.lcyk.keer.data.model.Account
import site.lcyk.keer.data.service.AccountService
import site.lcyk.keer.ext.string

@HiltViewModel(assistedFactory = AccountViewModel.AccountViewModelFactory::class)
class AccountViewModel @AssistedInject constructor(
    @Assisted val selectedAccountKey: String,
    private val accountService: AccountService
): ViewModel() {
    @AssistedFactory
    interface AccountViewModelFactory {
        fun create(selectedAccountKey: String): AccountViewModel
    }

    private val selectedAccount = accountService.accounts.map { accounts ->
        accounts.firstOrNull { it.accountKey() == selectedAccountKey }
    }
    val selectedAccountState = selectedAccount.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), null)

    suspend fun exportLocalAccount(destinationUri: Uri): Result<Unit> = withContext(viewModelScope.coroutineContext) {
        if (selectedAccountKey != Account.Local().accountKey()) {
            return@withContext Result.failure(IllegalStateException(R.string.export_local_only.string))
        }
        runCatching {
            accountService.exportLocalAccountZip(destinationUri)
        }
    }
}
