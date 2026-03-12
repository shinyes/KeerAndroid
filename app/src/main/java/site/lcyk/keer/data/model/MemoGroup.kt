package site.lcyk.keer.data.model

import kotlinx.serialization.Serializable

@Serializable
enum class MemoGroupType {
    GROUP,
    DIRECT,
}

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
    val type: MemoGroupType = MemoGroupType.GROUP,
    val members: List<GroupMember> = emptyList(),
    val hasUnreadDirectMessages: Boolean = false,
    val createdAtEpochMillis: Long = System.currentTimeMillis(),
    val updatedAtEpochMillis: Long = createdAtEpochMillis,
) {
    val isDirect: Boolean
        get() = type == MemoGroupType.DIRECT
}
