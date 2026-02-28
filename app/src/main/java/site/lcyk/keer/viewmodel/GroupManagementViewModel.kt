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
import site.lcyk.keer.data.model.UserSettings
import site.lcyk.keer.data.service.AccountService
import site.lcyk.keer.data.service.OfflineSyncTask
import site.lcyk.keer.data.service.OfflineSyncTaskScheduler
import site.lcyk.keer.ext.getErrorMessage
import site.lcyk.keer.ext.settingsDataStore
import site.lcyk.keer.ext.string
import site.lcyk.keer.util.cleanupGroupAliases
import site.lcyk.keer.util.removeGroupReferences

@HiltViewModel
class GroupManagementViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val accountService: AccountService,
    private val offlineSyncTaskScheduler: OfflineSyncTaskScheduler
) : ViewModel() {
    private val _groups = MutableStateFlow<List<MemoGroup>>(emptyList())
    val groups: StateFlow<List<MemoGroup>> = _groups.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    suspend fun refreshGroups() = withContext(viewModelScope.coroutineContext) {
        _groups.value = readCurrentUserSettings()?.groups.orEmpty()
        _loading.value = true
        _errorMessage.value = null
        try {
            syncPendingGroupTasks()
            _groups.value = readCurrentUserSettings()?.groups.orEmpty()
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

        mutateCurrentUserSettings { current ->
            current.copy(
                groups = upsertGroup(current.groups, localGroup),
                pendingGroupOperations = current.pendingGroupOperations + PendingGroupOperation(
                    operationId = UUID.randomUUID().toString(),
                    type = PendingGroupOperationType.CREATE,
                    groupId = localGroup.id,
                    name = normalizedName,
                    description = description.trim()
                )
            )
        }
        _groups.value = readCurrentUserSettings()?.groups.orEmpty()
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

        mutateCurrentUserSettings { current ->
            val placeholder = MemoGroup(
                id = normalizedGroupId,
                name = normalizedGroupId,
                description = "",
                creatorId = "",
                creatorName = "",
                members = emptyList()
            )
            current.copy(
                groups = upsertGroup(current.groups, placeholder),
                pendingGroupOperations = current.pendingGroupOperations + PendingGroupOperation(
                    operationId = UUID.randomUUID().toString(),
                    type = PendingGroupOperationType.JOIN,
                    groupId = normalizedGroupId
                )
            )
        }
        _groups.value = readCurrentUserSettings()?.groups.orEmpty()
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

        mutateCurrentUserSettings { current ->
            val updatedGroups = current.groups.map { group ->
                if (group.id != normalizedGroupId) {
                    group
                } else {
                    group.copy(
                        name = normalizedName,
                        description = description.trim()
                    )
                }
            }
            current.copy(
                groups = updatedGroups,
                pendingGroupOperations = current.pendingGroupOperations + PendingGroupOperation(
                    operationId = UUID.randomUUID().toString(),
                    type = PendingGroupOperationType.UPDATE,
                    groupId = normalizedGroupId,
                    name = normalizedName,
                    description = description.trim()
                )
            )
        }
        _groups.value = readCurrentUserSettings()?.groups.orEmpty()
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

        mutateCurrentUserSettings { current ->
            val withoutGroup = cleanupGroupAliases(removeGroupReferences(current, normalizedGroupId))
            withoutGroup.copy(
                pendingGroupOperations = withoutGroup.pendingGroupOperations + PendingGroupOperation(
                    operationId = UUID.randomUUID().toString(),
                    type = PendingGroupOperationType.DELETE_OR_LEAVE,
                    groupId = normalizedGroupId
                )
            )
        }
        _groups.value = readCurrentUserSettings()?.groups.orEmpty()
        _errorMessage.value = null
        trySyncPendingOperationsAndRefresh()
        true
    }

    private suspend fun trySyncPendingOperationsAndRefresh() {
        syncPendingGroupTasks()
        _groups.value = readCurrentUserSettings()?.groups.orEmpty()
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
                _groups.value = readCurrentUserSettings()?.groups.orEmpty()
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
        mutateCurrentUserSettings { current ->
            current.copy(groups = groups)
        }
    }

    private suspend fun readCurrentUserSettings(): UserSettings? {
        val settings = context.settingsDataStore.data.first()
        return settings.usersList
            .firstOrNull { it.accountKey == settings.currentUser }
            ?.settings
    }

    private fun upsertGroup(groups: List<MemoGroup>, target: MemoGroup): List<MemoGroup> {
        val withoutTarget = groups.filterNot { it.id == target.id }
        return (withoutTarget + target).sortedByDescending { it.createdAtEpochMillis }
    }

    private suspend fun mutateCurrentUserSettings(transform: (UserSettings) -> UserSettings) {
        context.settingsDataStore.updateData { existing ->
            val index = existing.usersList.indexOfFirst { user -> user.accountKey == existing.currentUser }
            if (index == -1) {
                return@updateData existing
            }

            val users = existing.usersList.toMutableList()
            val target = users[index]
            users[index] = target.copy(settings = transform(target.settings))
            existing.copy(usersList = users)
        }
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
