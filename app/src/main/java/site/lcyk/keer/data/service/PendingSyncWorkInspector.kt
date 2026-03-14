package site.lcyk.keer.data.service

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import site.lcyk.keer.data.model.SyncStatus

@Singleton
class PendingSyncWorkInspector @Inject constructor(
    private val accountService: AccountService,
    private val accountLocalSettingsStore: AccountLocalSettingsStore,
    private val offlineGroupStore: OfflineGroupStore,
) {
    suspend fun hasPendingWork(repositoryStatus: SyncStatus): Boolean {
        if (repositoryStatus.unsyncedCount > 0) {
            return true
        }
        val accountKey = accountService.currentAccount.first()?.accountKey().orEmpty()
        if (accountKey.isNotBlank() && offlineGroupStore.hasPendingWork(accountKey)) {
            return true
        }
        return accountLocalSettingsStore.currentUserSettings()?.avatarSyncPending == true
    }
}
