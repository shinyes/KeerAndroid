package site.lcyk.keer.util

import site.lcyk.keer.data.model.MemoGroup
import site.lcyk.keer.data.model.MemoGroupType

private const val GROUP_ENTRY_PREFIX = "group:"
private const val DIRECT_ENTRY_PREFIX = "direct:"

fun exploreGroupEntryId(groupId: String): String {
    val normalizedGroupId = normalizeExploreEntryIdSegment(groupId)
    return "$GROUP_ENTRY_PREFIX$normalizedGroupId"
}

fun exploreDirectEntryId(userIdentifier: String): String {
    val normalizedUserId = normalizeExploreEntryIdSegment(userIdentifier)
    return "$DIRECT_ENTRY_PREFIX$normalizedUserId"
}

fun resolveMemoGroupExploreEntryId(
    group: MemoGroup,
    currentUserIdentifier: String?,
): String {
    if (group.type != MemoGroupType.DIRECT) {
        return exploreGroupEntryId(group.id)
    }
    val normalizedCurrentUserId = normalizeExploreEntryIdSegment(currentUserIdentifier.orEmpty())
    val directPeerId = group.members
        .asSequence()
        .map { member -> normalizeExploreEntryIdSegment(member.userId) }
        .firstOrNull { memberId -> memberId.isNotBlank() && memberId != normalizedCurrentUserId }
    if (!directPeerId.isNullOrBlank()) {
        return exploreDirectEntryId(directPeerId)
    }
    return exploreGroupEntryId(group.id)
}

private fun normalizeExploreEntryIdSegment(raw: String): String {
    return raw
        .trim()
        .substringAfterLast('/')
        .trim()
        .lowercase()
}
