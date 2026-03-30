package site.lcyk.keer.data.local.entity

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "ui_surface_snapshots",
    primaryKeys = ["accountKey", "surfaceKey"],
    indices = [
        Index(value = ["accountKey"]),
        Index(value = ["accountKey", "updatedAtEpochMillis"]),
    ],
)
data class UiSurfaceSnapshotEntity(
    val accountKey: String,
    val surfaceKey: String,
    val payloadJson: String,
    val updatedAtEpochMillis: Long,
)
