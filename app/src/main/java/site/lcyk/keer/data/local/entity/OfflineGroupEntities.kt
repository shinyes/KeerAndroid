package site.lcyk.keer.data.local.entity

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "offline_groups",
    primaryKeys = ["accountKey", "groupId"],
    indices = [
        Index(value = ["accountKey"]),
        Index(value = ["accountKey", "createdAtEpochMillis"]),
        Index(value = ["accountKey", "updatedAtEpochMillis"]),
    ]
)
data class OfflineGroupEntity(
    val accountKey: String,
    val groupId: String,
    val name: String,
    val description: String,
    val creatorId: String,
    val creatorName: String,
    val groupType: String,
    val hasUnreadDirectMessages: Boolean,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)

@Entity(
    tableName = "offline_group_members",
    primaryKeys = ["accountKey", "groupId", "userId"],
    indices = [
        Index(value = ["accountKey", "groupId"]),
    ]
)
data class OfflineGroupMemberEntity(
    val accountKey: String,
    val groupId: String,
    val userId: String,
    val userName: String,
)

@Entity(
    tableName = "offline_group_aliases",
    primaryKeys = ["accountKey", "localId", "remoteId"],
    indices = [
        Index(value = ["accountKey"]),
        Index(value = ["accountKey", "updatedAtEpochMillis"]),
    ]
)
data class OfflineGroupAliasEntity(
    val accountKey: String,
    val localId: String,
    val remoteId: String,
    val updatedAtEpochMillis: Long,
)

@Entity(
    tableName = "offline_pending_group_operations",
    primaryKeys = ["accountKey", "operationId"],
    indices = [
        Index(value = ["accountKey", "createdAtEpochMillis"]),
        Index(value = ["accountKey", "groupId"]),
    ]
)
data class OfflinePendingGroupOperationEntity(
    val accountKey: String,
    val operationId: String,
    val type: String,
    val groupId: String,
    val name: String?,
    val description: String?,
    val tag: String?,
    val createdAtEpochMillis: Long,
)

@Entity(
    tableName = "offline_pending_group_memos",
    primaryKeys = ["accountKey", "groupId", "localId"],
    indices = [
        Index(value = ["accountKey", "createdAtEpochMillis"]),
        Index(value = ["accountKey", "groupId"]),
    ]
)
data class OfflinePendingGroupMemoEntity(
    val accountKey: String,
    val localId: String,
    val groupId: String,
    val content: String,
    val tagsJson: String,
    val creatorId: String,
    val creatorName: String,
    val creatorAvatarUrl: String?,
    val createdAtEpochMillis: Long,
    val resourceIdsJson: String,
)

@Entity(
    tableName = "offline_cached_group_memos",
    primaryKeys = ["accountKey", "groupId", "remoteId"],
    indices = [
        Index(value = ["accountKey", "groupId"]),
        Index(value = ["accountKey", "updatedAtEpochMillis"]),
    ]
)
data class OfflineCachedGroupMemoEntity(
    val accountKey: String,
    val groupId: String,
    val remoteId: String,
    val payloadJson: String,
    val updatedAtEpochMillis: Long,
)

@Entity(
    tableName = "offline_cached_group_tags",
    primaryKeys = ["accountKey", "groupId"],
    indices = [
        Index(value = ["accountKey", "updatedAtEpochMillis"]),
    ]
)
data class OfflineCachedGroupTagEntity(
    val accountKey: String,
    val groupId: String,
    val tagsJson: String,
    val updatedAtEpochMillis: Long,
)

@Entity(
    tableName = "offline_pinned_group_memos",
    primaryKeys = ["accountKey", "groupId", "memoRemoteId"],
    indices = [
        Index(value = ["accountKey", "groupId"]),
    ]
)
data class OfflinePinnedGroupMemoEntity(
    val accountKey: String,
    val groupId: String,
    val memoRemoteId: String,
)
