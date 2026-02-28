package site.lcyk.keer.util

import site.lcyk.keer.data.model.UserSettings

const val DEFAULT_GROUP_ALIAS_RETENTION_MILLIS: Long = 7L * 24L * 60L * 60L * 1000L
const val DEFAULT_MAX_GROUP_ALIAS_ENTRIES: Int = 200

fun linkedGroupIds(settings: UserSettings, groupId: String): Set<String> {
    val linked = mutableSetOf(groupId)
    settings.groupIdAliases.forEach { alias ->
        if (alias.localId == groupId || alias.remoteId == groupId) {
            linked += alias.localId
            linked += alias.remoteId
        }
    }
    return linked
}

fun removeGroupReferences(settings: UserSettings, groupId: String): UserSettings {
    val linkedIds = linkedGroupIds(settings, groupId)
    return settings.copy(
        groups = settings.groups.filterNot { it.id in linkedIds },
        pendingGroupMemos = settings.pendingGroupMemos.filterNot { it.groupId in linkedIds },
        pinnedGroupMemoKeys = settings.pinnedGroupMemoKeys.filterNot { key ->
            linkedIds.any { id -> key.startsWith("$id|") }
        },
        cachedGroupMemos = settings.cachedGroupMemos.filterNot { memo ->
            memo.groupId != null && memo.groupId in linkedIds
        },
        cachedGroupTags = settings.cachedGroupTags.filterNot { it.groupId in linkedIds },
        groupIdAliases = settings.groupIdAliases.filterNot { alias ->
            alias.localId in linkedIds || alias.remoteId in linkedIds
        }
    )
}

fun cleanupGroupAliases(
    settings: UserSettings,
    nowMillis: Long = System.currentTimeMillis(),
    retentionMillis: Long = DEFAULT_GROUP_ALIAS_RETENTION_MILLIS,
    maxEntries: Int = DEFAULT_MAX_GROUP_ALIAS_ENTRIES
): UserSettings {
    if (settings.groupIdAliases.isEmpty()) {
        return settings
    }
    val referencedGroupIds = buildSet {
        settings.groups.forEach { add(it.id) }
        settings.pendingGroupOperations.forEach { add(it.groupId) }
        settings.pendingGroupMemos.forEach { add(it.groupId) }
        settings.cachedGroupMemos.forEach { memo ->
            memo.groupId?.let(::add)
        }
        settings.cachedGroupTags.forEach { add(it.groupId) }
        settings.pinnedGroupMemoKeys.forEach { key ->
            key.substringBefore('|', "").takeIf { it.isNotEmpty() }?.let(::add)
        }
    }
    val aliases = settings.groupIdAliases
        .asReversed()
        .distinctBy { alias -> alias.localId to alias.remoteId }
        .asReversed()
        .filter { alias ->
            val stillReferenced =
                alias.localId in referencedGroupIds || alias.remoteId in referencedGroupIds
            val notExpired = nowMillis - alias.updatedAtEpochMillis <= retentionMillis
            stillReferenced || notExpired
        }
        .sortedByDescending { it.updatedAtEpochMillis }
        .take(maxEntries)

    return if (aliases == settings.groupIdAliases) {
        settings
    } else {
        settings.copy(groupIdAliases = aliases)
    }
}
