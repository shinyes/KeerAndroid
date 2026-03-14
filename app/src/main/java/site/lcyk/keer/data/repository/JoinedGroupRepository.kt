package site.lcyk.keer.data.repository

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import site.lcyk.keer.data.model.GroupIdAlias
import site.lcyk.keer.data.model.MemoGroup
import site.lcyk.keer.data.service.AccountService
import site.lcyk.keer.data.service.OfflineGroupStore

@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
class JoinedGroupRepository @Inject constructor(
    private val accountService: AccountService,
    private val offlineGroupStore: OfflineGroupStore,
) {
    fun observeJoinedGroups(): Flow<List<MemoGroup>> {
        return accountService.currentAccount.flatMapLatest { account ->
            val accountKey = account?.accountKey().orEmpty()
            if (accountKey.isBlank()) {
                emptyFlow()
            } else {
                offlineGroupStore.observeGroups(accountKey)
            }
        }
    }

    fun observeGroupIdAliases(): Flow<List<GroupIdAlias>> {
        return accountService.currentAccount.flatMapLatest { account ->
            val accountKey = account?.accountKey().orEmpty()
            if (accountKey.isBlank()) {
                emptyFlow()
            } else {
                offlineGroupStore.observeGroupAliases(accountKey)
            }
        }
    }

    suspend fun upsertGroup(group: MemoGroup) {
        val accountKey = accountService.currentAccount.first()?.accountKey().orEmpty()
        if (accountKey.isNotBlank()) {
            offlineGroupStore.upsertGroup(accountKey, group)
        }
    }
}
