package site.lcyk.keer.data.model

import kotlinx.serialization.Serializable

@Serializable
data class QuickMemoDraftState(
    val text: String = "",
    val selectedTags: List<String> = emptyList(),
    val selectedCollaborators: List<String> = emptyList(),
    val resourceIdentifiers: List<String> = emptyList(),
) {
    fun isEmpty(): Boolean {
        return text.isBlank() &&
            selectedTags.isEmpty() &&
            selectedCollaborators.isEmpty() &&
            resourceIdentifiers.isEmpty()
    }
}
