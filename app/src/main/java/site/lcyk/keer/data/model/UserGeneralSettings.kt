package site.lcyk.keer.data.model

import kotlinx.serialization.Serializable

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

@Serializable
data class ExploreDrawerEntryConfig(
    val entryId: String,
    val visibleInExplore: Boolean = true,
)

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
