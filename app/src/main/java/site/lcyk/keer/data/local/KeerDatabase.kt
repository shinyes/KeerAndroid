package site.lcyk.keer.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteDatabase
import site.lcyk.keer.data.local.dao.MemoDao
import site.lcyk.keer.data.local.dao.OfflineGroupDao
import site.lcyk.keer.data.local.entity.MemoTagCrossRef
import site.lcyk.keer.data.local.entity.MemoEntity
import site.lcyk.keer.data.local.entity.OfflineCachedGroupMemoEntity
import site.lcyk.keer.data.local.entity.OfflineCachedGroupTagEntity
import site.lcyk.keer.data.local.entity.OfflineGroupAliasEntity
import site.lcyk.keer.data.local.entity.OfflineGroupEntity
import site.lcyk.keer.data.local.entity.OfflineGroupMemberEntity
import site.lcyk.keer.data.local.entity.OfflinePendingGroupMemoEntity
import site.lcyk.keer.data.local.entity.OfflinePendingGroupOperationEntity
import site.lcyk.keer.data.local.entity.OfflinePinnedGroupMemoEntity
import site.lcyk.keer.data.local.entity.ResourceEntity
import site.lcyk.keer.data.local.entity.TagEntity

@Database(
    entities = [
        MemoEntity::class,
        ResourceEntity::class,
        TagEntity::class,
        MemoTagCrossRef::class,
        OfflineGroupEntity::class,
        OfflineGroupMemberEntity::class,
        OfflineGroupAliasEntity::class,
        OfflinePendingGroupOperationEntity::class,
        OfflinePendingGroupMemoEntity::class,
        OfflineCachedGroupMemoEntity::class,
        OfflineCachedGroupTagEntity::class,
        OfflinePinnedGroupMemoEntity::class,
    ],
    version = 15
)
@TypeConverters(Converters::class)
abstract class KeerDatabase : RoomDatabase() {
    abstract fun memoDao(): MemoDao
    abstract fun offlineGroupDao(): OfflineGroupDao

    companion object {
        @Volatile
        private var INSTANCE: KeerDatabase? = null

        fun getDatabase(context: Context): KeerDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    KeerDatabase::class.java,
                    "Keer_database_localfirst"
                )
                    .addMigrations(MIGRATION_1_2)
                    .addMigrations(MIGRATION_2_3)
                    .addMigrations(MIGRATION_3_4)
                    .addMigrations(MIGRATION_4_5)
                    .addMigrations(MIGRATION_5_6)
                    .addMigrations(MIGRATION_6_7)
                    .addMigrations(MIGRATION_7_8)
                    .addMigrations(MIGRATION_8_9)
                    .addMigrations(MIGRATION_9_10)
                    .addMigrations(MIGRATION_10_11)
                    .addMigrations(MIGRATION_11_12)
                    .addMigrations(MIGRATION_12_13)
                    .addMigrations(MIGRATION_13_14)
                    .addMigrations(MIGRATION_14_15)
                    .build()
                INSTANCE = instance
                instance
            }
        }

        private val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE resources ADD COLUMN thumbnailUri TEXT")
            }
        }

        private val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE resources ADD COLUMN thumbnailLocalUri TEXT")
            }
        }

        private val MIGRATION_3_4: Migration = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS tags (
                        accountKey TEXT NOT NULL,
                        name TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        PRIMARY KEY(accountKey, name)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_tags_accountKey ON tags(accountKey)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_tags_accountKey_updatedAt ON tags(accountKey, updatedAt)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS memo_tags (
                        memoId TEXT NOT NULL,
                        accountKey TEXT NOT NULL,
                        tagName TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        PRIMARY KEY(memoId, accountKey, tagName),
                        FOREIGN KEY(memoId) REFERENCES memos(identifier) ON DELETE CASCADE,
                        FOREIGN KEY(accountKey, tagName) REFERENCES tags(accountKey, name) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_memo_tags_memoId ON memo_tags(memoId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_memo_tags_accountKey ON memo_tags(accountKey)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_memo_tags_accountKey_tagName ON memo_tags(accountKey, tagName)")
            }
        }

        private val MIGRATION_4_5: Migration = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE memos ADD COLUMN latitude REAL")
                db.execSQL("ALTER TABLE memos ADD COLUMN longitude REAL")
            }
        }

        private val MIGRATION_5_6: Migration = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE memos ADD COLUMN quoteSourceKind TEXT")
                db.execSQL("ALTER TABLE memos ADD COLUMN quoteSource TEXT")
                db.execSQL("ALTER TABLE memos ADD COLUMN quoteStatus TEXT")
                db.execSQL("ALTER TABLE memos ADD COLUMN quoteContentPreview TEXT")
            }
        }

        private val MIGRATION_6_7: Migration = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("PRAGMA foreign_keys=OFF")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `memos_new` (
                        `identifier` TEXT NOT NULL,
                        `remoteId` TEXT,
                        `accountKey` TEXT NOT NULL,
                        `content` TEXT NOT NULL,
                        `date` INTEGER NOT NULL,
                        `visibility` TEXT NOT NULL,
                        `pinned` INTEGER NOT NULL,
                        `archived` INTEGER NOT NULL,
                        `latitude` REAL,
                        `longitude` REAL,
                        `needsSync` INTEGER NOT NULL,
                        `isDeleted` INTEGER NOT NULL,
                        `lastModified` INTEGER NOT NULL,
                        `lastSyncedAt` INTEGER,
                        PRIMARY KEY(`identifier`)
                    )
                    """.trimIndent()
                )

                db.execSQL(
                    """
                    INSERT INTO `memos_new` (
                        `identifier`,
                        `remoteId`,
                        `accountKey`,
                        `content`,
                        `date`,
                        `visibility`,
                        `pinned`,
                        `archived`,
                        `latitude`,
                        `longitude`,
                        `needsSync`,
                        `isDeleted`,
                        `lastModified`,
                        `lastSyncedAt`
                    )
                    SELECT
                        `identifier`,
                        `remoteId`,
                        `accountKey`,
                        `content`,
                        `date`,
                        `visibility`,
                        `pinned`,
                        `archived`,
                        `latitude`,
                        `longitude`,
                        `needsSync`,
                        `isDeleted`,
                        `lastModified`,
                        `lastSyncedAt`
                    FROM `memos`
                    """.trimIndent()
                )

                db.execSQL("DROP TABLE `memos`")
                db.execSQL("ALTER TABLE `memos_new` RENAME TO `memos`")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_memos_accountKey` ON `memos` (`accountKey`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_memos_accountKey_remoteId` ON `memos` (`accountKey`, `remoteId`)")

                db.execSQL("PRAGMA foreign_keys=ON")
            }
        }

        private val MIGRATION_7_8: Migration = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE resources ADD COLUMN encryptionMetadata TEXT")
            }
        }

        private val MIGRATION_8_9: Migration = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS offline_groups (
                        accountKey TEXT NOT NULL,
                        groupId TEXT NOT NULL,
                        name TEXT NOT NULL,
                        description TEXT NOT NULL,
                        creatorId TEXT NOT NULL,
                        creatorName TEXT NOT NULL,
                        groupType TEXT NOT NULL DEFAULT 'GROUP',
                        createdAtEpochMillis INTEGER NOT NULL,
                        PRIMARY KEY(accountKey, groupId)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_offline_groups_accountKey ON offline_groups(accountKey)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_offline_groups_accountKey_createdAtEpochMillis ON offline_groups(accountKey, createdAtEpochMillis)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS offline_group_members (
                        accountKey TEXT NOT NULL,
                        groupId TEXT NOT NULL,
                        userId TEXT NOT NULL,
                        userName TEXT NOT NULL,
                        PRIMARY KEY(accountKey, groupId, userId)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_offline_group_members_accountKey_groupId ON offline_group_members(accountKey, groupId)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS offline_group_aliases (
                        accountKey TEXT NOT NULL,
                        localId TEXT NOT NULL,
                        remoteId TEXT NOT NULL,
                        updatedAtEpochMillis INTEGER NOT NULL,
                        PRIMARY KEY(accountKey, localId, remoteId)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_offline_group_aliases_accountKey ON offline_group_aliases(accountKey)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_offline_group_aliases_accountKey_updatedAtEpochMillis ON offline_group_aliases(accountKey, updatedAtEpochMillis)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS offline_pending_group_operations (
                        accountKey TEXT NOT NULL,
                        operationId TEXT NOT NULL,
                        type TEXT NOT NULL,
                        groupId TEXT NOT NULL,
                        name TEXT,
                        description TEXT,
                        tag TEXT,
                        createdAtEpochMillis INTEGER NOT NULL,
                        PRIMARY KEY(accountKey, operationId)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_offline_pending_group_operations_accountKey_createdAtEpochMillis ON offline_pending_group_operations(accountKey, createdAtEpochMillis)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_offline_pending_group_operations_accountKey_groupId ON offline_pending_group_operations(accountKey, groupId)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS offline_pending_group_memos (
                        accountKey TEXT NOT NULL,
                        localId TEXT NOT NULL,
                        groupId TEXT NOT NULL,
                        content TEXT NOT NULL,
                        tagsJson TEXT NOT NULL,
                        creatorId TEXT NOT NULL,
                        creatorName TEXT NOT NULL,
                        creatorAvatarUrl TEXT,
                        createdAtEpochMillis INTEGER NOT NULL,
                        resourceIdsJson TEXT NOT NULL,
                        PRIMARY KEY(accountKey, groupId, localId)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_offline_pending_group_memos_accountKey_createdAtEpochMillis ON offline_pending_group_memos(accountKey, createdAtEpochMillis)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_offline_pending_group_memos_accountKey_groupId ON offline_pending_group_memos(accountKey, groupId)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS offline_cached_group_memos (
                        accountKey TEXT NOT NULL,
                        groupId TEXT NOT NULL,
                        remoteId TEXT NOT NULL,
                        payloadJson TEXT NOT NULL,
                        updatedAtEpochMillis INTEGER NOT NULL,
                        PRIMARY KEY(accountKey, groupId, remoteId)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_offline_cached_group_memos_accountKey_groupId ON offline_cached_group_memos(accountKey, groupId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_offline_cached_group_memos_accountKey_updatedAtEpochMillis ON offline_cached_group_memos(accountKey, updatedAtEpochMillis)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS offline_cached_explore_memos (
                        accountKey TEXT NOT NULL,
                        remoteId TEXT NOT NULL,
                        groupId TEXT,
                        payloadJson TEXT NOT NULL,
                        updatedAtEpochMillis INTEGER NOT NULL,
                        PRIMARY KEY(accountKey, remoteId)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_offline_cached_explore_memos_accountKey_updatedAtEpochMillis ON offline_cached_explore_memos(accountKey, updatedAtEpochMillis)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS offline_cached_group_tags (
                        accountKey TEXT NOT NULL,
                        groupId TEXT NOT NULL,
                        tagsJson TEXT NOT NULL,
                        updatedAtEpochMillis INTEGER NOT NULL,
                        PRIMARY KEY(accountKey, groupId)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_offline_cached_group_tags_accountKey_updatedAtEpochMillis ON offline_cached_group_tags(accountKey, updatedAtEpochMillis)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS offline_pinned_group_memos (
                        accountKey TEXT NOT NULL,
                        groupId TEXT NOT NULL,
                        memoRemoteId TEXT NOT NULL,
                        PRIMARY KEY(accountKey, groupId, memoRemoteId)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_offline_pinned_group_memos_accountKey_groupId ON offline_pinned_group_memos(accountKey, groupId)")
            }
        }

        private val MIGRATION_9_10: Migration = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP INDEX IF EXISTS index_offline_cached_explore_memos_accountKey_updatedAtEpochMillis")
                db.execSQL("DROP TABLE IF EXISTS offline_cached_explore_memos")
            }
        }

        private val MIGRATION_10_11: Migration = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE memos ADD COLUMN quoteSourceKind TEXT")
                db.execSQL("ALTER TABLE memos ADD COLUMN quoteSource TEXT")
                db.execSQL("ALTER TABLE memos ADD COLUMN quoteStatus TEXT")
                db.execSQL("ALTER TABLE memos ADD COLUMN quoteContentPreview TEXT")
                db.execSQL("ALTER TABLE memos ADD COLUMN quoteDate INTEGER")
                db.execSQL("ALTER TABLE memos ADD COLUMN quoteHasAttachments INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_11_12: Migration = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE offline_groups ADD COLUMN groupType TEXT NOT NULL DEFAULT 'GROUP'")
            }
        }

        private val MIGRATION_12_13: Migration = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE offline_groups ADD COLUMN hasUnreadDirectMessages INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_13_14: Migration = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                if (!hasColumn(db, "offline_groups", "hasUnreadDirectMessages")) {
                    db.execSQL("ALTER TABLE offline_groups ADD COLUMN hasUnreadDirectMessages INTEGER NOT NULL DEFAULT 0")
                }
            }
        }

        private val MIGRATION_14_15: Migration = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                if (!hasColumn(db, "offline_groups", "updatedAtEpochMillis")) {
                    db.execSQL("ALTER TABLE offline_groups ADD COLUMN updatedAtEpochMillis INTEGER NOT NULL DEFAULT 0")
                }
                db.execSQL(
                    """
                    UPDATE offline_groups
                    SET updatedAtEpochMillis = createdAtEpochMillis
                    WHERE updatedAtEpochMillis = 0
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS index_offline_groups_accountKey_updatedAtEpochMillis
                    ON offline_groups(accountKey, updatedAtEpochMillis)
                    """.trimIndent()
                )
            }
        }

        private fun hasColumn(db: SupportSQLiteDatabase, table: String, column: String): Boolean {
            db.query(SimpleSQLiteQuery("PRAGMA table_info($table)")).use { cursor ->
                val nameIndex = cursor.getColumnIndex("name")
                while (cursor.moveToNext()) {
                    if (nameIndex >= 0 && cursor.getString(nameIndex) == column) {
                        return true
                    }
                }
            }
            return false
        }
    }
}
