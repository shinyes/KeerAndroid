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
import kotlinx.coroutines.Dispatchers
import site.lcyk.keer.R
import site.lcyk.keer.data.model.Account
import site.lcyk.keer.data.model.LOCAL_GROUP_PREFIX
import site.lcyk.keer.data.model.MemoGroup
import site.lcyk.keer.data.model.PendingGroupOperation
import site.lcyk.keer.data.model.PendingGroupOperationType
import site.lcyk.keer.data.model.SyncDomain
import site.lcyk.keer.data.model.isLocalGroupId
import site.lcyk.keer.data.service.AccountLocalSettingsStore
import site.lcyk.keer.data.service.AccountService
import site.lcyk.keer.data.service.MemoService
import site.lcyk.keer.data.service.OfflineGroupStore
import site.lcyk.keer.ext.getErrorMessage
import site.lcyk.keer.ext.string

@HiltViewModel
class GroupManagementViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val accountService: AccountService,
    private val accountLocalSettingsStore: AccountLocalSettingsStore,
    private val offlineGroupStore: OfflineGroupStore,
    private val memoService: MemoService,
) : ViewModel() {
    private val _groups = MutableStateFlow<List<MemoGroup>>(emptyList())
    val groups: StateFlow<List<MemoGroup>> = _groups.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    suspend fun refreshGroups() = withContext(Dispatchers.IO) {
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

    suspend fun createGroup(name: String, description: String): Boolean = withContext(Dispatchers.IO) {
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

    suspend fun addGroupMember(groupId: String, userIdentifier: String): Boolean = withContext(Dispatchers.IO) {
        val normalizedGroupId = groupId.trim()
        val normalizedUserIdentifier = userIdentifier.trim()
        if (normalizedGroupId.isEmpty() || normalizedUserIdentifier.isEmpty()) {
            _errorMessage.value = R.string.group_error_id_required.string
            return@withContext false
        }

        val accountKey = readCurrentAccountKey() ?: return@withContext false
        offlineGroupStore.enqueuePendingGroupOperation(
            accountKey,
            PendingGroupOperation(
                operationId = UUID.randomUUID().toString(),
                type = PendingGroupOperationType.ADD_MEMBER,
                groupId = normalizedGroupId,
                targetUser = normalizedUserIdentifier
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
                description = description.trim(),
                updatedAtEpochMillis = System.currentTimeMillis(),
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
        if (isLocalGroupId(normalizedGroupId)) {
            offlineGroupStore.removeGroupReferences(accountKey, normalizedGroupId)
            _groups.value = readStoredGroups()
            _errorMessage.value = null
            return@withContext true
        }
        val alreadyPendingDelete = offlineGroupStore.getPendingGroupOperations(accountKey)
            .any { operation ->
                operation.type == PendingGroupOperationType.DELETE_OR_LEAVE &&
                    operation.groupId == normalizedGroupId
            }
        if (alreadyPendingDelete) {
            _groups.value = readStoredGroups()
            _errorMessage.value = null
            return@withContext true
        }
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
        when (val response = memoService.sync(
            force = true,
            domains = setOf(SyncDomain.GROUPS)
        )) {
            is ApiResponse.Success -> Unit
            else -> {
                _errorMessage.value = response.getErrorMessage()
                _groups.value = readStoredGroups()
            }
        }
    }

    private suspend fun refreshGroupsFromRemote() {
        _groups.value = readStoredGroups()
        _errorMessage.value = null
    }

    private suspend fun readStoredGroups(): List<MemoGroup> {
        val accountKey = readCurrentAccountKey() ?: return emptyList()
        return offlineGroupStore.getGroups(accountKey)
    }

    private suspend fun readCurrentAccountKey(): String? {
        return accountLocalSettingsStore.observeCurrentAccountKey().first()
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
            members = emptyList(),
            updatedAtEpochMillis = System.currentTimeMillis(),
        )
    }

}
