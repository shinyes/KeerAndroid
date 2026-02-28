package site.lcyk.keer.util

const val COLLABORATOR_TAG_PREFIX = "collab/"

fun normalizeCollaboratorId(raw: String): String {
    val normalized = raw
        .trim()
        .substringAfterLast('/')
        .trim()
    if (normalized.isEmpty()) {
        return ""
    }
    return normalized
}

fun collaboratorTag(userId: String): String {
    val normalized = normalizeCollaboratorId(userId)
    return if (normalized.isEmpty()) "" else "$COLLABORATOR_TAG_PREFIX$normalized"
}

fun isCollaboratorTag(tag: String): Boolean {
    return normalizeTagName(tag).startsWith(COLLABORATOR_TAG_PREFIX)
}

fun extractCollaboratorIds(tags: List<String>): List<String> {
    return tags.asSequence()
        .map(::normalizeTagName)
        .filter(::isCollaboratorTag)
        .map { normalizeCollaboratorId(it.removePrefix(COLLABORATOR_TAG_PREFIX)) }
        .filter { it.isNotEmpty() }
        .distinct()
        .toList()
}

fun stripCollaboratorTags(tags: List<String>): List<String> {
    return tags.asSequence()
        .map(::normalizeTagName)
        .filter { it.isNotEmpty() }
        .filterNot(::isCollaboratorTag)
        .distinct()
        .toList()
}

fun mergeTagsWithCollaborators(tags: List<String>, collaboratorIds: List<String>): List<String> {
    val normalizedTags = normalizeTagList(stripCollaboratorTags(tags))
    val collaboratorTags = collaboratorIds
        .asSequence()
        .map(::collaboratorTag)
        .filter { it.isNotEmpty() }
        .distinct()
        .toList()
    return (normalizedTags + collaboratorTags).distinct()
}

fun buildCollaboratorFilterExpression(userId: String): String {
    val tag = collaboratorTag(userId)
    if (tag.isEmpty()) {
        return ""
    }
    return "\"${tag.replace("\"", "\\\"")}\" in tags"
}
