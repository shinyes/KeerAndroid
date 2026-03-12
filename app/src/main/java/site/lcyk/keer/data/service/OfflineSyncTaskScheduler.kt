package site.lcyk.keer.data.service

import com.skydoves.sandwich.ApiResponse
import com.skydoves.sandwich.retrofit.statusCode
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import site.lcyk.keer.data.model.CachedMemoItem
import site.lcyk.keer.data.model.MemoGroup
import site.lcyk.keer.data.model.PendingGroupOperationType
import site.lcyk.keer.data.repository.ResourceEncryptionScope
import site.lcyk.keer.data.model.toCachedMemoItem
import site.lcyk.keer.data.repository.RemoteRepository

enum class OfflineSyncTask {
    AVATAR,
    GROUP_OPERATIONS,
    GROUP_TAGS,
    GROUP_MESSAGES,
    USERS,
    MEMOS,
}

@Singleton
class OfflineSyncTaskScheduler @Inject constructor(
    private val accountService: AccountService,
    private val offlineGroupStore: OfflineGroupStore,
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
            val accountKey = readCurrentAccountKey() ?: return@withLock ApiResponse.Success(Unit)
            val remoteRepository = accountService.getRemoteRepository()
                ?: return@withLock ApiResponse.Success(Unit)
            val pendingSync = syncPendingGroupMemos(remoteRepository, groupId = normalizedGroupId)
            if (pendingSync !is ApiResponse.Success) {
                return@withLock pendingSync
            }
            val groupDirectoryRefresh = refreshGroupDirectory(remoteRepository, accountKey)
            if (groupDirectoryRefresh !is ApiResponse.Success) {
                return@withLock when (groupDirectoryRefresh) {
                    is ApiResponse.Failure.Error -> ApiResponse.exception(
                        IllegalStateException("Group cache refresh failed: HTTP ${groupDirectoryRefresh.statusCode}")
                    )
                    is ApiResponse.Failure.Exception -> ApiResponse.exception(
                        IllegalStateException(
                            groupDirectoryRefresh.throwable.message ?: "Group cache refresh failed",
                            groupDirectoryRefresh.throwable
                        )
                    )
                    is ApiResponse.Success -> ApiResponse.Success(Unit)
                }
            }
            refreshGroupCache(remoteRepository, accountKey, normalizedGroupId)
        }
    }

    suspend fun refreshAllGroupCaches(): ApiResponse<Unit> = withContext(Dispatchers.IO) {
        dispatchMutex.withLock {
            val accountKey = readCurrentAccountKey() ?: return@withLock ApiResponse.Success(Unit)
            val remoteRepository = accountService.getRemoteRepository()
                ?: return@withLock ApiResponse.Success(Unit)
            refreshAllGroupCaches(remoteRepository, accountKey)
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

                if (OfflineSyncTask.USERS in tasks) {
                    val userSync = remoteRepository.syncKnownUsers()
                    if (userSync !is ApiResponse.Success) {
                        return@withLock userSync
                    }
                }

                if (tasks.any { task ->
                        task == OfflineSyncTask.GROUP_OPERATIONS ||
                            task == OfflineSyncTask.GROUP_TAGS ||
                            task == OfflineSyncTask.GROUP_MESSAGES
                    }
                ) {
                    val accountKey = readCurrentAccountKey() ?: return@withLock ApiResponse.Success(Unit)
                    val groupDirectoryRefresh = refreshGroupDirectory(remoteRepository, accountKey)
                    if (groupDirectoryRefresh !is ApiResponse.Success) {
                        return@withLock when (groupDirectoryRefresh) {
                            is ApiResponse.Failure.Error -> ApiResponse.exception(
                                IllegalStateException("Group cache refresh failed: HTTP ${groupDirectoryRefresh.statusCode}")
                            )
                            is ApiResponse.Failure.Exception -> ApiResponse.exception(
                                IllegalStateException(
                                    groupDirectoryRefresh.throwable.message ?: "Group cache refresh failed",
                                    groupDirectoryRefresh.throwable
                                )
                            )
                            is ApiResponse.Success -> ApiResponse.Success(Unit)
                        }
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
            OfflineSyncTask.USERS,
            OfflineSyncTask.MEMOS
        )
    }

    private suspend fun syncPendingGroupOperations(
        remoteRepository: RemoteRepository
    ): ApiResponse<Unit> {
        while (true) {
            val accountKey = readCurrentAccountKey() ?: return ApiResponse.Success(Unit)
            val operation = offlineGroupStore.getPendingGroupOperations(accountKey)
                .firstOrNull()
                ?: return ApiResponse.Success(Unit)
            when (operation.type) {
                PendingGroupOperationType.CREATE -> {
                    val name = operation.name?.trim().orEmpty()
                    if (name.isEmpty()) {
                        removePendingOperation(accountKey, operation.operationId)
                        continue
                    }
                    when (val response = remoteRepository.createGroup(name, operation.description.orEmpty())) {
                        is ApiResponse.Success -> {
                            offlineGroupStore.replaceLocalGroupId(accountKey, operation.groupId, response.data)
                            removePendingOperation(accountKey, operation.operationId)
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

                PendingGroupOperationType.ADD_MEMBER -> {
                    val targetUser = operation.targetUser?.trim().orEmpty()
                    if (targetUser.isEmpty()) {
                        removePendingOperation(accountKey, operation.operationId)
                        continue
                    }
                    when (val response = remoteRepository.addGroupMember(operation.groupId, targetUser)) {
                        is ApiResponse.Success -> {
                            upsertGroupLocal(accountKey, response.data)
                            removePendingOperation(accountKey, operation.operationId)
                        }
                        is ApiResponse.Failure.Error -> {
                            return ApiResponse.exception(
                                IllegalStateException("Group member invite sync failed: HTTP ${response.statusCode}")
                            )
                        }
                        is ApiResponse.Failure.Exception -> {
                            return ApiResponse.exception(
                                IllegalStateException(
                                    response.throwable.message ?: "Group member invite sync failed",
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
                            upsertGroupLocal(accountKey, response.data)
                            removePendingOperation(accountKey, operation.operationId)
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
                            offlineGroupStore.removeGroupReferences(accountKey, operation.groupId)
                            removePendingOperation(accountKey, operation.operationId)
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
                        removePendingOperation(accountKey, operation.operationId)
                        continue
                    }
                    val cachedTags = offlineGroupStore.getCachedGroupTags(accountKey, operation.groupId)
                    upsertCachedGroupTags(
                        accountKey,
                        operation.groupId,
                        normalizeTags(cachedTags + tag)
                    )
                    removePendingOperation(accountKey, operation.operationId)
                }
            }
        }
    }

    private suspend fun syncPendingGroupMemos(
        remoteRepository: RemoteRepository,
        groupId: String?
    ): ApiResponse<Unit> {
        while (true) {
            val accountKey = readCurrentAccountKey() ?: return ApiResponse.Success(Unit)
            val pending = offlineGroupStore.getPendingGroupMemos(accountKey, groupId)
                .asSequence()
                .sortedBy { it.createdAtEpochMillis }
                .firstOrNull()
                ?: return ApiResponse.Success(Unit)
            val resourceRemoteIds = if (pending.resourceIdentifiers.isEmpty()) {
                emptyList()
            } else {
                when (
                    val uploadResponse = accountService.getRepository().resolveRemoteResourceIds(
                        resourceIdentifiers = pending.resourceIdentifiers,
                        encryptionScope = ResourceEncryptionScope.Group(pending.groupId),
                    )
                ) {
                    is ApiResponse.Success -> uploadResponse.data
                    is ApiResponse.Failure.Error -> {
                        return ApiResponse.exception(
                            IllegalStateException("Group resource upload sync failed: HTTP ${uploadResponse.statusCode.code}")
                        )
                    }
                    is ApiResponse.Failure.Exception -> {
                        return ApiResponse.exception(
                            IllegalStateException(
                                uploadResponse.throwable.message ?: "Group resource upload sync failed",
                                uploadResponse.throwable
                            )
                        )
                    }
                }
            }

            val response = remoteRepository.createGroupMessage(
                groupId = pending.groupId,
                content = pending.content,
                tags = pending.tags,
                resourceRemoteIds = resourceRemoteIds
            )

            when (response) {
                is ApiResponse.Success -> {
                    removePendingMemoAndMigratePin(
                        accountKey = accountKey,
                        groupId = pending.groupId,
                        localId = pending.localId,
                        remoteId = response.data.remoteId
                    )
                    appendCachedGroupMemo(
                        accountKey = accountKey,
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

    private suspend fun appendCachedGroupMemo(accountKey: String, groupId: String, cached: CachedMemoItem) {
        offlineGroupStore.upsertCachedGroupMemo(accountKey, groupId, cached)
    }

    private suspend fun refreshAllGroupCaches(
        remoteRepository: RemoteRepository,
        accountKey: String
    ): ApiResponse<Unit> {
        val operationSync = syncPendingGroupOperations(remoteRepository)
        if (operationSync !is ApiResponse.Success) {
            return operationSync
        }
        val pendingMessageSync = syncPendingGroupMemos(remoteRepository, groupId = null)
        if (pendingMessageSync !is ApiResponse.Success) {
            return pendingMessageSync
        }
        val groups = when (val directoryRefresh = refreshGroupDirectory(remoteRepository, accountKey)) {
            is ApiResponse.Success -> directoryRefresh.data
            is ApiResponse.Failure.Error -> {
                return ApiResponse.exception(
                    IllegalStateException("Group cache refresh failed: HTTP ${directoryRefresh.statusCode}")
                )
            }
            is ApiResponse.Failure.Exception -> {
                return ApiResponse.exception(
                    IllegalStateException(
                        directoryRefresh.throwable.message ?: "Group cache refresh failed",
                        directoryRefresh.throwable
                    )
                )
            }
        }
        for (group in groups) {
            val refreshed = refreshGroupCache(remoteRepository, accountKey, group.id)
            if (refreshed !is ApiResponse.Success) {
                return refreshed
            }
        }
        return ApiResponse.Success(Unit)
    }

    private suspend fun refreshGroupCache(
        remoteRepository: RemoteRepository,
        accountKey: String,
        groupId: String
    ): ApiResponse<Unit> {
        val normalizedGroupId = groupId.trim()
        if (normalizedGroupId.isEmpty()) {
            return ApiResponse.Success(Unit)
        }

        val allMessages = mutableListOf<site.lcyk.keer.data.model.Memo>()
        var pageToken: String? = null
        do {
            when (
                val response = remoteRepository.listGroupMessages(
                    groupId = normalizedGroupId,
                    pageSize = 100,
                    pageToken = pageToken
                )
            ) {
                is ApiResponse.Success -> {
                    allMessages += response.data.first
                    pageToken = response.data.second
                }
                is ApiResponse.Failure.Error -> {
                    return ApiResponse.exception(
                        IllegalStateException("Group message refresh failed: HTTP ${response.statusCode}")
                    )
                }
                is ApiResponse.Failure.Exception -> {
                    return ApiResponse.exception(
                        IllegalStateException(
                            response.throwable.message ?: "Group message refresh failed",
                            response.throwable
                        )
                    )
                }
            }
        } while (!pageToken.isNullOrBlank())

        offlineGroupStore.replaceCachedGroupMemos(
            accountKey = accountKey,
            groupId = normalizedGroupId,
            memos = allMessages.map { memo -> memo.toCachedMemoItem(groupId = normalizedGroupId) }
        )
        val cachedTags = offlineGroupStore.getCachedGroupTags(accountKey, normalizedGroupId)
        val pendingTags = offlineGroupStore.getPendingGroupOperations(accountKey)
            .asSequence()
            .filter { operation ->
                operation.type == PendingGroupOperationType.ADD_TAG &&
                    operation.groupId == normalizedGroupId
            }
            .mapNotNull { operation ->
                operation.tag?.trim()?.takeIf { tag -> tag.isNotEmpty() }
            }
            .toList()
        val messageTags = allMessages.flatMap { memo -> memo.tags }
        offlineGroupStore.upsertCachedGroupTags(
            accountKey,
            normalizedGroupId,
            normalizeTags(cachedTags + messageTags + pendingTags)
        )

        return ApiResponse.Success(Unit)
    }

    private suspend fun refreshGroupDirectory(
        remoteRepository: RemoteRepository,
        accountKey: String
    ): ApiResponse<List<MemoGroup>> {
        return when (val groupsResponse = remoteRepository.listGroups()) {
            is ApiResponse.Success -> {
                offlineGroupStore.replaceGroups(accountKey, groupsResponse.data)
                ApiResponse.Success(groupsResponse.data)
            }
            is ApiResponse.Failure.Error -> {
                ApiResponse.exception(
                    IllegalStateException("Group cache refresh failed: HTTP ${groupsResponse.statusCode}")
                )
            }
            is ApiResponse.Failure.Exception -> {
                ApiResponse.exception(
                    IllegalStateException(
                        groupsResponse.throwable.message ?: "Group cache refresh failed",
                        groupsResponse.throwable
                    )
                )
            }
        }
    }

    private suspend fun upsertGroupLocal(accountKey: String, group: MemoGroup) {
        offlineGroupStore.upsertGroup(accountKey, group)
    }

    private suspend fun upsertCachedGroupTags(accountKey: String, groupId: String, tags: List<String>) {
        offlineGroupStore.upsertCachedGroupTags(accountKey, groupId, tags)
    }

    private suspend fun removePendingOperation(accountKey: String, operationId: String) {
        offlineGroupStore.removePendingGroupOperation(accountKey, operationId)
    }

    private suspend fun removePendingMemoAndMigratePin(
        accountKey: String,
        groupId: String,
        localId: String,
        remoteId: String
    ) {
        val localRemoteId = localMemoRemoteId(localId)
        val pinnedKeys = offlineGroupStore.getPinnedGroupMemoKeys(accountKey)
        offlineGroupStore.removePendingGroupMemo(accountKey, groupId, localId)
        if (groupMemoKey(groupId, localRemoteId) in pinnedKeys) {
            offlineGroupStore.setPinnedGroupMemo(accountKey, groupId, localRemoteId, pinned = false)
            offlineGroupStore.setPinnedGroupMemo(accountKey, groupId, remoteId, pinned = true)
        }
    }

    private suspend fun readCurrentAccountKey(): String? {
        return accountService.currentAccount.first()?.accountKey()?.takeIf { accountKey ->
            accountKey.isNotBlank()
        }
    }

    private fun groupMemoKey(groupId: String, memoRemoteId: String): String {
        return "$groupId|$memoRemoteId"
    }

    private fun localMemoRemoteId(localId: String): String {
        return "local:$localId"
    }

    private fun normalizeTags(rawTags: Collection<String>): List<String> {
        val normalized = linkedSetOf<String>()
        rawTags.forEach { rawTag ->
            val trimmed = rawTag.trim()
            if (trimmed.isNotEmpty()) {
                normalized += trimmed
            }
        }
        return normalized.toList()
    }
}
