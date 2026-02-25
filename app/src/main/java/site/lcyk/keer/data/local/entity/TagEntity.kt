package site.lcyk.keer.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import java.time.Instant

@Entity(
    tableName = "tags",
    primaryKeys = ["accountKey", "name"],
    indices = [
        Index(value = ["accountKey"]),
        Index(value = ["accountKey", "updatedAt"])
    ]
)
data class TagEntity(
    val accountKey: String,
    val name: String,
    val createdAt: Instant,
    val updatedAt: Instant
)
