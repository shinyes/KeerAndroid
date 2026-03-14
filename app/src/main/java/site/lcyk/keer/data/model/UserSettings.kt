package site.lcyk.keer.data.model

import kotlinx.serialization.Serializable

@Serializable
data class UserSettings(
    val draft: String = "",
    val acceptedUnsupportedSyncVersions: List<String> = emptyList(),
    val generalSettings: UserGeneralSettings = UserGeneralSettings(),
    val avatarUri: String = "",
    val avatarSyncPending: Boolean = false,
    val memoSyncAnchor: String = "",
    val userSyncAnchor: String = "",
    val syncedUserIds: List<String> = emptyList(),
)
