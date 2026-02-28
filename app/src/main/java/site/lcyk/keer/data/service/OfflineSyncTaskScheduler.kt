package site.lcyk.keer.data.service

import android.content.Context
import com.skydoves.sandwich.ApiResponse
import com.skydoves.sandwich.retrofit.statusCode
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import site.lcyk.keer.data.model.CachedGroupTagSet
import site.lcyk.keer.data.model.CachedMemoItem
import site.lcyk.keer.data.model.GroupIdAlias
import site.lcyk.keer.data.model.MemoGroup
import site.lcyk.keer.data.model.PendingGroupOperationType
import site.lcyk.keer.data.model.UserSettings
import site.lcyk.keer.data.model.toCachedMemoItem
import site.lcyk.keer.data.repository.RemoteRepository
import site.lcyk.keer.ext.settingsDataStore
import site.lcyk.keer.util.cleanupGroupAliases
import site.lcyk.keer.util.removeGroupReferences

enum class OfflineSyncTask {
    AVATAR,
    GROUP_OPERATIONS,
    GROUP_TAGS,
    GROUP_MESSAGES,
    MEMOS,
}

@Singleton
class OfflineSyncTaskScheduler @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val accountService: AccountService,
) {
    private val dispatchMutex = Mutex()

    suspend fun dispatch(vararg tasks: OfflineSyncTask): ApiResponse<Unit> {
        return dispatch(tasks.toSet())
    }

    suspend fun dispatchGroupMessages(groupId: String): ApiResponse<Unit> = withContext(Dispatchers.IO) {
        dispatchMutex.withLock {
            val normalizedGroupId = groupId.trim()
            if (normalizedGroupId.isEmpty()) {
                return@withLock ApiResponse.Success(Unit)
            }
            val remoteRepository = accountService.getRemoteRepository()
                ?: return@withLock ApiResponse.Success(Unit)
            syncPendingGroupMemos(remoteRepository, groupId = normalizedGroupId)
        }
    }

    suspend fun dispatch(
        tasks: Set<OfflineSyncTask>,
    ): ApiResponse<Unit> = withContext(Dispatchers.IO) {
        dispatchMutex.withLock {
            if (tasks.isEmpty()) {
                return@withLock ApiResponse.Success(Unit)
            }

            if (OfflineSyncTask.AVATAR in tasks) {
                val avatarSync = accountService.syncPendingAvatarIfNeeded()
                if (avatarSync !is ApiResponse.Success) {
                    return@withLock avatarSync
                }
            }

            val remoteRepository = accountService.getRemoteRepository()
            if (remoteRepository != null) {
                if (OfflineSyncTask.GROUP_OPERATIONS in tasks || OfflineSyncTask.GROUP_TAGS in tasks) {
                    val operationSync = syncPendingGroupOperations(remoteRepository)
                    if (operationSync !is ApiResponse.Success) {
                        return@withLock operationSync
                    }
                }

                if (OfflineSyncTask.GROUP_MESSAGES in tasks) {
                    val messageSync = syncPendingGroupMemos(remoteRepository, groupId = null)
                    if (messageSync !is ApiResponse.Success) {
                        return@withLock messageSync
                    }
                }
            }

            if (OfflineSyncTask.MEMOS in tasks) {
                return@withLock accountService.getRepository().sync()
            }

            ApiResponse.Success(Unit)
        }
    }

    companion object {
        val GROUP_TASKS: Set<OfflineSyncTask> = setOf(
            OfflineSyncTask.GROUP_OPERATIONS,
            OfflineSyncTask.GROUP_TAGS,
            OfflineSyncTask.GROUP_MESSAGES
        )

        val FULL_TASKS: Set<OfflineSyncTask> = setOf(
            OfflineSyncTask.AVATAR,
            OfflineSyncTask.GROUP_OPERATIONS,
            OfflineSyncTask.GROUP_TAGS,
            OfflineSyncTask.GROUP_MESSAGES,
            OfflineSyncTask.MEMOS
        )
    }

    private suspend fun syncPendingGroupOperations(
        remoteRepository: RemoteRepository
    ): ApiResponse<Unit> {
        while (true) {
            val currentSettings = readCurrentUserSettings() ?: return ApiResponse.Success(Unit)
            val operation = currentSettings.pendingGroupOperations.firstOrNull() ?: return ApiResponse.Success(Unit)
            when (operation.type) {
                PendingGroupOperationType.CREATE -> {
                    val name = operation.name?.trim().orEmpty()
                    if (name.isEmpty()) {
                        removePendingOperation(operation.operationId)
                        continue
                    }
                    when (val response = remoteRepository.createGroup(name, operation.description.orEmpty())) {
                        is ApiResponse.Success -> {
                            replaceLocalGroupId(operation.groupId, response.data)
                            removePendingOperation(operation.operationId)
                        }
                        is ApiResponse.Failure.Error -> {
                            return ApiResponse.exception(
                                IllegalStateException("Group create sync failed: HTTP ${response.statusCode}")
                            )
                        }
                        is ApiResponse.Failure.Exception -> {
                            return ApiResponse.exception(
                                IllegalStateException(
                                    response.throwable.message ?: "Group create sync failed",
                                    response.throwable
                                )
                            )
                        }
                    }
                }

                PendingGroupOperationType.JOIN -> {
                    when (val response = remoteRepository.joinGroup(operation.groupId)) {
                        is ApiResponse.Success -> {
                            upsertGroupLocal(response.data)
                            removePendingOperation(operation.operationId)
                        }
                        is ApiResponse.Failure.Error -> {
                            return ApiResponse.exception(
                                IllegalStateException("Group join sync failed: HTTP ${response.statusCode}")
                            )
                        }
                        is ApiResponse.Failure.Exception -> {
                            return ApiResponse.exception(
                                IllegalStateException(
                                    response.throwable.message ?: "Group join sync failed",
                                    response.throwable
                                )
                            )
                        }
                    }
                }

                PendingGroupOperationType.UPDATE -> {
                    when (
                        val response = remoteRepository.updateGroup(
                            groupId = operation.groupId,
                            name = operation.name,
                            description = operation.description
                        )
                    ) {
                        is ApiResponse.Success -> {
                            upsertGroupLocal(response.data)
                            removePendingOperation(operation.operationId)
                        }
                        is ApiResponse.Failure.Error -> {
                            return ApiResponse.exception(
                                IllegalStateException("Group update sync failed: HTTP ${response.statusCode}")
                            )
                        }
                        is ApiResponse.Failure.Exception -> {
                            return ApiResponse.exception(
                                IllegalStateException(
                                    response.throwable.message ?: "Group update sync failed",
                                    response.throwable
                                )
                            )
                        }
                    }
                }

                PendingGroupOperationType.DELETE_OR_LEAVE -> {
                    when (val response = remoteRepository.deleteOrLeaveGroup(operation.groupId)) {
                        is ApiResponse.Success -> {
                            mutateCurrentUserSettings { settings ->
                                removeGroupReferences(settings, operation.groupId).copy(
                                    pendingGroupOperations = settings.pendingGroupOperations
                                        .filterNot { it.operationId == operation.operationId }
                                )
                            }
                        }
                        is ApiResponse.Failure.Error -> {
                            return ApiResponse.exception(
                                IllegalStateException("Group delete/leave sync failed: HTTP ${response.statusCode}")
                            )
                        }
                        is ApiResponse.Failure.Exception -> {
                            return ApiResponse.exception(
                                IllegalStateException(
                                    response.throwable.message ?: "Group delete/leave sync failed",
                                    response.throwable
                                )
                            )
                        }
                    }
                }

                PendingGroupOperationType.ADD_TAG -> {
                    val tag = operation.tag?.trim().orEmpty()
                    if (tag.isEmpty()) {
                        removePendingOperation(operation.operationId)
                        continue
                    }
                    when (val response = remoteRepository.addGroupTag(operation.groupId, tag)) {
                        is ApiResponse.Success -> {
                            upsertCachedGroupTags(operation.groupId, response.data)
                            removePendingOperation(operation.operationId)
                        }
                        is ApiResponse.Failure.Error -> {
                            return ApiResponse.exception(
                                IllegalStateException("Group tag sync failed: HTTP ${response.statusCode}")
                            )
                        }
                        is ApiResponse.Failure.Exception -> {
                            return ApiResponse.exception(
                                IllegalStateException(
                                    response.throwable.message ?: "Group tag sync failed",
                                    response.throwable
                                )
                            )
                        }
                    }
                }
            }
        }
    }

    private suspend fun syncPendingGroupMemos(
        remoteRepository: RemoteRepository,
        groupId: String?
    ): ApiResponse<Unit> {
        while (true) {
            val settings = readCurrentUserSettings() ?: return ApiResponse.Success(Unit)
            val pending = settings.pendingGroupMemos
                .asSequence()
                .filter { candidate -> groupId == null || candidate.groupId == groupId }
                .sortedBy { it.createdAtEpochMillis }
                .firstOrNull()
                ?: return ApiResponse.Success(Unit)

            when (
                val response = remoteRepository.createGroupMessage(
                    groupId = pending.groupId,
                    content = pending.content,
                    tags = pending.tags
                )
            ) {
                is ApiResponse.Success -> {
                    removePendingMemoAndMigratePin(
                        groupId = pending.groupId,
                        localId = pending.localId,
                        remoteId = response.data.remoteId
                    )
                    appendCachedGroupMemo(
                        groupId = pending.groupId,
                        cached = response.data.toCachedMemoItem(groupId = pending.groupId)
                    )
                }
                is ApiResponse.Failure.Error -> {
                    return ApiResponse.exception(
                        IllegalStateException("Group message sync failed: HTTP ${response.statusCode}")
                    )
                }
                is ApiResponse.Failure.Exception -> {
                    return ApiResponse.exception(
                        IllegalStateException(
                            response.throwable.message ?: "Group message sync failed",
                            response.throwable
                        )
                    )
                }
            }
        }
    }

    private suspend fun appendCachedGroupMemo(groupId: String, cached: CachedMemoItem) {
        mutateCurrentUserSettings { settings ->
            val withoutGroup = settings.cachedGroupMemos.filterNot {
                it.groupId == groupId && it.remoteId == cached.remoteId
            }
            settings.copy(cachedGroupMemos = withoutGroup + cached)
        }
    }

    private suspend fun upsertGroupLocal(group: MemoGroup) {
        mutateCurrentUserSettings { settings ->
            settings.copy(groups = upsertGroup(settings.groups, group))
        }
    }

    private suspend fun upsertCachedGroupTags(groupId: String, tags: List<String>) {
        mutateCurrentUserSettings { settings ->
            val updatedTags = settings.cachedGroupTags
                .filterNot { it.groupId == groupId } + CachedGroupTagSet(
                groupId = groupId,
                tags = tags
            )
            settings.copy(cachedGroupTags = updatedTags)
        }
    }

    private suspend fun replaceLocalGroupId(localGroupId: String, remoteGroup: MemoGroup) {
        mutateCurrentUserSettings { settings ->
            val migratedGroups = upsertGroup(
                settings.groups.filterNot { it.id == localGroupId },
                remoteGroup
            )
            val migratedPendingMemos = settings.pendingGroupMemos.map { memo ->
                if (memo.groupId == localGroupId) {
                    memo.copy(groupId = remoteGroup.id)
                } else {
                    memo
                }
            }
            val migratedPinned = settings.pinnedGroupMemoKeys.map { key ->
                val prefix = "$localGroupId|"
                if (key.startsWith(prefix)) {
                    "${remoteGroup.id}|${key.removePrefix(prefix)}"
                } else {
                    key
                }
            }.distinct()
            val migratedCachedGroupMemos = settings.cachedGroupMemos.map { memo ->
                if (memo.groupId == localGroupId) {
                    memo.copy(groupId = remoteGroup.id)
                } else {
                    memo
                }
            }
            val migratedCachedGroupTags = settings.cachedGroupTags.map { tagSet ->
                if (tagSet.groupId == localGroupId) {
                    tagSet.copy(groupId = remoteGroup.id)
                } else {
                    tagSet
                }
            }
            val migratedPendingOperations = settings.pendingGroupOperations.map { operation ->
                if (operation.groupId == localGroupId) {
                    operation.copy(groupId = remoteGroup.id)
                } else {
                    operation
                }
            }
            val migratedAliases = (settings.groupIdAliases
                .filterNot { alias ->
                    alias.localId == localGroupId || alias.remoteId == remoteGroup.id
                } + GroupIdAlias(
                localId = localGroupId,
                remoteId = remoteGroup.id
            )).distinctBy { alias -> alias.localId to alias.remoteId }
            cleanupGroupAliases(
                settings.copy(
                    groups = migratedGroups,
                    pendingGroupMemos = migratedPendingMemos,
                    pinnedGroupMemoKeys = migratedPinned,
                    cachedGroupMemos = migratedCachedGroupMemos,
                    cachedGroupTags = migratedCachedGroupTags,
                    pendingGroupOperations = migratedPendingOperations,
                    groupIdAliases = migratedAliases
                )
            )
        }
    }

    private suspend fun removePendingOperation(operationId: String) {
        mutateCurrentUserSettings { settings ->
            settings.copy(
                pendingGroupOperations = settings.pendingGroupOperations.filterNot { it.operationId == operationId }
            )
        }
    }

    private suspend fun removePendingMemoAndMigratePin(
        groupId: String,
        localId: String,
        remoteId: String
    ) {
        mutateCurrentUserSettings { settings ->
            val pinnedKeys = settings.pinnedGroupMemoKeys.toMutableSet()
            val localKey = groupMemoKey(groupId, localMemoRemoteId(localId))
            val remoteKey = groupMemoKey(groupId, remoteId)
            if (localKey in pinnedKeys) {
                pinnedKeys -= localKey
                pinnedKeys += remoteKey
            }
            settings.copy(
                pendingGroupMemos = settings.pendingGroupMemos
                    .filterNot { memo -> memo.groupId == groupId && memo.localId == localId },
                pinnedGroupMemoKeys = pinnedKeys.toList()
            )
        }
    }

    private suspend fun readCurrentUserSettings(): UserSettings? {
        val settings = context.settingsDataStore.data.first()
        return settings.usersList
            .firstOrNull { user -> user.accountKey == settings.currentUser }
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

    private fun groupMemoKey(groupId: String, memoRemoteId: String): String {
        return "$groupId|$memoRemoteId"
    }

    private fun localMemoRemoteId(localId: String): String {
        return "local:$localId"
    }
}
