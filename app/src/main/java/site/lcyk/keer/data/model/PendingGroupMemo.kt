package site.lcyk.keer.data.model

import kotlinx.serialization.Serializable

@Serializable
data class PendingGroupMemo(
    val localId: String,
    val groupId: String,
    val content: String,
    val tags: List<String> = emptyList(),
    val creatorId: String,
    val creatorName: String,
    val creatorAvatarUrl: String? = null,
    val createdAtEpochMillis: Long
)
