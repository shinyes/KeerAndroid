package site.lcyk.keer.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.skydoves.sandwich.ApiResponse
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import site.lcyk.keer.data.model.Account
import site.lcyk.keer.data.model.CachedMemoItem
import site.lcyk.keer.data.model.Memo
import site.lcyk.keer.data.model.MemoGroup
import site.lcyk.keer.data.model.toCachedMemoItem
import site.lcyk.keer.data.model.toMemo
import site.lcyk.keer.data.repository.RemoteRepository
import site.lcyk.keer.data.service.AccountService
import site.lcyk.keer.ext.settingsDataStore
import site.lcyk.keer.util.buildCollaboratorFilterExpression

@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class ExploreViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val accountService: AccountService
) : ViewModel() {

    val groups = context.settingsDataStore.data
        .map { settings ->
            settings.usersList
                .firstOrNull { it.accountKey == settings.currentUser }
                ?.settings
                ?.groups
                .orEmpty()
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val exploreMemos = combine(
        accountService.currentAccount,
        groups
    ) { account, currentGroups ->
        account to currentGroups
    }
        .flatMapLatest { (account, currentGroups) ->
            flow {
                val cached = readCachedExploreMemos()
                if (cached.isNotEmpty()) {
                    emit(PagingData.from(cached))
                }

                if (account == null || account is Account.Local) {
                    if (cached.isEmpty()) {
                        emit(PagingData.empty())
                    }
                    return@flow
                }

                val remoteRepository = accountService.getRemoteRepository()
                if (remoteRepository == null) {
                    if (cached.isEmpty()) {
                        emit(PagingData.empty())
                    }
                    return@flow
                }

                val aggregated = loadAggregatedMemos(account, remoteRepository, currentGroups)
                if (aggregated.isNotEmpty()) {
                    persistExploreMemos(aggregated)
                    emit(PagingData.from(aggregated))
                } else if (cached.isEmpty()) {
                    emit(PagingData.empty())
                }
            }
        }
        .cachedIn(viewModelScope)

    init {
        viewModelScope.launch {
            accountService.currentAccount.collectLatest { account ->
                if (account is Account.KeerV2) {
                    syncGroupsFromRemote()
                }
            }
        }
    }

    private suspend fun loadAggregatedMemos(
        account: Account,
        remoteRepository: RemoteRepository,
        groups: List<MemoGroup>
    ): List<Memo> {
        val collaborative = loadCollaborativeMemos(account, remoteRepository)
        val groupMemos = loadGroupScopeMemos(remoteRepository, groups)
        return (collaborative + groupMemos)
            .distinctBy { memo -> memo.remoteId }
            .sortedByDescending { memo -> memo.date }
    }

    private fun resolveCollaborativeFilter(account: Account): String? {
        val remoteAccount = account as? Account.KeerV2 ?: return null
        val accountId = remoteAccount.info.id
        val collaboratorFilter = buildCollaboratorFilterExpression(accountId.toString())
        if (collaboratorFilter.isEmpty()) {
            return null
        }
        return "($collaboratorFilter) && (creator_id != $accountId)"
    }

    private suspend fun loadCollaborativeMemos(
        account: Account,
        remoteRepository: RemoteRepository
    ): List<Memo> {
        val filter = resolveCollaborativeFilter(account) ?: return emptyList()
        val loaded = mutableListOf<Memo>()
        var pageToken: String? = null

        do {
            when (val response = remoteRepository.listWorkspaceMemos(pageSize = 100, pageToken = pageToken, filter = filter)) {
                is ApiResponse.Success -> {
                    loaded += response.data.first
                    pageToken = response.data.second
                }
                is ApiResponse.Failure.Error,
                is ApiResponse.Failure.Exception -> {
                    pageToken = null
                }
            }
        } while (!pageToken.isNullOrBlank())

        return loaded
    }

    private suspend fun loadGroupScopeMemos(
        remoteRepository: RemoteRepository,
        groups: List<MemoGroup>
    ): List<Memo> {
        val targets = groups.distinctBy { group -> group.id }
        if (targets.isEmpty()) {
            return emptyList()
        }

        val loaded = coroutineScope {
            targets.map { group ->
                async { loadGroupMessages(remoteRepository, group.id) }
            }.awaitAll()
        }.flatten()

        return loaded
    }

    private suspend fun loadGroupMessages(
        remoteRepository: RemoteRepository,
        groupId: String
    ): List<Memo> {
        val loaded = mutableListOf<Memo>()
        var pageToken: String? = null

        do {
            when (val response = remoteRepository.listGroupMessages(groupId, pageSize = 100, pageToken = pageToken)) {
                is ApiResponse.Success -> {
                    loaded += response.data.first
                    pageToken = response.data.second
                }
                is ApiResponse.Failure.Error,
                is ApiResponse.Failure.Exception -> {
                    pageToken = null
                }
            }
        } while (!pageToken.isNullOrBlank())

        return loaded
    }

    private suspend fun readCachedExploreMemos(): List<Memo> {
        val settings = context.settingsDataStore.data.first()
        val userSettings = settings.usersList
            .firstOrNull { it.accountKey == settings.currentUser }
            ?.settings
            ?: return emptyList()
        return userSettings.cachedExploreMemos
            .map(CachedMemoItem::toMemo)
            .sortedByDescending { memo -> memo.date }
    }

    private suspend fun persistExploreMemos(memos: List<Memo>) {
        context.settingsDataStore.updateData { existing ->
            val index = existing.usersList.indexOfFirst { user -> user.accountKey == existing.currentUser }
            if (index == -1) {
                return@updateData existing
            }
            val users = existing.usersList.toMutableList()
            val target = users[index]
            users[index] = target.copy(
                settings = target.settings.copy(
                    cachedExploreMemos = memos.map { memo -> memo.toCachedMemoItem() }
                )
            )
            existing.copy(usersList = users)
        }
    }

    private suspend fun syncGroupsFromRemote() {
        val remoteRepository = accountService.getRemoteRepository() ?: return
        when (val response = remoteRepository.listGroups()) {
            is ApiResponse.Success -> {
                context.settingsDataStore.updateData { existing ->
                    val index = existing.usersList.indexOfFirst { user -> user.accountKey == existing.currentUser }
                    if (index == -1) {
                        return@updateData existing
                    }
                    val users = existing.usersList.toMutableList()
                    val target = users[index]
                    users[index] = target.copy(settings = target.settings.copy(groups = response.data))
                    existing.copy(usersList = users)
                }
            }
            is ApiResponse.Failure.Error,
            is ApiResponse.Failure.Exception -> Unit
        }
    }
}
