package site.lcyk.keer.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import site.lcyk.keer.data.local.entity.UiSurfaceSnapshotEntity

@Dao
interface UiSurfaceSnapshotDao {
    @Query(
        """
        SELECT * FROM ui_surface_snapshots
        WHERE accountKey = :accountKey AND surfaceKey = :surfaceKey
        LIMIT 1
        """
    )
    suspend fun getSnapshot(
        accountKey: String,
        surfaceKey: String,
    ): UiSurfaceSnapshotEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSnapshot(snapshot: UiSurfaceSnapshotEntity)

    @Query("DELETE FROM ui_surface_snapshots WHERE accountKey = :accountKey")
    suspend fun deleteSnapshotsForAccount(accountKey: String)
}
