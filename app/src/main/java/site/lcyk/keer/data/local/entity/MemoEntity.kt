package site.lcyk.keer.data.local.entity

import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.Index
import androidx.room.PrimaryKey
import site.lcyk.keer.data.model.MemoRepresentable
import site.lcyk.keer.data.model.MemoVisibility
import java.time.Instant

@Entity(
    tableName = "memos",
    indices = [
        Index(value = ["accountKey"]),
        Index(value = ["accountKey", "remoteId"])
    ]
)
data class MemoEntity(
    @PrimaryKey
    val identifier: String,
    override val remoteId: String? = null,
    val accountKey: String,
    override val content: String,
    override val date: Instant,
    override val visibility: MemoVisibility,
    override val pinned: Boolean,
    override val archived: Boolean = false,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val needsSync: Boolean = true,
    val isDeleted: Boolean = false,
    val lastModified: Instant = Instant.now(),
    val lastSyncedAt: Instant? = null
) : MemoRepresentable {
    @Ignore
    override var resources: List<ResourceEntity> = emptyList()

    @Ignore
    override var tags: List<String> = emptyList()
}
