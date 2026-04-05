package site.lcyk.keer.util

import site.lcyk.keer.data.local.entity.ResourceEntity
import site.lcyk.keer.data.model.QuickMemoDraftState

data class QuickMemoDraftResourceRestore(
    val resources: List<ResourceEntity>,
    val resourceIdentifiers: List<String>,
)

fun normalizeQuickMemoDraftState(draft: QuickMemoDraftState): QuickMemoDraftState {
    return draft.copy(
        selectedTags = normalizeTagList(draft.selectedTags),
        selectedCollaborators = draft.selectedCollaborators
            .map(::normalizeCollaboratorId)
            .filter { collaboratorId -> collaboratorId.isNotEmpty() }
            .distinct(),
        resourceIdentifiers = draft.resourceIdentifiers
            .asSequence()
            .map { identifier -> identifier.trim() }
            .filter { identifier -> identifier.isNotEmpty() }
            .distinct()
            .toList(),
    )
}

fun buildQuickMemoDraftState(
    text: String,
    selectedTags: List<String>,
    forcedTags: List<String>,
    selectedCollaborators: List<String>,
    resources: List<ResourceEntity>,
): QuickMemoDraftState {
    val normalizedForcedTags = normalizeTagList(forcedTags).toSet()
    return normalizeQuickMemoDraftState(
        QuickMemoDraftState(
            text = text,
            selectedTags = normalizeTagList(selectedTags)
                .filterNot { tag -> tag in normalizedForcedTags },
            selectedCollaborators = selectedCollaborators,
            resourceIdentifiers = resources
                .mapNotNull { resource ->
                    resource.remoteId?.trim()?.takeIf { remoteId -> remoteId.isNotEmpty() }
                        ?: resource.identifier.trim().takeIf { identifier -> identifier.isNotEmpty() }
                }
                .distinct(),
        )
    )
}

fun mergeQuickMemoDraftTags(
    draft: QuickMemoDraftState,
    forcedTags: List<String>,
): List<String> {
    val normalizedDraft = normalizeQuickMemoDraftState(draft)
    return normalizeTagList(forcedTags + normalizedDraft.selectedTags)
}

fun resolveQuickMemoDraftResources(
    resourceIdentifiers: List<String>,
    resources: List<ResourceEntity>,
): QuickMemoDraftResourceRestore {
    val normalizedIdentifiers = resourceIdentifiers
        .asSequence()
        .map { identifier -> identifier.trim() }
        .filter { identifier -> identifier.isNotEmpty() }
        .distinct()
        .toList()
    val resourcesByIdentifier = linkedMapOf<String, ResourceEntity>()
    resources
        .distinctBy { resource -> resource.identifier }
        .forEach { resource ->
            val keys = listOf(resource.identifier, resource.remoteId)
                .mapNotNull { key -> key?.trim()?.takeIf { it.isNotEmpty() } }
            keys.forEach { key ->
                resourcesByIdentifier.putIfAbsent(key, resource)
            }
        }

    val restoredResources = mutableListOf<ResourceEntity>()
    val restoredIdentifiers = mutableListOf<String>()
    normalizedIdentifiers.forEach { identifier ->
        val resource = resourcesByIdentifier[identifier] ?: return@forEach
        if (restoredResources.any { existing -> existing.identifier == resource.identifier }) {
            return@forEach
        }
        restoredResources += resource
        restoredIdentifiers += identifier
    }
    return QuickMemoDraftResourceRestore(
        resources = restoredResources,
        resourceIdentifiers = restoredIdentifiers,
    )
}
