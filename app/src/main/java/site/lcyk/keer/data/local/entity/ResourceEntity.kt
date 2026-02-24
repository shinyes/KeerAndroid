package site.lcyk.keer.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import site.lcyk.keer.data.model.ResourceRepresentable
import java.time.Instant

@Entity(
    tableName = "resources",
    indices = [
        Index(value = ["memoId"]),
        Index(value = ["accountKey"]),
        Index(value = ["accountKey", "remoteId"])
    ],
    foreignKeys = [
        ForeignKey(
            entity = MemoEntity::class,
            parentColumns = ["identifier"],
            childColumns = ["memoId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class ResourceEntity(
    @PrimaryKey
    val identifier: String,
    override val remoteId: String? = null,
    val accountKey: String,
    override val date: Instant,
    override val filename: String,
    override val uri: String,
    override val localUri: String? = null,
    override val mimeType: String?,
    override val thumbnailUri: String? = null,
    val memoId: String? = null
) : ResourceRepresentable
