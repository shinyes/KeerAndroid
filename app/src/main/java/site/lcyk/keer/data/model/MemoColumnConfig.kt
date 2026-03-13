package site.lcyk.keer.data.model

import kotlinx.serialization.Serializable

@Serializable
data class MemoColumnConfig(
    val id: String = "",
    val name: String = "",
    val requiredTags: List<String> = emptyList(),
    val visibleInDrawer: Boolean = true,
)
