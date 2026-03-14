package site.lcyk.keer.data.model

fun Settings.currentUserDataOrNull(): UserData? {
    return usersList.firstOrNull { user -> user.accountKey == currentUser }
}

fun Settings.userDataOrNull(accountKey: String): UserData? {
    return usersList.firstOrNull { user -> user.accountKey == accountKey }
}

fun Settings.currentUserSettingsOrNull(): UserSettings? {
    return currentUserDataOrNull()?.settings
}

fun Settings.userSettingsOrNull(accountKey: String): UserSettings? {
    return userDataOrNull(accountKey)?.settings
}

inline fun Settings.updateCurrentUserData(transform: (UserData) -> UserData): Settings {
    return updateUserData(currentUser, transform)
}

inline fun Settings.updateUserData(accountKey: String, transform: (UserData) -> UserData): Settings {
    val index = usersList.indexOfFirst { user -> user.accountKey == accountKey }
    if (index == -1) {
        return this
    }
    val users = usersList.toMutableList()
    users[index] = transform(users[index])
    return copy(usersList = users)
}

fun Settings.upsertUserData(userData: UserData): Settings {
    val users = usersList.toMutableList()
    val index = users.indexOfFirst { existing -> existing.accountKey == userData.accountKey }
    if (index == -1) {
        users.add(userData)
    } else {
        users[index] = userData
    }
    return copy(usersList = users)
}

fun Settings.removeUserData(accountKey: String): Settings {
    return copy(usersList = usersList.filterNot { user -> user.accountKey == accountKey })
}
