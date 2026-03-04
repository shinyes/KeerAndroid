package site.lcyk.keer.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import site.lcyk.keer.data.local.dao.MemoDao
import site.lcyk.keer.data.local.entity.MemoTagCrossRef
import site.lcyk.keer.data.local.entity.MemoEntity
import site.lcyk.keer.data.local.entity.ResourceEntity
import site.lcyk.keer.data.local.entity.TagEntity

@Database(
    entities = [MemoEntity::class, ResourceEntity::class, TagEntity::class, MemoTagCrossRef::class],
    version = 7
)
@TypeConverters(Converters::class)
abstract class KeerDatabase : RoomDatabase() {
    abstract fun memoDao(): MemoDao

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
    }
}
