package site.lcyk.keer.data.service

import com.skydoves.sandwich.ApiResponse
import com.skydoves.sandwich.retrofit.statusCode
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import site.lcyk.keer.data.model.PendingGroupMemo
import site.lcyk.keer.data.model.PendingGroupOperationType
import site.lcyk.keer.data.model.toCachedMemoItem
import site.lcyk.keer.data.repository.SyncPullDomain
import site.lcyk.keer.data.repository.SyncPullResult
import site.lcyk.keer.data.repository.RemoteRepository
import site.lcyk.keer.data.repository.ResourceEncryptionScope

@Singleton
class GroupsSyncRunner @Inject constructor(
    private val accountService: AccountService,
    private val accountLocalSettingsStore: AccountLocalSettingsStore,
    private val offlineGroupStore: OfflineGroupStore,
) {
    suspend fun applyStreamChunk(
        accountKey: String,
        chunk: SyncPullResult,
    ) {
        applyGroupSyncPage(
            accountKey = accountKey,
            patches = chunk.patches,
        )
        val nextCursor = chunk.nextCursor.trim()
        if (nextCursor.isNotEmpty()) {
            accountLocalSettingsStore.writeGroupSyncCursor(accountKey, nextCursor)
        }
    }

    suspend fun sync(groupId: String? = null): ApiResponse<Unit> = withContext(Dispatchers.IO) {
        val normalizedGroupId = groupId?.trim().orEmpty()
        val remoteRepository = accountService.getRemoteRepository()
            ?: return@withContext ApiResponse.Success(Unit)
        val pendingOperationSync = syncPendingGroupOperations(remoteRepository)
        if (pendingOperationSync !is ApiResponse.Success) {
            return@withContext pendingOperationSync
        }
        val pendingMessageSync = syncPendingGroupMemos(
            remoteRepository,
            groupId = normalizedGroupId.ifBlank { null }
        )
        if (pendingMessageSync !is ApiResponse.Success) {
            return@withContext pendingMessageSync
        }
        val accountKey = readCurrentAccountKey() ?: return@withContext ApiResponse.Success(Unit)
        return@withContext runPullSync(
            remoteRepository = remoteRepository,
            accountKey = accountKey,
            scopedGroupId = normalizedGroupId.ifBlank { null },
        )
    }

    private suspend fun runPullSync(
        remoteRepository: RemoteRepository,
        accountKey: String,
        scopedGroupId: String?,
    ): ApiResponse<Unit> {
        var cursor = accountLocalSettingsStore.readGroupSyncCursor(accountKey)?.trim().orEmpty()
            .ifBlank { "0" }
        val streamResponse = remoteRepository.streamSyncBootstrap(
            resumeCursor = cursor,
            domains = setOf(SyncPullDomain.GROUPS, SyncPullDomain.GROUP_MESSAGES),
            groupScopes = scopedGroupId?.let { scoped -> listOf("groups/$scoped") }.orEmpty(),
            limit = PULL_SYNC_GROUP_PAGE_SIZE,
        ) { chunk ->
            applyGroupSyncPage(
                accountKey = accountKey,
                patches = chunk.patches,
            )
            val nextCursor = chunk.nextCursor.trim()
            if (nextCursor.isNotEmpty() && nextCursor != cursor) {
                cursor = nextCursor
                accountLocalSettingsStore.writeGroupSyncCursor(accountKey, cursor)
            }
            ApiResponse.Success(Unit)
        }
        return when (streamResponse) {
            is ApiResponse.Success -> ApiResponse.Success(Unit)
            is ApiResponse.Failure.Error -> ApiResponse.exception(
                IllegalStateException("Group cache refresh failed: HTTP ${streamResponse.statusCode.code}")
            )
            is ApiResponse.Failure.Exception -> ApiResponse.exception(
                IllegalStateException(
                    streamResponse.throwable.message ?: "Group cache refresh failed",
                    streamResponse.throwable
                )
            )
        }
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
                            offlineGroupStore.upsertGroup(accountKey, response.data)
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
                            offlineGroupStore.upsertGroup(accountKey, response.data)
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
                    offlineGroupStore.upsertCachedGroupTags(
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
                .sortedBy(PendingGroupMemo::createdAtEpochMillis)
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

            when (
                val response = remoteRepository.createGroupMessage(
                    groupId = pending.groupId,
                    content = pending.content,
                    tags = pending.tags,
                    resourceRemoteIds = resourceRemoteIds
                )
            ) {
                is ApiResponse.Success -> {
                    removePendingMemoAndMigratePin(
                        accountKey = accountKey,
                        groupId = pending.groupId,
                        localId = pending.localId,
                        remoteId = response.data.remoteId
                    )
                    offlineGroupStore.upsertCachedGroupMemo(
                        accountKey,
                        pending.groupId,
                        response.data.toCachedMemoItem(groupId = pending.groupId)
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

    private suspend fun applyGroupSyncPage(
        accountKey: String,
        patches: site.lcyk.keer.data.repository.SyncPullPatches,
    ) {
        if (patches.groups.upserts.isNotEmpty()) {
            patches.groups.upserts.forEach { group ->
                offlineGroupStore.upsertGroup(accountKey, group)
            }
        }
        if (patches.groups.deletes.isNotEmpty()) {
            patches.groups.deletes
                .asSequence()
                .map(String::trim)
                .filter(String::isNotEmpty)
                .distinct()
                .forEach { groupId ->
                    offlineGroupStore.removeGroupReferences(accountKey, groupId)
                }
        }

        for (groupPatch in patches.groupMessages.groups) {
            val groupId = groupPatch.groupId.trim()
            if (groupId.isEmpty()) {
                continue
            }

            val upserts = groupPatch.upserts
            if (upserts.isNotEmpty()) {
                upserts.forEach { memo ->
                    offlineGroupStore.upsertCachedGroupMemo(
                        accountKey,
                        groupId,
                        memo.toCachedMemoItem(groupId = groupId)
                    )
                }
            }

            groupPatch.deletes
                .asSequence()
                .map(String::trim)
                .filter(String::isNotEmpty)
                .distinct()
                .forEach { memoRemoteId ->
                    offlineGroupStore.removeCachedGroupMemo(accountKey, groupId, memoRemoteId)
                    offlineGroupStore.setPinnedGroupMemo(
                        accountKey = accountKey,
                        groupId = groupId,
                        memoRemoteId = memoRemoteId,
                        pinned = false,
                    )
                }

            val pendingTagOps = offlineGroupStore.getPendingGroupOperations(accountKey)
                .asSequence()
                .filter { operation ->
                    operation.type == PendingGroupOperationType.ADD_TAG &&
                        operation.groupId == groupId
                }
                .mapNotNull { operation ->
                    operation.tag?.trim()?.takeIf(String::isNotEmpty)
                }
                .toList()

            val cachedTags = offlineGroupStore.getCachedGroupTags(accountKey, groupId)
            val upsertTags = upserts.flatMap { memo -> memo.tags }
            val mergedTags = normalizeTags(cachedTags + groupPatch.tags + upsertTags + pendingTagOps)
            offlineGroupStore.upsertCachedGroupTags(
                accountKey = accountKey,
                groupId = groupId,
                tags = mergedTags,
            )
            offlineGroupStore.setGroupUnreadState(
                accountKey = accountKey,
                groupId = groupId,
                hasUnread = groupPatch.hasUnread,
            )
        }
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
        return accountService.currentAccount.first()?.accountKey()?.takeIf(String::isNotBlank)
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

    private companion object {
        private const val PULL_SYNC_GROUP_PAGE_SIZE = 120
    }
}
