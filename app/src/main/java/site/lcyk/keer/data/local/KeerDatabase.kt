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
    version = 5
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
    }
}
