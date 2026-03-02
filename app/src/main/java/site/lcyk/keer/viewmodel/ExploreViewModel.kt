package site.lcyk.keer.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.skydoves.sandwich.ApiResponse
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import site.lcyk.keer.data.model.Account
import site.lcyk.keer.data.model.Memo
import site.lcyk.keer.data.model.MemoGroup
import site.lcyk.keer.data.model.toCachedMemoItem
import site.lcyk.keer.data.model.toMemo
import site.lcyk.keer.data.repository.RemoteRepository
import site.lcyk.keer.data.service.AccountService
import site.lcyk.keer.ext.getErrorMessage
import site.lcyk.keer.ext.settingsDataStore
import site.lcyk.keer.util.buildCollaboratorFilterExpression
import site.lcyk.keer.util.normalizeTagList

data class ExploreMemoItem(
    val memo: Memo,
    val groupId: String? = null
)

@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class ExploreViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val accountService: AccountService
) : ViewModel() {
    private val refreshSignal = MutableStateFlow(0)
    private val _mutationErrorMessage = MutableStateFlow<String?>(null)
    val mutationErrorMessage: StateFlow<String?> = _mutationErrorMessage.asStateFlow()

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
        groups,
        refreshSignal
    ) { account, currentGroups, _ ->
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
    ): List<ExploreMemoItem> {
        val collaborative = loadCollaborativeMemos(account, remoteRepository)
        val groupMemos = loadGroupScopeMemos(remoteRepository, groups)
        return (collaborative + groupMemos)
            .distinctBy { item -> item.memo.remoteId }
            .sortedByDescending { item -> item.memo.date }
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
    ): List<ExploreMemoItem> {
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

        return loaded.map { memo ->
            ExploreMemoItem(memo = memo, groupId = null)
        }
    }

    private suspend fun loadGroupScopeMemos(
        remoteRepository: RemoteRepository,
        groups: List<MemoGroup>
    ): List<ExploreMemoItem> {
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
    ): List<ExploreMemoItem> {
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

        return loaded.map { memo ->
            ExploreMemoItem(
                memo = memo,
                groupId = groupId
            )
        }
    }

    private suspend fun readCachedExploreMemos(): List<ExploreMemoItem> {
        val settings = context.settingsDataStore.data.first()
        val userSettings = settings.usersList
            .firstOrNull { it.accountKey == settings.currentUser }
            ?.settings
            ?: return emptyList()
        return userSettings.cachedExploreMemos
            .map { item ->
                ExploreMemoItem(
                    memo = item.toMemo(),
                    groupId = item.groupId
                )
            }
            .sortedByDescending { item -> item.memo.date }
    }

    private suspend fun persistExploreMemos(memos: List<ExploreMemoItem>) {
        context.settingsDataStore.updateData { existing ->
            val index = existing.usersList.indexOfFirst { user -> user.accountKey == existing.currentUser }
            if (index == -1) {
                return@updateData existing
            }
            val users = existing.usersList.toMutableList()
            val target = users[index]
            users[index] = target.copy(
                settings = target.settings.copy(
                    cachedExploreMemos = memos.map { item ->
                        item.memo.toCachedMemoItem(groupId = item.groupId)
                    }
                )
            )
            existing.copy(usersList = users)
        }
    }

    suspend fun updateExploreMemo(
        item: ExploreMemoItem,
        content: String,
        tags: List<String>
    ): Boolean {
        val remoteRepository = accountService.getRemoteRepository() ?: run {
            _mutationErrorMessage.value = "Current account does not support remote memo operations"
            return false
        }
        val normalizedContent = content.trim()
        if (normalizedContent.isEmpty()) {
            return false
        }
        val normalizedTags = normalizeTagList(tags)
        val response = if (item.groupId.isNullOrBlank()) {
            remoteRepository.updateMemo(
                remoteId = item.memo.remoteId,
                content = normalizedContent,
                tags = normalizedTags
            )
        } else {
            remoteRepository.updateGroupMessage(
                groupId = item.groupId,
                messageRemoteId = item.memo.remoteId,
                content = normalizedContent,
                tags = normalizedTags
            )
        }
        return when (response) {
            is ApiResponse.Success -> {
                _mutationErrorMessage.value = null
                refreshSignal.update { current -> current + 1 }
                true
            }
            else -> {
                _mutationErrorMessage.value = response.getErrorMessage()
                false
            }
        }
    }

    suspend fun deleteExploreMemo(item: ExploreMemoItem): Boolean {
        val remoteRepository = accountService.getRemoteRepository() ?: run {
            _mutationErrorMessage.value = "Current account does not support remote memo operations"
            return false
        }
        val response = if (item.groupId.isNullOrBlank()) {
            remoteRepository.deleteMemo(item.memo.remoteId)
        } else {
            remoteRepository.deleteGroupMessage(item.groupId, item.memo.remoteId)
        }
        return when (response) {
            is ApiResponse.Success -> {
                _mutationErrorMessage.value = null
                refreshSignal.update { current -> current + 1 }
                true
            }
            else -> {
                _mutationErrorMessage.value = response.getErrorMessage()
                false
            }
        }
    }

    fun clearMutationError() {
        _mutationErrorMessage.value = null
    }

    suspend fun refreshExploreMemos() {
        syncGroupsFromRemote()
        refreshSignal.update { current -> current + 1 }
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
