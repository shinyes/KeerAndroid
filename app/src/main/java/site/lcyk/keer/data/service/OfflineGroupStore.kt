package site.lcyk.keer.data.service

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import site.lcyk.keer.data.local.KeerDatabase
import site.lcyk.keer.data.local.entity.OfflineCachedGroupMemoEntity
import site.lcyk.keer.data.local.entity.OfflineCachedGroupTagEntity
import site.lcyk.keer.data.local.entity.OfflineGroupAliasEntity
import site.lcyk.keer.data.local.entity.OfflineGroupEntity
import site.lcyk.keer.data.local.entity.OfflineGroupMemberEntity
import site.lcyk.keer.data.local.entity.OfflinePendingGroupMemoEntity
import site.lcyk.keer.data.local.entity.OfflinePendingGroupOperationEntity
import site.lcyk.keer.data.local.entity.OfflinePinnedGroupMemoEntity
import site.lcyk.keer.data.model.CachedGroupTagSet
import site.lcyk.keer.data.model.CachedMemoItem
import site.lcyk.keer.data.model.GroupIdAlias
import site.lcyk.keer.data.model.GroupMember
import site.lcyk.keer.data.model.MemoGroup
import site.lcyk.keer.data.model.PendingGroupMemo
import site.lcyk.keer.data.model.PendingGroupOperation
import site.lcyk.keer.data.model.PendingGroupOperationType
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OfflineGroupStore @Inject constructor(
    private val database: KeerDatabase,
) {
    private val dao = database.offlineGroupDao()
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
    }
    private val stringListSerializer = ListSerializer(String.serializer())

    fun observeGroups(accountKey: String): Flow<List<MemoGroup>> {
        val normalizedAccountKey = accountKey.trim()
        return combine(
            dao.observeGroups(normalizedAccountKey),
            dao.observeGroupMembers(normalizedAccountKey),
        ) { groups, members ->
            val membersByGroupId = members.groupBy(OfflineGroupMemberEntity::groupId)
            groups.map { group -> group.toModel(membersByGroupId) }
        }
    }

    fun observeGroupAliases(accountKey: String): Flow<List<GroupIdAlias>> {
        val normalizedAccountKey = accountKey.trim()
        return dao.observeGroupAliases(normalizedAccountKey).map { aliases ->
            aliases.map { alias -> alias.toAliasModel() }
        }
    }

    fun observeAllCachedGroupMemos(accountKey: String): Flow<List<Pair<CachedMemoItem, String>>> {
        val normalizedAccountKey = accountKey.trim()
        return dao.observeCachedGroupMemos(normalizedAccountKey).map { entities ->
            entities.mapNotNull { entity ->
                decodeCachedMemo(entity.payloadJson)?.let { memo ->
                    memo to entity.groupId
                }
            }
        }
    }

    fun observePinnedGroupMemoKeys(accountKey: String): Flow<Set<String>> {
        val normalizedAccountKey = accountKey.trim()
        return dao.observePinnedGroupMemos(normalizedAccountKey).map { entities ->
            entities.map { entity -> groupMemoKey(entity.groupId, entity.memoRemoteId) }.toSet()
        }
    }

    suspend fun getGroups(accountKey: String): List<MemoGroup> {
        val normalizedAccountKey = accountKey.trim()
        val groups = dao.getGroups(normalizedAccountKey)
        val membersByGroupId = dao.getGroupMembers(normalizedAccountKey)
            .groupBy(OfflineGroupMemberEntity::groupId)
        return groups.map { group -> group.toModel(membersByGroupId) }
    }

    suspend fun replaceGroups(accountKey: String, groups: List<MemoGroup>) {
        val normalizedAccountKey = accountKey.trim()
        database.withTransaction {
            dao.deleteGroupsByAccount(normalizedAccountKey)
            dao.deleteGroupMembersByAccount(normalizedAccountKey)
            if (groups.isNotEmpty()) {
                dao.upsertGroups(groups.map { group -> group.toEntity(normalizedAccountKey) })
                dao.upsertGroupMembers(
                    groups.flatMap { group ->
                        group.members.map { member -> member.toEntity(normalizedAccountKey, group.id) }
                    }
                )
            }
        }
    }

    suspend fun upsertGroup(accountKey: String, group: MemoGroup) {
        val normalizedAccountKey = accountKey.trim()
        database.withTransaction {
            dao.upsertGroup(group.toEntity(normalizedAccountKey))
            dao.deleteGroupMembersByGroup(normalizedAccountKey, group.id)
            if (group.members.isNotEmpty()) {
                dao.upsertGroupMembers(
                    group.members.map { member -> member.toEntity(normalizedAccountKey, group.id) }
                )
            }
        }
    }

    suspend fun getGroupAliases(accountKey: String): List<GroupIdAlias> {
        return dao.getGroupAliases(accountKey.trim()).map { alias -> alias.toAliasModel() }
    }

    suspend fun getPendingGroupOperations(accountKey: String): List<PendingGroupOperation> {
        return dao.getPendingGroupOperations(accountKey.trim()).map { operation -> operation.toPendingOperationModel() }
    }

    suspend fun enqueuePendingGroupOperation(accountKey: String, operation: PendingGroupOperation) {
        dao.upsertPendingGroupOperation(operation.toEntity(accountKey.trim()))
    }

    suspend fun removePendingGroupOperation(accountKey: String, operationId: String) {
        dao.deletePendingGroupOperation(accountKey.trim(), operationId.trim())
    }

    suspend fun getPendingGroupMemos(accountKey: String, groupId: String? = null): List<PendingGroupMemo> {
        val normalizedGroupId = groupId?.trim().orEmpty()
        return dao.getPendingGroupMemos(accountKey.trim())
            .map { memo -> memo.toPendingMemoModel() }
            .filter { memo ->
                normalizedGroupId.isEmpty() || memo.groupId == normalizedGroupId
            }
    }

    suspend fun upsertPendingGroupMemo(accountKey: String, memo: PendingGroupMemo) {
        dao.upsertPendingGroupMemo(memo.toEntity(accountKey.trim(), json, stringListSerializer))
    }

    suspend fun removePendingGroupMemo(accountKey: String, groupId: String, localId: String) {
        dao.deletePendingGroupMemo(accountKey.trim(), groupId.trim(), localId.trim())
    }

    suspend fun setPinnedGroupMemo(accountKey: String, groupId: String, memoRemoteId: String, pinned: Boolean) {
        val normalizedAccountKey = accountKey.trim()
        val normalizedGroupId = groupId.trim()
        val normalizedMemoRemoteId = memoRemoteId.trim()
        if (normalizedGroupId.isEmpty() || normalizedMemoRemoteId.isEmpty()) {
            return
        }
        if (pinned) {
            dao.upsertPinnedGroupMemo(
                OfflinePinnedGroupMemoEntity(
                    accountKey = normalizedAccountKey,
                    groupId = normalizedGroupId,
                    memoRemoteId = normalizedMemoRemoteId,
                )
            )
        } else {
            dao.deletePinnedGroupMemo(normalizedAccountKey, normalizedGroupId, normalizedMemoRemoteId)
        }
    }

    suspend fun getPinnedGroupMemoKeys(accountKey: String): Set<String> {
        return dao.getPinnedGroupMemos(accountKey.trim())
            .map { entity -> groupMemoKey(entity.groupId, entity.memoRemoteId) }
            .toSet()
    }

    suspend fun getCachedGroupMemos(accountKey: String, groupId: String): List<CachedMemoItem> {
        return dao.getCachedGroupMemos(accountKey.trim(), groupId.trim())
            .mapNotNull { entity -> decodeCachedMemo(entity.payloadJson) }
    }

    suspend fun replaceCachedGroupMemos(accountKey: String, groupId: String, memos: List<CachedMemoItem>) {
        val normalizedAccountKey = accountKey.trim()
        val normalizedGroupId = groupId.trim()
        database.withTransaction {
            dao.deleteCachedGroupMemosByGroup(normalizedAccountKey, normalizedGroupId)
            if (memos.isNotEmpty()) {
                dao.upsertCachedGroupMemos(
                    memos.map { memo ->
                        OfflineCachedGroupMemoEntity(
                            accountKey = normalizedAccountKey,
                            groupId = normalizedGroupId,
                            remoteId = memo.remoteId,
                            payloadJson = json.encodeToString(CachedMemoItem.serializer(), memo),
                            updatedAtEpochMillis = memo.updatedAtEpochMillis ?: memo.dateEpochMillis,
                        )
                    }
                )
            }
        }
    }

    suspend fun upsertCachedGroupMemo(accountKey: String, groupId: String, memo: CachedMemoItem) {
        dao.upsertCachedGroupMemos(
            listOf(
                OfflineCachedGroupMemoEntity(
                    accountKey = accountKey.trim(),
                    groupId = groupId.trim(),
                    remoteId = memo.remoteId,
                    payloadJson = json.encodeToString(CachedMemoItem.serializer(), memo),
                    updatedAtEpochMillis = memo.updatedAtEpochMillis ?: memo.dateEpochMillis,
                )
            )
        )
    }

    suspend fun removeCachedGroupMemo(accountKey: String, groupId: String, remoteId: String) {
        dao.deleteCachedGroupMemo(accountKey.trim(), groupId.trim(), remoteId.trim())
    }

    suspend fun getCachedGroupTags(accountKey: String, groupId: String): List<String> {
        return dao.getCachedGroupTags(accountKey.trim())
            .firstOrNull { tagSet -> tagSet.groupId == groupId.trim() }
            ?.toModel(json)
            ?.tags
            .orEmpty()
    }

    suspend fun upsertCachedGroupTags(accountKey: String, groupId: String, tags: List<String>) {
        dao.upsertCachedGroupTag(
            OfflineCachedGroupTagEntity(
                accountKey = accountKey.trim(),
                groupId = groupId.trim(),
                tagsJson = json.encodeToString(
                    CachedGroupTagSet.serializer(),
                    CachedGroupTagSet(groupId = groupId.trim(), tags = tags)
                ),
                updatedAtEpochMillis = System.currentTimeMillis(),
            )
        )
    }

    suspend fun replaceLocalGroupId(accountKey: String, localGroupId: String, remoteGroup: MemoGroup) {
        val normalizedAccountKey = accountKey.trim()
        val normalizedLocalGroupId = localGroupId.trim()
        if (normalizedAccountKey.isEmpty() || normalizedLocalGroupId.isEmpty()) {
            return
        }

        database.withTransaction {
            val groups = mergeGroup(
                getGroups(normalizedAccountKey).filterNot { group -> group.id == normalizedLocalGroupId },
                remoteGroup,
            )
            val pendingMemos = getPendingGroupMemos(normalizedAccountKey).map { memo ->
                if (memo.groupId == normalizedLocalGroupId) memo.copy(groupId = remoteGroup.id) else memo
            }
            val pendingOperations = getPendingGroupOperations(normalizedAccountKey).map { operation ->
                if (operation.groupId == normalizedLocalGroupId) operation.copy(groupId = remoteGroup.id) else operation
            }
            val pinned = dao.getPinnedGroupMemos(normalizedAccountKey).map { entity ->
                if (entity.groupId == normalizedLocalGroupId) entity.copy(groupId = remoteGroup.id) else entity
            }.distinctBy { entity -> Triple(entity.accountKey, entity.groupId, entity.memoRemoteId) }
            val cachedGroupMemos = collectAllCachedGroupMemoEntities(normalizedAccountKey).map { entity ->
                if (entity.groupId == normalizedLocalGroupId) entity.copy(groupId = remoteGroup.id) else entity
            }.distinctBy { entity -> Triple(entity.accountKey, entity.groupId, entity.remoteId) }
            val cachedGroupTags = dao.getCachedGroupTags(normalizedAccountKey).map { entity ->
                if (entity.groupId == normalizedLocalGroupId) {
                    val decoded = entity.toModel(json)
                    entity.copy(
                        groupId = remoteGroup.id,
                        tagsJson = json.encodeToString(
                            CachedGroupTagSet.serializer(),
                            decoded.copy(groupId = remoteGroup.id),
                        ),
                    )
                } else {
                    entity
                }
            }
            val aliases = (getGroupAliases(normalizedAccountKey)
                .filterNot { alias ->
                    alias.localId == normalizedLocalGroupId || alias.remoteId == remoteGroup.id
                } + GroupIdAlias(
                localId = normalizedLocalGroupId,
                remoteId = remoteGroup.id,
            )).distinctBy { alias -> alias.localId to alias.remoteId }

            dao.deleteGroupsByAccount(normalizedAccountKey)
            dao.deleteGroupMembersByAccount(normalizedAccountKey)
            dao.deletePendingGroupMemosByAccount(normalizedAccountKey)
            dao.deletePendingGroupOperationsByAccount(normalizedAccountKey)
            dao.deletePinnedGroupMemosByAccount(normalizedAccountKey)
            dao.deleteCachedGroupMemosByAccount(normalizedAccountKey)
            dao.deleteCachedGroupTagsByAccount(normalizedAccountKey)
            dao.deleteGroupAliasesByAccount(normalizedAccountKey)

            if (groups.isNotEmpty()) {
                dao.upsertGroups(groups.map { group -> group.toEntity(normalizedAccountKey) })
                dao.upsertGroupMembers(
                    groups.flatMap { group ->
                        group.members.map { member -> member.toEntity(normalizedAccountKey, group.id) }
                    }
                )
            }
            pendingMemos.forEach { memo -> dao.upsertPendingGroupMemo(memo.toEntity(normalizedAccountKey, json, stringListSerializer)) }
            pendingOperations.forEach { operation -> dao.upsertPendingGroupOperation(operation.toEntity(normalizedAccountKey)) }
            pinned.forEach { entity -> dao.upsertPinnedGroupMemo(entity) }
            if (cachedGroupMemos.isNotEmpty()) {
                dao.upsertCachedGroupMemos(cachedGroupMemos)
            }
            cachedGroupTags.forEach { entity -> dao.upsertCachedGroupTag(entity) }
            if (aliases.isNotEmpty()) {
                dao.upsertGroupAliases(aliases.map { alias -> alias.toEntity(normalizedAccountKey) })
            }
        }
    }

    suspend fun removeGroupReferences(accountKey: String, groupId: String) {
        val normalizedAccountKey = accountKey.trim()
        val linkedIds = linkedGroupIds(normalizedAccountKey, groupId.trim())
        if (linkedIds.isEmpty()) {
            return
        }

        database.withTransaction {
            dao.deleteGroupsByIds(normalizedAccountKey, linkedIds)
            dao.deleteGroupMembersByGroups(normalizedAccountKey, linkedIds)
            dao.deleteCachedGroupMemosByGroups(normalizedAccountKey, linkedIds)
            dao.deleteCachedGroupTagsByGroups(normalizedAccountKey, linkedIds)
            dao.deletePinnedGroupMemosByGroups(normalizedAccountKey, linkedIds)
            dao.deleteGroupAliasesByGroupIds(normalizedAccountKey, linkedIds)

            val pendingMemos = getPendingGroupMemos(normalizedAccountKey)
                .filterNot { memo -> memo.groupId in linkedIds }
            val pendingOperations = getPendingGroupOperations(normalizedAccountKey)
                .filterNot { operation -> operation.groupId in linkedIds }

            dao.deletePendingGroupMemosByAccount(normalizedAccountKey)
            dao.deletePendingGroupOperationsByAccount(normalizedAccountKey)
            pendingMemos.forEach { memo -> dao.upsertPendingGroupMemo(memo.toEntity(normalizedAccountKey, json, stringListSerializer)) }
            pendingOperations.forEach { operation -> dao.upsertPendingGroupOperation(operation.toEntity(normalizedAccountKey)) }
        }
    }

    suspend fun hasPendingWork(accountKey: String): Boolean {
        val normalizedAccountKey = accountKey.trim()
        return dao.getPendingGroupMemos(normalizedAccountKey).isNotEmpty() ||
            dao.getPendingGroupOperations(normalizedAccountKey).isNotEmpty()
    }

    suspend fun purgeAccount(accountKey: String) {
        val normalizedAccountKey = accountKey.trim()
        database.withTransaction {
            dao.deleteGroupsByAccount(normalizedAccountKey)
            dao.deleteGroupMembersByAccount(normalizedAccountKey)
            dao.deleteGroupAliasesByAccount(normalizedAccountKey)
            dao.deletePendingGroupOperationsByAccount(normalizedAccountKey)
            dao.deletePendingGroupMemosByAccount(normalizedAccountKey)
            dao.deletePinnedGroupMemosByAccount(normalizedAccountKey)
            dao.deleteCachedGroupMemosByAccount(normalizedAccountKey)
            dao.deleteCachedGroupTagsByAccount(normalizedAccountKey)
        }
    }

    private suspend fun collectAllCachedGroupMemoEntities(accountKey: String): List<OfflineCachedGroupMemoEntity> {
        return dao.getGroups(accountKey).flatMap { group ->
            dao.getCachedGroupMemos(accountKey, group.groupId)
        }
    }

    private suspend fun linkedGroupIds(accountKey: String, groupId: String): List<String> {
        if (groupId.isBlank()) {
            return emptyList()
        }
        val linked = linkedSetOf(groupId)
        getGroupAliases(accountKey).forEach { alias ->
            if (alias.localId == groupId || alias.remoteId == groupId) {
                linked += alias.localId
                linked += alias.remoteId
            }
        }
        return linked.toList()
    }

    private fun decodeCachedMemo(raw: String): CachedMemoItem? {
        return runCatching {
            json.decodeFromString(CachedMemoItem.serializer(), raw)
        }.getOrNull()
    }

    private fun mergeGroup(groups: List<MemoGroup>, target: MemoGroup): List<MemoGroup> {
        val withoutTarget = groups.filterNot { group -> group.id == target.id }
        return (withoutTarget + target).sortedByDescending(MemoGroup::createdAtEpochMillis)
    }

    private fun OfflineGroupEntity.toModel(
        membersByGroupId: Map<String, List<OfflineGroupMemberEntity>>,
    ): MemoGroup {
        return MemoGroup(
            id = groupId,
            name = name,
            description = description,
            creatorId = creatorId,
            creatorName = creatorName,
            members = membersByGroupId[groupId].orEmpty().map { member -> member.toModel() },
            createdAtEpochMillis = createdAtEpochMillis,
        )
    }

    private fun MemoGroup.toEntity(accountKey: String): OfflineGroupEntity {
        return OfflineGroupEntity(
            accountKey = accountKey,
            groupId = id,
            name = name,
            description = description,
            creatorId = creatorId,
            creatorName = creatorName,
            createdAtEpochMillis = createdAtEpochMillis,
        )
    }

    private fun GroupMember.toEntity(accountKey: String, groupId: String): OfflineGroupMemberEntity {
        return OfflineGroupMemberEntity(
            accountKey = accountKey,
            groupId = groupId,
            userId = userId,
            userName = userName,
        )
    }

    private fun OfflineGroupMemberEntity.toModel(): GroupMember {
        return GroupMember(
            userId = userId,
            userName = userName,
        )
    }

    private fun GroupIdAlias.toEntity(accountKey: String): OfflineGroupAliasEntity {
        return OfflineGroupAliasEntity(
            accountKey = accountKey,
            localId = localId,
            remoteId = remoteId,
            updatedAtEpochMillis = updatedAtEpochMillis,
        )
    }

    private fun OfflineGroupAliasEntity.toAliasModel(): GroupIdAlias {
        return GroupIdAlias(
            localId = localId,
            remoteId = remoteId,
            updatedAtEpochMillis = updatedAtEpochMillis,
        )
    }

    private fun PendingGroupOperation.toEntity(accountKey: String): OfflinePendingGroupOperationEntity {
        return OfflinePendingGroupOperationEntity(
            accountKey = accountKey,
            operationId = operationId,
            type = type.name,
            groupId = groupId,
            name = name,
            description = description,
            tag = tag,
            createdAtEpochMillis = createdAtEpochMillis,
        )
    }

    private fun OfflinePendingGroupOperationEntity.toPendingOperationModel(): PendingGroupOperation {
        return PendingGroupOperation(
            operationId = operationId,
            type = PendingGroupOperationType.valueOf(type),
            groupId = groupId,
            name = name,
            description = description,
            tag = tag,
            createdAtEpochMillis = createdAtEpochMillis,
        )
    }

    private fun PendingGroupMemo.toEntity(
        accountKey: String,
        json: Json,
        stringListSerializer: KSerializer<List<String>>,
    ): OfflinePendingGroupMemoEntity {
        return OfflinePendingGroupMemoEntity(
            accountKey = accountKey,
            localId = localId,
            groupId = groupId,
            content = content,
            tagsJson = json.encodeToString(stringListSerializer, tags),
            creatorId = creatorId,
            creatorName = creatorName,
            creatorAvatarUrl = creatorAvatarUrl,
            createdAtEpochMillis = createdAtEpochMillis,
            resourceIdsJson = json.encodeToString(stringListSerializer, resourceIdentifiers),
        )
    }

    private fun OfflinePendingGroupMemoEntity.toPendingMemoModel(): PendingGroupMemo {
        return PendingGroupMemo(
            localId = localId,
            groupId = groupId,
            content = content,
            tags = decodeStringList(tagsJson),
            creatorId = creatorId,
            creatorName = creatorName,
            creatorAvatarUrl = creatorAvatarUrl,
            createdAtEpochMillis = createdAtEpochMillis,
            resourceIdentifiers = decodeStringList(resourceIdsJson),
        )
    }

    private fun OfflineCachedGroupTagEntity.toModel(json: Json): CachedGroupTagSet {
        return runCatching {
            json.decodeFromString(CachedGroupTagSet.serializer(), tagsJson)
        }.getOrElse {
            CachedGroupTagSet(
                groupId = groupId,
                tags = emptyList(),
                updatedAtEpochMillis = updatedAtEpochMillis,
            )
        }
    }

    private fun decodeStringList(raw: String): List<String> {
        return runCatching {
            json.decodeFromString(stringListSerializer, raw)
        }.getOrDefault(emptyList())
    }

    private fun groupMemoKey(groupId: String, memoRemoteId: String): String {
        return "$groupId|$memoRemoteId"
    }
}
