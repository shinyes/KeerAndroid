package site.lcyk.keer.data.model

import kotlinx.serialization.Serializable

@Serializable
data class UserData(
    val settings: UserSettings = UserSettings(),
    val accountKey: String = "",
    val keerV2: MemosAccount? = null,
    val local: LocalAccount? = null,
) {
    enum class AccountCase {
        KEER_V2,
        LOCAL,
        ACCOUNT_NOT_SET,
    }

    val accountCase: AccountCase
        get() = when {
            keerV2 != null -> AccountCase.KEER_V2
            local != null -> AccountCase.LOCAL
            else -> AccountCase.ACCOUNT_NOT_SET
        }
}
