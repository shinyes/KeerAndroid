package site.lcyk.keer.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import site.lcyk.keer.data.local.entity.MemoTagCrossRef
import site.lcyk.keer.data.local.entity.MemoEntity
import site.lcyk.keer.data.local.entity.MemoWithResources
import site.lcyk.keer.data.local.entity.ResourceEntity
import site.lcyk.keer.data.local.entity.TagEntity
import java.time.Instant

@Dao
interface MemoDao {
    @Query("SELECT * FROM memos WHERE accountKey = :accountKey AND archived = 1 ORDER BY date DESC")
    suspend fun getArchivedMemos(accountKey: String): List<MemoEntity>

    @Transaction
    @Query("SELECT * FROM memos WHERE accountKey = :accountKey AND archived = 1 ORDER BY date DESC")
    suspend fun getArchivedMemoItems(accountKey: String): List<MemoWithResources>

    @Query("""
        SELECT * FROM memos 
        WHERE accountKey = :accountKey AND archived = 0 AND isDeleted = 0
        ORDER BY pinned DESC, date DESC
    """)
    suspend fun getAllMemos(accountKey: String): List<MemoEntity>

    @Transaction
    @Query("""
        SELECT * FROM memos 
        WHERE accountKey = :accountKey AND archived = 0 AND isDeleted = 0
        ORDER BY pinned DESC, date DESC
    """)
    suspend fun getAllMemoItems(accountKey: String): List<MemoWithResources>

    @Transaction
    @Query("""
        SELECT * FROM memos
        WHERE accountKey = :accountKey AND archived = 0 AND isDeleted = 0
        ORDER BY pinned DESC, date DESC
    """)
    fun observeAllMemos(accountKey: String): Flow<List<MemoWithResources>>

    @Query("SELECT * FROM memos WHERE accountKey = :accountKey")
    suspend fun getAllMemosForSync(accountKey: String): List<MemoEntity>

    @Query("""
        SELECT * FROM memos 
        WHERE accountKey = :accountKey 
        AND (needsSync = 1 OR isDeleted = 1 OR lastModified > :since)
        ORDER BY lastModified ASC
    """)
    suspend fun getMemosForIncrementalSync(accountKey: String, since: Instant): List<MemoEntity>

    @Query("""
        SELECT * FROM memos 
        WHERE accountKey = :accountKey 
        AND needsSync = 1
        ORDER BY lastModified ASC
    """)
    suspend fun getPendingSyncMemos(accountKey: String): List<MemoEntity>

    @Query("""
        SELECT * FROM memos 
        WHERE accountKey = :accountKey 
        AND isDeleted = 1
        ORDER BY lastModified ASC
    """)
    suspend fun getDeletedMemos(accountKey: String): List<MemoEntity>

    @Query("""
        SELECT * FROM memos 
        WHERE accountKey = :accountKey 
        AND lastModified > :since
        ORDER BY lastModified ASC
    """)
    suspend fun getModifiedMemosSince(accountKey: String, since: Instant): List<MemoEntity>

    @Query("SELECT COUNT(*) FROM memos WHERE accountKey = :accountKey AND needsSync = 1")
    suspend fun countUnsyncedMemos(accountKey: String): Int

    @Query("SELECT * FROM memos WHERE identifier = :identifier AND accountKey = :accountKey")
    suspend fun getMemoById(identifier: String, accountKey: String): MemoEntity?

    @Query("SELECT * FROM memos WHERE remoteId = :remoteId AND accountKey = :accountKey")
    suspend fun getMemoByRemoteId(remoteId: String, accountKey: String): MemoEntity?

    @Query("SELECT MAX(lastSyncedAt) FROM memos WHERE accountKey = :accountKey AND lastSyncedAt IS NOT NULL")
    suspend fun getLastSyncTimestamp(accountKey: String): Instant?

    @Upsert
    suspend fun insertMemo(memo: MemoEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMemos(memos: List<MemoEntity>)

    @Delete
    suspend fun deleteMemo(memo: MemoEntity)

    @Delete
    suspend fun deleteMemos(memos: List<MemoEntity>)

    @Query("SELECT * FROM resources WHERE memoId = :memoId AND accountKey = :accountKey")
    suspend fun getMemoResources(memoId: String, accountKey: String): List<ResourceEntity>

    @Query("SELECT tagName FROM memo_tags WHERE memoId = :memoId AND accountKey = :accountKey ORDER BY tagName COLLATE NOCASE ASC")
    suspend fun getMemoTags(memoId: String, accountKey: String): List<String>

    @Query(
        """
        SELECT DISTINCT memoId
        FROM memo_tags
        WHERE accountKey = :accountKey
          AND (tagName = :tag OR tagName LIKE :tagPrefix)
    """
    )
    suspend fun listMemoIdsByTagPrefix(accountKey: String, tag: String, tagPrefix: String): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertResource(resource: ResourceEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertResources(resources: List<ResourceEntity>)

    @Delete
    suspend fun deleteResource(resource: ResourceEntity)

    @Delete
    suspend fun deleteResources(resources: List<ResourceEntity>)

    @Query("SELECT * FROM resources WHERE accountKey = :accountKey ORDER BY date DESC")
    suspend fun getAllResources(accountKey: String): List<ResourceEntity>

    @Query("SELECT * FROM resources WHERE accountKey = :accountKey ORDER BY date DESC")
    fun observeAllResources(accountKey: String): Flow<List<ResourceEntity>>

    @Query("SELECT * FROM resources WHERE identifier = :identifier AND accountKey = :accountKey")
    suspend fun getResourceById(identifier: String, accountKey: String): ResourceEntity?

    @Query("SELECT * FROM resources WHERE remoteId = :remoteId AND accountKey = :accountKey")
    suspend fun getResourceByRemoteId(remoteId: String, accountKey: String): ResourceEntity?

    @Query(
        """
        SELECT * FROM resources
        WHERE accountKey = :accountKey
          AND (identifier = :identifier OR remoteId = :identifier)
        LIMIT 1
        """
    )
    fun observeResourceByIdentifier(identifier: String, accountKey: String): Flow<ResourceEntity?>

    @Query("DELETE FROM resources WHERE accountKey = :accountKey")
    suspend fun deleteResourcesByAccount(accountKey: String)

    @Query("DELETE FROM memos WHERE accountKey = :accountKey")
    suspend fun deleteMemosByAccount(accountKey: String)

    @Upsert
    suspend fun insertTag(tag: TagEntity)

    @Upsert
    suspend fun insertTags(tags: List<TagEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMemoTagCrossRef(crossRef: MemoTagCrossRef)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMemoTagCrossRefs(crossRefs: List<MemoTagCrossRef>)

    @Query("DELETE FROM memo_tags WHERE memoId = :memoId AND accountKey = :accountKey")
    suspend fun deleteMemoTagsForMemo(memoId: String, accountKey: String)

    @Query("DELETE FROM memo_tags WHERE accountKey = :accountKey")
    suspend fun deleteMemoTagsByAccount(accountKey: String)

    @Query("DELETE FROM tags WHERE accountKey = :accountKey")
    suspend fun deleteTagsByAccount(accountKey: String)

    @Query("""
        DELETE FROM tags
        WHERE accountKey = :accountKey
          AND name NOT IN (
            SELECT tagName FROM memo_tags WHERE accountKey = :accountKey
          )
    """)
    suspend fun pruneUnusedTags(accountKey: String)

    @Query("""
        SELECT t.name
        FROM tags t
        LEFT JOIN memo_tags mt
          ON mt.accountKey = t.accountKey
         AND mt.tagName = t.name
        LEFT JOIN memos m
          ON m.identifier = mt.memoId
         AND m.accountKey = t.accountKey
         AND m.isDeleted = 0
         AND m.date >= :since
        WHERE t.accountKey = :accountKey
        GROUP BY t.name
        ORDER BY COUNT(m.identifier) DESC, MAX(m.date) DESC, t.name COLLATE NOCASE ASC
    """)
    suspend fun listTagsByRecentUsage(accountKey: String, since: Instant): List<String>

    @Query("""
        SELECT t.name
        FROM tags t
        LEFT JOIN memo_tags mt
          ON mt.accountKey = t.accountKey
         AND mt.tagName = t.name
        LEFT JOIN memos m
          ON m.identifier = mt.memoId
         AND m.accountKey = t.accountKey
         AND m.isDeleted = 0
         AND m.date >= :since
        WHERE t.accountKey = :accountKey
        GROUP BY t.name
        ORDER BY COUNT(m.identifier) DESC, MAX(m.date) DESC, t.name COLLATE NOCASE ASC
    """)
    fun observeTagsByRecentUsage(accountKey: String, since: Instant): Flow<List<String>>

    @Transaction
    suspend fun replaceMemoTags(memoId: String, accountKey: String, tags: List<String>) {
        val now = Instant.now()
        val normalizedTags = tags
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .toList()
        deleteMemoTagsForMemo(memoId, accountKey)
        normalizedTags.forEach { tag ->
            insertTag(
                TagEntity(
                    accountKey = accountKey,
                    name = tag,
                    createdAt = now,
                    updatedAt = now
                )
            )
            insertMemoTagCrossRef(
                MemoTagCrossRef(
                    memoId = memoId,
                    accountKey = accountKey,
                    tagName = tag,
                    createdAt = now
                )
            )
        }
        pruneUnusedTags(accountKey)
    }

}
