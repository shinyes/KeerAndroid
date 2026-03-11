package site.lcyk.keer.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.skydoves.sandwich.ApiResponse
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import site.lcyk.keer.data.local.entity.MemoEntity
import site.lcyk.keer.data.local.entity.ResourceEntity
import site.lcyk.keer.data.model.Account
import site.lcyk.keer.data.model.Memo
import site.lcyk.keer.data.model.Resource
import site.lcyk.keer.data.model.User
import site.lcyk.keer.data.model.toMemo
import site.lcyk.keer.data.service.AccountService
import site.lcyk.keer.data.service.MemoService
import site.lcyk.keer.data.service.OfflineGroupStore
import site.lcyk.keer.data.service.OfflineSyncTaskScheduler
import site.lcyk.keer.data.service.SyncTrigger
import site.lcyk.keer.ext.getErrorMessage
import site.lcyk.keer.util.extractCollaboratorIds
import site.lcyk.keer.util.normalizeCollaboratorId
import site.lcyk.keer.util.normalizeTagList

data class ExploreMemoItem(
    val memo: Memo,
    val groupId: String? = null
)

@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class ExploreViewModel @Inject constructor(
    private val accountService: AccountService,
    private val memoService: MemoService,
    private val offlineGroupStore: OfflineGroupStore,
    private val offlineSyncTaskScheduler: OfflineSyncTaskScheduler,
) : ViewModel() {
    private val _mutationErrorMessage = MutableStateFlow<String?>(null)
    val mutationErrorMessage: StateFlow<String?> = _mutationErrorMessage.asStateFlow()

    val groups = accountService.currentAccount
        .flatMapLatest { account ->
            val accountKey = account?.accountKey().orEmpty()
            if (accountKey.isBlank()) {
                emptyFlow()
            } else {
                offlineGroupStore.observeGroups(accountKey)
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val exploreMemos = accountService.currentAccount
        .flatMapLatest { account ->
            val accountKey = account?.accountKey().orEmpty()
            if (accountKey.isBlank()) {
                return@flatMapLatest flowOf(PagingData.empty())
            }
            combine(
                memoService.memos,
                offlineGroupStore.observeAllCachedGroupMemos(accountKey),
                offlineGroupStore.observePinnedGroupMemoKeys(accountKey),
            ) { localMemos, cachedGroupMemos, pinnedGroupMemoKeys ->
                PagingData.from(
                    buildExploreMemoItems(
                        account = account,
                        localMemos = localMemos,
                        cachedGroupMemos = cachedGroupMemos,
                        pinnedGroupMemoKeys = pinnedGroupMemoKeys,
                    )
                )
            }
        }
        .cachedIn(viewModelScope)

    init {
        viewModelScope.launch {
            accountService.currentAccount.collectLatest { account ->
                if (account is Account.KeerV2) {
                    memoService.requestSync(trigger = SyncTrigger.AUTO, force = false)
                    offlineSyncTaskScheduler.refreshAllGroupCaches()
                }
            }
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
                enqueueExploreRefresh(item.groupId)
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
                enqueueExploreRefresh(item.groupId)
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
        val refreshResponse = offlineSyncTaskScheduler.refreshAllGroupCaches()
        if (refreshResponse !is ApiResponse.Success) {
            _mutationErrorMessage.value = refreshResponse.getErrorMessage()
        }
    }

    private fun enqueueExploreRefresh(groupId: String?) {
        viewModelScope.launch {
            if (groupId.isNullOrBlank()) {
                memoService.requestSync(trigger = SyncTrigger.MUTATION, force = true)
            } else {
                offlineSyncTaskScheduler.dispatchGroupMessages(groupId)
            }
        }
    }

    private fun buildExploreMemoItems(
        account: Account?,
        localMemos: List<MemoEntity>,
        cachedGroupMemos: List<Pair<site.lcyk.keer.data.model.CachedMemoItem, String>>,
        pinnedGroupMemoKeys: Set<String>,
    ): List<ExploreMemoItem> {
        val collaborative = buildCollaborativeMemoItems(account, localMemos)
        val groupItems = cachedGroupMemos.map { (cachedMemo, groupId) ->
            val memo = cachedMemo.toMemo()
            val pinned = groupMemoKey(groupId, memo.remoteId) in pinnedGroupMemoKeys
            ExploreMemoItem(
                memo = if (memo.pinned == pinned) memo else memo.copy(pinned = pinned),
                groupId = groupId
            )
        }
        return (collaborative + groupItems)
            .distinctBy { item -> "${item.groupId.orEmpty()}|${item.memo.remoteId}" }
            .sortedByDescending { item -> item.memo.date }
    }

    private fun buildCollaborativeMemoItems(
        account: Account?,
        localMemos: List<MemoEntity>
    ): List<ExploreMemoItem> {
        val currentUserId = (account as? Account.KeerV2)?.info?.id?.toString()?.let(::normalizeCollaboratorId)
            ?: return emptyList()
        return localMemos
            .asSequence()
            .filter { memo ->
                extractCollaboratorIds(memo.tags).any { collaboratorId ->
                    normalizeCollaboratorId(collaboratorId) == currentUserId
                }
            }
            .map { memo -> ExploreMemoItem(memo = memo.toExploreMemo(), groupId = null) }
            .toList()
    }

    private fun MemoEntity.toExploreMemo(): Memo {
        return Memo(
            remoteId = remoteId ?: identifier,
            content = content,
            date = date,
            pinned = pinned,
            visibility = visibility,
            resources = resources.map { resource -> resource.toExploreResource() },
            tags = tags,
            latitude = latitude,
            longitude = longitude,
            creator = null,
            archived = archived,
            updatedAt = lastSyncedAt ?: lastModified
        )
    }

    private fun ResourceEntity.toExploreResource(): Resource {
        return Resource(
            remoteId = remoteId ?: identifier,
            date = date,
            filename = filename,
            mimeType = mimeType,
            encryptionMetadata = encryptionMetadata,
            uri = uri,
            localUri = localUri,
            thumbnailUri = thumbnailUri,
            thumbnailLocalUri = thumbnailLocalUri
        )
    }

    private fun groupMemoKey(groupId: String, memoRemoteId: String): String {
        return "$groupId|$memoRemoteId"
    }
}
