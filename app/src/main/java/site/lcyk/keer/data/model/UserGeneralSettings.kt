package site.lcyk.keer.data.model

import kotlinx.serialization.Serializable
import site.lcyk.keer.util.normalizeTagList

@Serializable
enum class MemoEditGesture {
    NONE,
    SINGLE,
    DOUBLE,
    LONG,
}

@Serializable
data class UserGeneralSettings(
    val memoVisibility: MemoVisibility = MemoVisibility.PRIVATE,
    val memoEditGesture: MemoEditGesture = MemoEditGesture.NONE,
    val memoColumns: List<MemoColumnConfig> = emptyList(),
    val exploreDrawerEntries: List<ExploreDrawerEntryConfig> = emptyList(),
)

private const val TAG_DRAWER_ENTRY_PREFIX = "drawer_tag:"

@Serializable
data class ExploreDrawerEntryConfig(
    val entryId: String,
    val visibleInExplore: Boolean = true,
)

fun UserGeneralSettings.isTagVisibleInDrawer(tag: String): Boolean {
    val normalizedTag = tag.trim()
    if (normalizedTag.isEmpty()) {
        return true
    }
    val tagEntryId = toTagDrawerEntryId(normalizedTag)
    return exploreDrawerEntries
        .firstOrNull { config -> config.entryId == tagEntryId }
        ?.visibleInExplore
        ?: true
}

fun UserGeneralSettings.isExploreEntryVisible(entryId: String): Boolean {
    val normalizedEntryId = entryId.trim()
    if (normalizedEntryId.isEmpty()) {
        return true
    }
    return exploreDrawerEntries
        .firstOrNull { config -> config.entryId == normalizedEntryId }
        ?.visibleInExplore
        ?: true
}

fun UserGeneralSettings.withExploreEntryVisibility(
    entryId: String,
    visibleInExplore: Boolean,
): UserGeneralSettings {
    val normalizedEntryId = entryId.trim()
    if (normalizedEntryId.isEmpty()) {
        return this
    }
    val existing = exploreDrawerEntries
    val updated = mutableListOf<ExploreDrawerEntryConfig>()
    var replaced = false
    existing.forEach { config ->
        if (config.entryId.trim() == normalizedEntryId) {
            if (!replaced) {
                updated += config.copy(
                    entryId = normalizedEntryId,
                    visibleInExplore = visibleInExplore,
                )
                replaced = true
            }
        } else {
            updated += config
        }
    }
    if (!replaced) {
        updated += ExploreDrawerEntryConfig(
            entryId = normalizedEntryId,
            visibleInExplore = visibleInExplore,
        )
    }
    return copy(exploreDrawerEntries = updated)
}

fun UserGeneralSettings.withTagDrawerVisibility(
    tag: String,
    visibleInDrawer: Boolean,
): UserGeneralSettings {
    val normalizedTag = tag.trim()
    if (normalizedTag.isEmpty()) {
        return this
    }
    return withExploreEntryVisibility(
        entryId = toTagDrawerEntryId(normalizedTag),
        visibleInExplore = visibleInDrawer,
    )
}

fun UserGeneralSettings.orderTagsForDrawer(tags: List<String>): List<String> {
    val normalizedTags = normalizeTagList(tags)
    if (normalizedTags.isEmpty()) {
        return emptyList()
    }
    val ordered = mutableListOf<String>()
    val seen = linkedSetOf<String>()

    exploreDrawerEntries.forEach { config ->
        val tagPath = config.entryId.toTagDrawerPathOrNull() ?: return@forEach
        if (tagPath in normalizedTags && seen.add(tagPath)) {
            ordered += tagPath
        }
    }

    normalizedTags.forEach { tag ->
        if (seen.add(tag)) {
            ordered += tag
        }
    }

    return ordered
}

fun UserGeneralSettings.withReorderedTagDrawerEntries(
    orderedTags: List<String>,
): UserGeneralSettings {
    val normalizedOrderedTags = normalizeTagList(orderedTags)
    if (normalizedOrderedTags.isEmpty()) {
        return this
    }

    val existingTagConfigsByPath = linkedMapOf<String, ExploreDrawerEntryConfig>()
    exploreDrawerEntries.forEach { config ->
        val tagPath = config.entryId.toTagDrawerPathOrNull() ?: return@forEach
        existingTagConfigsByPath.putIfAbsent(
            tagPath,
            config.copy(entryId = toTagDrawerEntryId(tagPath)),
        )
    }

    val orderedTagConfigs = normalizedOrderedTags.map { tag ->
        existingTagConfigsByPath[tag] ?: ExploreDrawerEntryConfig(
            entryId = toTagDrawerEntryId(tag),
            visibleInExplore = true,
        )
    }

    val remainingExistingTagConfigs = existingTagConfigsByPath
        .filterKeys { tag -> tag !in normalizedOrderedTags }
        .values

    val reorderedTagConfigs = (orderedTagConfigs + remainingExistingTagConfigs).iterator()
    val updatedEntries = mutableListOf<ExploreDrawerEntryConfig>()

    exploreDrawerEntries.forEach { config ->
        if (config.entryId.toTagDrawerPathOrNull() != null) {
            if (reorderedTagConfigs.hasNext()) {
                updatedEntries += reorderedTagConfigs.next()
            }
        } else {
            updatedEntries += config
        }
    }

    while (reorderedTagConfigs.hasNext()) {
        updatedEntries += reorderedTagConfigs.next()
    }

    return copy(exploreDrawerEntries = updatedEntries)
}

fun UserGeneralSettings.withRenamedTagDrawerEntries(
    oldTag: String,
    newTag: String,
): UserGeneralSettings {
    val normalizedOldTag = oldTag.trim()
    val normalizedNewTag = newTag.trim()
    if (normalizedOldTag.isEmpty() || normalizedNewTag.isEmpty()) {
        return this
    }
    val updated = mutableListOf<ExploreDrawerEntryConfig>()
    val seenEntryIds = linkedSetOf<String>()
    exploreDrawerEntries.forEach { config ->
        val tagPath = config.entryId.toTagDrawerPathOrNull()
        val nextEntryId = if (tagPath == null) {
            config.entryId
        } else {
            toTagDrawerEntryId(
                renameTagPathWithPrefix(
                    tag = tagPath,
                    oldPrefix = normalizedOldTag,
                    newPrefix = normalizedNewTag,
                )
            )
        }
        if (seenEntryIds.add(nextEntryId)) {
            updated += config.copy(entryId = nextEntryId)
        }
    }
    return copy(exploreDrawerEntries = updated)
}

fun UserGeneralSettings.withoutTagDrawerEntries(
    tag: String,
): UserGeneralSettings {
    val normalizedTag = tag.trim()
    if (normalizedTag.isEmpty()) {
        return this
    }
    return copy(
        exploreDrawerEntries = exploreDrawerEntries.filterNot { config ->
            val tagPath = config.entryId.toTagDrawerPathOrNull() ?: return@filterNot false
            tagPath == normalizedTag || tagPath.startsWith("$normalizedTag/")
        }
    )
}

private fun toTagDrawerEntryId(tag: String): String {
    return TAG_DRAWER_ENTRY_PREFIX + tag
}

private fun String.toTagDrawerPathOrNull(): String? {
    if (!startsWith(TAG_DRAWER_ENTRY_PREFIX)) {
        return null
    }
    val tag = removePrefix(TAG_DRAWER_ENTRY_PREFIX).trim()
    return tag.ifEmpty { null }
}

private fun renameTagPathWithPrefix(
    tag: String,
    oldPrefix: String,
    newPrefix: String,
): String {
    return when {
        tag == oldPrefix -> newPrefix
        tag.startsWith("$oldPrefix/") -> "$newPrefix/${tag.removePrefix("$oldPrefix/")}"
        else -> tag
    }
}
