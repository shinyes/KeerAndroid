package site.lcyk.keer.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.skydoves.sandwich.ApiResponse
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import site.lcyk.keer.R
import site.lcyk.keer.data.model.Account
import site.lcyk.keer.data.model.MemoGroup
import site.lcyk.keer.data.model.PendingGroupOperation
import site.lcyk.keer.data.model.PendingGroupOperationType
import site.lcyk.keer.data.service.AccountService
import site.lcyk.keer.data.service.OfflineGroupStore
import site.lcyk.keer.data.service.OfflineSyncTask
import site.lcyk.keer.data.service.OfflineSyncTaskScheduler
import site.lcyk.keer.ext.getErrorMessage
import site.lcyk.keer.ext.settingsDataStore
import site.lcyk.keer.ext.string

@HiltViewModel
class GroupManagementViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val accountService: AccountService,
    private val offlineGroupStore: OfflineGroupStore,
    private val offlineSyncTaskScheduler: OfflineSyncTaskScheduler
) : ViewModel() {
    private val _groups = MutableStateFlow<List<MemoGroup>>(emptyList())
    val groups: StateFlow<List<MemoGroup>> = _groups.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    suspend fun refreshGroups() = withContext(viewModelScope.coroutineContext) {
        _groups.value = readStoredGroups()
        _loading.value = true
        _errorMessage.value = null
        try {
            syncPendingGroupTasks()
            _groups.value = readStoredGroups()
            refreshGroupsFromRemote()
        } finally {
            _loading.value = false
        }
    }

    suspend fun createGroup(name: String, description: String): Boolean = withContext(viewModelScope.coroutineContext) {
        val normalizedName = name.trim()
        if (normalizedName.isEmpty()) {
            _errorMessage.value = R.string.group_error_name_required.string
            return@withContext false
        }

        val localGroup = buildLocalGroup(
            name = normalizedName,
            description = description.trim()
        )

        val accountKey = readCurrentAccountKey() ?: return@withContext false
        offlineGroupStore.upsertGroup(accountKey, localGroup)
        offlineGroupStore.enqueuePendingGroupOperation(
            accountKey,
            PendingGroupOperation(
                operationId = UUID.randomUUID().toString(),
                type = PendingGroupOperationType.CREATE,
                groupId = localGroup.id,
                name = normalizedName,
                description = description.trim()
            )
        )
        _groups.value = readStoredGroups()
        _errorMessage.value = null
        trySyncPendingOperationsAndRefresh()
        true
    }

    suspend fun joinGroup(groupId: String): Boolean = withContext(viewModelScope.coroutineContext) {
        val normalizedGroupId = groupId.trim()
        if (normalizedGroupId.isEmpty()) {
            _errorMessage.value = R.string.group_error_id_required.string
            return@withContext false
        }

        val accountKey = readCurrentAccountKey() ?: return@withContext false
        val placeholder = MemoGroup(
            id = normalizedGroupId,
            name = normalizedGroupId,
            description = "",
            creatorId = "",
            creatorName = "",
            members = emptyList()
        )
        offlineGroupStore.upsertGroup(accountKey, placeholder)
        offlineGroupStore.enqueuePendingGroupOperation(
            accountKey,
            PendingGroupOperation(
                operationId = UUID.randomUUID().toString(),
                type = PendingGroupOperationType.JOIN,
                groupId = normalizedGroupId
            )
        )
        _groups.value = readStoredGroups()
        _errorMessage.value = null
        trySyncPendingOperationsAndRefresh()
        true
    }

    suspend fun updateGroup(groupId: String, name: String, description: String): Boolean = withContext(viewModelScope.coroutineContext) {
        val normalizedGroupId = groupId.trim()
        val normalizedName = name.trim()
        if (normalizedGroupId.isEmpty() || normalizedName.isEmpty()) {
            _errorMessage.value = R.string.group_error_invalid_update_request.string
            return@withContext false
        }

        val accountKey = readCurrentAccountKey() ?: return@withContext false
        val updatedGroup = readStoredGroups()
            .firstOrNull { group -> group.id == normalizedGroupId }
            ?.copy(
                name = normalizedName,
                description = description.trim()
            )
            ?: return@withContext false
        offlineGroupStore.upsertGroup(accountKey, updatedGroup)
        offlineGroupStore.enqueuePendingGroupOperation(
            accountKey,
            PendingGroupOperation(
                operationId = UUID.randomUUID().toString(),
                type = PendingGroupOperationType.UPDATE,
                groupId = normalizedGroupId,
                name = normalizedName,
                description = description.trim()
            )
        )
        _groups.value = readStoredGroups()
        _errorMessage.value = null
        trySyncPendingOperationsAndRefresh()
        true
    }

    suspend fun deleteOrLeaveGroup(groupId: String): Boolean = withContext(viewModelScope.coroutineContext) {
        val normalizedGroupId = groupId.trim()
        if (normalizedGroupId.isEmpty()) {
            _errorMessage.value = R.string.group_error_id_required.string
            return@withContext false
        }

        val accountKey = readCurrentAccountKey() ?: return@withContext false
        offlineGroupStore.removeGroupReferences(accountKey, normalizedGroupId)
        offlineGroupStore.enqueuePendingGroupOperation(
            accountKey,
            PendingGroupOperation(
                operationId = UUID.randomUUID().toString(),
                type = PendingGroupOperationType.DELETE_OR_LEAVE,
                groupId = normalizedGroupId
            )
        )
        _groups.value = readStoredGroups()
        _errorMessage.value = null
        trySyncPendingOperationsAndRefresh()
        true
    }

    private suspend fun trySyncPendingOperationsAndRefresh() {
        syncPendingGroupTasks()
        _groups.value = readStoredGroups()
        refreshGroupsFromRemote()
    }

    private suspend fun syncPendingGroupTasks() {
        when (
            val response = offlineSyncTaskScheduler.dispatch(
                setOf(OfflineSyncTask.GROUP_OPERATIONS, OfflineSyncTask.GROUP_TAGS)
            )
        ) {
            is ApiResponse.Success -> Unit
            else -> {
                _errorMessage.value = response.getErrorMessage()
                _groups.value = readStoredGroups()
            }
        }
    }

    private suspend fun refreshGroupsFromRemote() {
        val remoteRepository = accountService.getRemoteRepository() ?: return
        when (val response = remoteRepository.listGroups()) {
            is ApiResponse.Success -> {
                val loaded = response.data
                _groups.value = loaded
                persistGroups(loaded)
                _errorMessage.value = null
            }
            else -> {
                _errorMessage.value = response.getErrorMessage()
            }
        }
    }

    private suspend fun persistGroups(groups: List<MemoGroup>) {
        readCurrentAccountKey()?.let { accountKey ->
            offlineGroupStore.replaceGroups(accountKey, groups)
        }
    }

    private suspend fun readStoredGroups(): List<MemoGroup> {
        val accountKey = readCurrentAccountKey() ?: return emptyList()
        return offlineGroupStore.getGroups(accountKey)
    }

    private suspend fun readCurrentAccountKey(): String? {
        return context.settingsDataStore.data.first().currentUser.takeIf { it.isNotBlank() }
    }

    private suspend fun buildLocalGroup(name: String, description: String): MemoGroup {
        val account = accountService.currentAccount.first()
        val creatorId = when (account) {
            is Account.KeerV2 -> account.info.id.toString()
            is Account.Local -> "local"
            null -> "unknown"
        }
        val creatorName = when (account) {
            is Account.KeerV2 -> account.info.name.ifBlank { creatorId }
            is Account.Local -> "Local"
            null -> creatorId
        }
        return MemoGroup(
            id = "$LOCAL_GROUP_PREFIX${UUID.randomUUID()}",
            name = name,
            description = description,
            creatorId = creatorId,
            creatorName = creatorName,
            members = emptyList()
        )
    }

    companion object {
        private const val LOCAL_GROUP_PREFIX = "local-group:"
    }
}
