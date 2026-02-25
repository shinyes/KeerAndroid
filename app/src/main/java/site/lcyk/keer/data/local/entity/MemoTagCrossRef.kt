package site.lcyk.keer.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import java.time.Instant

@Entity(
    tableName = "memo_tags",
    primaryKeys = ["memoId", "accountKey", "tagName"],
    indices = [
        Index(value = ["memoId"]),
        Index(value = ["accountKey"]),
        Index(value = ["accountKey", "tagName"])
    ],
    foreignKeys = [
        ForeignKey(
            entity = MemoEntity::class,
            parentColumns = ["identifier"],
            childColumns = ["memoId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = TagEntity::class,
            parentColumns = ["accountKey", "name"],
            childColumns = ["accountKey", "tagName"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class MemoTagCrossRef(
    val memoId: String,
    val accountKey: String,
    val tagName: String,
    val createdAt: Instant
)
