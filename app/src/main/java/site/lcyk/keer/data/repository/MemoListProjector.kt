package site.lcyk.keer.data.repository

import site.lcyk.keer.data.local.entity.MemoEntity
import site.lcyk.keer.data.local.entity.MemoWithResources
import site.lcyk.keer.data.local.entity.ResourceEntity
import site.lcyk.keer.data.local.entity.TagEntity
import site.lcyk.keer.util.ProjectedList

internal class MemoListProjector {
    private data class CachedMemoProjection(
        val memo: MemoEntity,
        val resources: List<ResourceEntity>,
        val tags: List<String>,
    )

    private var previousList: ProjectedList<MemoEntity> = ProjectedList.empty()
    private var previousById: Map<String, CachedMemoProjection> = emptyMap()

    fun project(items: List<MemoWithResources>): List<MemoEntity> {
        if (items.isEmpty()) {
            previousById = emptyMap()
            previousList = ProjectedList.empty()
            return previousList
        }

        val nextById = LinkedHashMap<String, CachedMemoProjection>(items.size)
        val projectedItems = ArrayList<MemoEntity>(items.size)
        var identicalToPrevious = previousList.size == items.size

        items.forEachIndexed { index, item ->
            val cached = previousById[item.memo.identifier]
            val resolvedResources = cached?.resources?.takeIf { it == item.resources } ?: item.resources
            val resolvedTags = cached?.tags?.takeIf { it.matches(item.tags) } ?: item.tags.map(TagEntity::name)
            val projectedMemo = if (
                cached != null &&
                cached.memo == item.memo &&
                cached.resources === resolvedResources &&
                cached.tags === resolvedTags
            ) {
                cached.memo
            } else {
                projectMemoWithRelations(
                    memo = item.memo,
                    resources = resolvedResources,
                    tags = resolvedTags,
                )
            }
            projectedItems += projectedMemo
            nextById[item.memo.identifier] = CachedMemoProjection(
                memo = projectedMemo,
                resources = resolvedResources,
                tags = resolvedTags,
            )
            if (identicalToPrevious && previousList[index] !== projectedMemo) {
                identicalToPrevious = false
            }
        }

        previousById = nextById
        if (identicalToPrevious) {
            return previousList
        }

        return ProjectedList.wrap(projectedItems).also { projectedList ->
            previousList = projectedList
        }
    }
}

internal fun projectMemoWithRelations(item: MemoWithResources): MemoEntity {
    return projectMemoWithRelations(
        memo = item.memo,
        resources = item.resources,
        tags = item.tags.map(TagEntity::name),
    )
}

internal fun projectMemoWithRelations(
    memo: MemoEntity,
    resources: List<ResourceEntity>,
    tags: List<String>,
): MemoEntity {
    return memo.copy().also { projectedMemo ->
        projectedMemo.resources = resources
        projectedMemo.tags = tags
    }
}

private fun List<String>.matches(tags: List<TagEntity>): Boolean {
    if (size != tags.size) {
        return false
    }
    for (index in indices) {
        if (this[index] != tags[index].name) {
            return false
        }
    }
    return true
}
