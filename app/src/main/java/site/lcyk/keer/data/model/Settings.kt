package site.lcyk.keer.data.model

import kotlinx.serialization.Serializable

@Serializable
data class Settings(
    val usersList: List<UserData> = emptyList(),
    val currentUser: String = "",
)
