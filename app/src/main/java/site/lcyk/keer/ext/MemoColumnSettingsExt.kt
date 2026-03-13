package site.lcyk.keer.ext

import androidx.datastore.core.DataStore
import site.lcyk.keer.data.model.MemoColumnConfig
import site.lcyk.keer.data.model.Settings

fun Settings.currentUserColumns(): List<MemoColumnConfig> {
    return usersList
        .firstOrNull { user -> user.accountKey == currentUser }
        ?.settings
        ?.columns
        .orEmpty()
}

fun Settings.findCurrentUserColumn(columnId: String): MemoColumnConfig? {
    return currentUserColumns().firstOrNull { column -> column.id == columnId }
}

suspend fun DataStore<Settings>.updateCurrentUserColumns(
    transform: (List<MemoColumnConfig>) -> List<MemoColumnConfig>
) {
    updateData { existing ->
        val userIndex = existing.usersList.indexOfFirst { user ->
            user.accountKey == existing.currentUser
        }
        if (userIndex == -1) {
            return@updateData existing
        }
        val users = existing.usersList.toMutableList()
        val user = users[userIndex]
        users[userIndex] = user.copy(
            settings = user.settings.copy(columns = transform(user.settings.columns))
        )
        existing.copy(usersList = users)
    }
}
