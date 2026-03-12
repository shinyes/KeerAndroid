package site.lcyk.keer.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import site.lcyk.keer.data.local.entity.OfflineCachedGroupMemoEntity
import site.lcyk.keer.data.local.entity.OfflineCachedGroupTagEntity
import site.lcyk.keer.data.local.entity.OfflineGroupAliasEntity
import site.lcyk.keer.data.local.entity.OfflineGroupEntity
import site.lcyk.keer.data.local.entity.OfflineGroupMemberEntity
import site.lcyk.keer.data.local.entity.OfflinePendingGroupMemoEntity
import site.lcyk.keer.data.local.entity.OfflinePendingGroupOperationEntity
import site.lcyk.keer.data.local.entity.OfflinePinnedGroupMemoEntity

@Dao
interface OfflineGroupDao {
    @Query(
        """
        SELECT *
        FROM offline_groups
        WHERE accountKey = :accountKey
        ORDER BY updatedAtEpochMillis DESC, createdAtEpochMillis DESC
        """
    )
    fun observeGroups(accountKey: String): Flow<List<OfflineGroupEntity>>

    @Query(
        """
        SELECT *
        FROM offline_groups
        WHERE accountKey = :accountKey
        ORDER BY updatedAtEpochMillis DESC, createdAtEpochMillis DESC
        """
    )
    suspend fun getGroups(accountKey: String): List<OfflineGroupEntity>

    @Upsert
    suspend fun upsertGroups(groups: List<OfflineGroupEntity>)

    @Upsert
    suspend fun upsertGroup(group: OfflineGroupEntity)

    @Query(
        """
        UPDATE offline_groups
        SET hasUnreadMessages = 0
        WHERE accountKey = :accountKey AND groupId = :groupId
        """
    )
    suspend fun markGroupRead(accountKey: String, groupId: String)

    @Query("DELETE FROM offline_groups WHERE accountKey = :accountKey")
    suspend fun deleteGroupsByAccount(accountKey: String)

    @Query("DELETE FROM offline_groups WHERE accountKey = :accountKey AND groupId IN (:groupIds)")
    suspend fun deleteGroupsByIds(accountKey: String, groupIds: List<String>)

    @Query("SELECT * FROM offline_group_members WHERE accountKey = :accountKey")
    suspend fun getGroupMembers(accountKey: String): List<OfflineGroupMemberEntity>

    @Query("SELECT * FROM offline_group_members WHERE accountKey = :accountKey")
    fun observeGroupMembers(accountKey: String): Flow<List<OfflineGroupMemberEntity>>

    @Upsert
    suspend fun upsertGroupMembers(members: List<OfflineGroupMemberEntity>)

    @Query("DELETE FROM offline_group_members WHERE accountKey = :accountKey")
    suspend fun deleteGroupMembersByAccount(accountKey: String)

    @Query("DELETE FROM offline_group_members WHERE accountKey = :accountKey AND groupId = :groupId")
    suspend fun deleteGroupMembersByGroup(accountKey: String, groupId: String)

    @Query("DELETE FROM offline_group_members WHERE accountKey = :accountKey AND groupId IN (:groupIds)")
    suspend fun deleteGroupMembersByGroups(accountKey: String, groupIds: List<String>)

    @Query("SELECT * FROM offline_group_aliases WHERE accountKey = :accountKey ORDER BY updatedAtEpochMillis DESC")
    fun observeGroupAliases(accountKey: String): Flow<List<OfflineGroupAliasEntity>>

    @Query("SELECT * FROM offline_group_aliases WHERE accountKey = :accountKey ORDER BY updatedAtEpochMillis DESC")
    suspend fun getGroupAliases(accountKey: String): List<OfflineGroupAliasEntity>

    @Upsert
    suspend fun upsertGroupAliases(aliases: List<OfflineGroupAliasEntity>)

    @Query("DELETE FROM offline_group_aliases WHERE accountKey = :accountKey")
    suspend fun deleteGroupAliasesByAccount(accountKey: String)

    @Query("DELETE FROM offline_group_aliases WHERE accountKey = :accountKey AND (localId IN (:groupIds) OR remoteId IN (:groupIds))")
    suspend fun deleteGroupAliasesByGroupIds(accountKey: String, groupIds: List<String>)

    @Query("SELECT * FROM offline_pending_group_operations WHERE accountKey = :accountKey ORDER BY createdAtEpochMillis ASC")
    suspend fun getPendingGroupOperations(accountKey: String): List<OfflinePendingGroupOperationEntity>

    @Upsert
    suspend fun upsertPendingGroupOperation(operation: OfflinePendingGroupOperationEntity)

    @Query("DELETE FROM offline_pending_group_operations WHERE accountKey = :accountKey AND operationId = :operationId")
    suspend fun deletePendingGroupOperation(accountKey: String, operationId: String)

    @Query("DELETE FROM offline_pending_group_operations WHERE accountKey = :accountKey")
    suspend fun deletePendingGroupOperationsByAccount(accountKey: String)

    @Query("SELECT * FROM offline_pending_group_memos WHERE accountKey = :accountKey ORDER BY createdAtEpochMillis ASC")
    suspend fun getPendingGroupMemos(accountKey: String): List<OfflinePendingGroupMemoEntity>

    @Upsert
    suspend fun upsertPendingGroupMemo(memo: OfflinePendingGroupMemoEntity)

    @Query("DELETE FROM offline_pending_group_memos WHERE accountKey = :accountKey AND groupId = :groupId AND localId = :localId")
    suspend fun deletePendingGroupMemo(accountKey: String, groupId: String, localId: String)

    @Query("DELETE FROM offline_pending_group_memos WHERE accountKey = :accountKey")
    suspend fun deletePendingGroupMemosByAccount(accountKey: String)

    @Query("SELECT * FROM offline_pinned_group_memos WHERE accountKey = :accountKey")
    suspend fun getPinnedGroupMemos(accountKey: String): List<OfflinePinnedGroupMemoEntity>

    @Query("SELECT * FROM offline_pinned_group_memos WHERE accountKey = :accountKey")
    fun observePinnedGroupMemos(accountKey: String): Flow<List<OfflinePinnedGroupMemoEntity>>

    @Upsert
    suspend fun upsertPinnedGroupMemo(memo: OfflinePinnedGroupMemoEntity)

    @Query("DELETE FROM offline_pinned_group_memos WHERE accountKey = :accountKey AND groupId = :groupId AND memoRemoteId = :memoRemoteId")
    suspend fun deletePinnedGroupMemo(accountKey: String, groupId: String, memoRemoteId: String)

    @Query("DELETE FROM offline_pinned_group_memos WHERE accountKey = :accountKey")
    suspend fun deletePinnedGroupMemosByAccount(accountKey: String)

    @Query("DELETE FROM offline_pinned_group_memos WHERE accountKey = :accountKey AND groupId IN (:groupIds)")
    suspend fun deletePinnedGroupMemosByGroups(accountKey: String, groupIds: List<String>)

    @Query("SELECT * FROM offline_cached_group_memos WHERE accountKey = :accountKey AND groupId = :groupId ORDER BY updatedAtEpochMillis DESC")
    suspend fun getCachedGroupMemos(accountKey: String, groupId: String): List<OfflineCachedGroupMemoEntity>

    @Query("SELECT * FROM offline_cached_group_memos WHERE accountKey = :accountKey ORDER BY updatedAtEpochMillis DESC")
    fun observeCachedGroupMemos(accountKey: String): Flow<List<OfflineCachedGroupMemoEntity>>

    @Upsert
    suspend fun upsertCachedGroupMemos(memos: List<OfflineCachedGroupMemoEntity>)

    @Query("DELETE FROM offline_cached_group_memos WHERE accountKey = :accountKey AND groupId = :groupId")
    suspend fun deleteCachedGroupMemosByGroup(accountKey: String, groupId: String)

    @Query("DELETE FROM offline_cached_group_memos WHERE accountKey = :accountKey AND groupId = :groupId AND remoteId = :remoteId")
    suspend fun deleteCachedGroupMemo(accountKey: String, groupId: String, remoteId: String)

    @Query("DELETE FROM offline_cached_group_memos WHERE accountKey = :accountKey")
    suspend fun deleteCachedGroupMemosByAccount(accountKey: String)

    @Query("DELETE FROM offline_cached_group_memos WHERE accountKey = :accountKey AND groupId IN (:groupIds)")
    suspend fun deleteCachedGroupMemosByGroups(accountKey: String, groupIds: List<String>)

    @Query("SELECT * FROM offline_cached_group_tags WHERE accountKey = :accountKey")
    suspend fun getCachedGroupTags(accountKey: String): List<OfflineCachedGroupTagEntity>

    @Upsert
    suspend fun upsertCachedGroupTag(tagSet: OfflineCachedGroupTagEntity)

    @Query("DELETE FROM offline_cached_group_tags WHERE accountKey = :accountKey")
    suspend fun deleteCachedGroupTagsByAccount(accountKey: String)

    @Query("DELETE FROM offline_cached_group_tags WHERE accountKey = :accountKey AND groupId IN (:groupIds)")
    suspend fun deleteCachedGroupTagsByGroups(accountKey: String, groupIds: List<String>)
}
