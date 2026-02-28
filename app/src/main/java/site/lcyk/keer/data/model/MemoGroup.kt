package site.lcyk.keer.data.model

import kotlinx.serialization.Serializable

@Serializable
data class GroupMember(
    val userId: String,
    val userName: String
)

@Serializable
data class MemoGroup(
    val id: String,
    val name: String,
    val description: String = "",
    val creatorId: String,
    val creatorName: String,
    val members: List<GroupMember> = emptyList(),
    val createdAtEpochMillis: Long = System.currentTimeMillis()
)
