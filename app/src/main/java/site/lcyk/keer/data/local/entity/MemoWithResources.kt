package site.lcyk.keer.data.local.entity

import androidx.room.Embedded
import androidx.room.Junction
import androidx.room.Relation

data class MemoWithResources(
    @Embedded val memo: MemoEntity,
    @Relation(
        parentColumn = "identifier",
        entityColumn = "memoId"
    )
    val resources: List<ResourceEntity>,
    @Relation(
        parentColumn = "identifier",
        entity = TagEntity::class,
        entityColumn = "name",
        associateBy = Junction(
            value = MemoTagCrossRef::class,
            parentColumn = "memoId",
            entityColumn = "tagName"
        )
    )
    val tags: List<TagEntity>
)
