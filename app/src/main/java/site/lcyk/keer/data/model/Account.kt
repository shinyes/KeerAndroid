package site.lcyk.keer.data.model

import java.time.Instant

sealed class Account {
    fun accountKey(): String = when (this) {
        is KeerV2 -> "memos:${this.info.host}:${this.info.id}"
        is Local -> "local"
    }

    fun getAccountInfo(): MemosAccount? = when (this) {
        is KeerV2 -> this.info
        else -> null
    }

    fun toUser(): User = when (this) {
        is KeerV2 -> info.toUser()
        is Local -> info.toUser()
    }

    fun withUser(user: User): Account = when (this) {
        is KeerV2 -> KeerV2(info.withUser(user))
        is Local -> Local(info.withUser(user))
    }

    companion object {
        fun parseUserData(userData: UserData): Account? = when (userData.accountCase) {
            UserData.AccountCase.KEER_V2 -> userData.keerV2?.let { KeerV2(it) }
            UserData.AccountCase.LOCAL -> Local(userData.local ?: LocalAccount())
            else -> null
        }
    }

    class KeerV2(val info: MemosAccount) : Account()
    class Local(val info: LocalAccount = LocalAccount()) : Account()
}

private fun MemosAccount.toUser(): User {
    val visibility = MemoVisibility.entries.firstOrNull { it.name == defaultVisibility }
        ?: MemoVisibility.PRIVATE
    val startDate = if (startDateEpochSecond > 0L) {
        Instant.ofEpochSecond(startDateEpochSecond)
    } else {
        Instant.now()
    }
    return User(
        identifier = id.toString(),
        name = name,
        startDate = startDate,
        defaultVisibility = visibility
    )
}

private fun MemosAccount.withUser(user: User): MemosAccount {
    return copy(
        name = user.name,
        startDateEpochSecond = user.startDate.epochSecond,
        defaultVisibility = user.defaultVisibility.name,
        avatarUrl = user.avatarUrl ?: ""
    )
}

private fun LocalAccount.toUser(): User {
    val startDate = Instant.ofEpochSecond(startDateEpochSecond.coerceAtLeast(0L))
    return User(
        identifier = "local",
        name = "Local Account",
        startDate = startDate
    )
}

private fun LocalAccount.withUser(user: User): LocalAccount {
    return copy(
        startDateEpochSecond = user.startDate.epochSecond
    )
}
