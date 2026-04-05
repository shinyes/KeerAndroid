package site.lcyk.keer.data.model

import kotlinx.serialization.Serializable

@Serializable
data class UserSettings(
    val draft: String = "",
    val quickMemoDraft: QuickMemoDraftState = QuickMemoDraftState(),
    val acceptedUnsupportedSyncVersions: List<String> = emptyList(),
    val generalSettings: UserGeneralSettings = UserGeneralSettings(),
    val avatarUri: String = "",
    val avatarSyncPending: Boolean = false,
    val memoSyncAnchor: String = "",
    val groupSyncCursor: String = "",
    val profileSyncCursor: String = "",
    val streamSyncCursor: String = "",
    val userSyncAnchor: String = "",
    val syncedUserIds: List<String> = emptyList(),
)
