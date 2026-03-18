package site.lcyk.keer.data.service

import android.content.Context
import androidx.datastore.core.DataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import site.lcyk.keer.data.model.Account
import site.lcyk.keer.data.model.Settings
import site.lcyk.keer.data.model.UserData
import site.lcyk.keer.data.model.UserGeneralSettings
import site.lcyk.keer.data.model.UserSettings
import site.lcyk.keer.data.model.currentUserSettingsOrNull
import site.lcyk.keer.data.model.removeUserData
import site.lcyk.keer.data.model.updateCurrentUserData
import site.lcyk.keer.data.model.updateUserData
import site.lcyk.keer.data.model.upsertUserData
import site.lcyk.keer.data.model.userDataOrNull
import site.lcyk.keer.data.model.userSettingsOrNull
import site.lcyk.keer.ext.settingsDataStore

@Singleton
class AccountLocalSettingsStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val settingsDataStore: DataStore<Settings>
        get() = context.settingsDataStore

    suspend fun selectCurrentAccount(accountKey: String) {
        settingsDataStore.updateData { settings ->
            settings.copy(currentUser = accountKey)
        }
    }

    fun observeCurrentAccountKey(): Flow<String?> {
        return settingsDataStore.data.map { settings ->
            settings.currentUser.takeIf { it.isNotBlank() }
        }
    }

    fun observeCurrentUserSettings(): Flow<UserSettings?> {
        return settingsDataStore.data.map { settings ->
            settings.currentUserSettingsOrNull()
        }
    }

    fun observeCurrentGeneralSettings(): Flow<UserGeneralSettings> {
        return observeCurrentUserSettings().map { settings ->
            settings?.generalSettings ?: UserGeneralSettings()
        }
    }

    fun observeCurrentAvatarUri(): Flow<String> {
        return observeCurrentUserSettings().map { settings ->
            settings?.avatarUri.orEmpty()
        }
    }

    fun observeUserAvatarUri(accountKey: String): Flow<String> {
        return settingsDataStore.data.map { settings ->
            settings.userSettingsOrNull(accountKey)?.avatarUri.orEmpty()
        }
    }

    suspend fun upsertAccount(account: Account, makeCurrent: Boolean = true) {
        settingsDataStore.updateData { settings ->
            val currentSettings = settings.userSettingsOrNull(account.accountKey()) ?: UserSettings()
            settings.upsertUserData(account.toPersistedUserData(currentSettings)).let { updated ->
                if (makeCurrent) {
                    updated.copy(currentUser = account.accountKey())
                } else {
                    updated
                }
            }
        }
    }

    suspend fun removeAccount(accountKey: String) {
        settingsDataStore.updateData { settings ->
            val updated = settings.removeUserData(accountKey)
            val newCurrentUser = if (settings.currentUser == accountKey) {
                updated.usersList.firstOrNull()?.accountKey ?: ""
            } else {
                settings.currentUser
            }
            updated.copy(currentUser = newCurrentUser)
        }
    }

    suspend fun userData(accountKey: String): UserData? {
        return settingsDataStore.data.first().userDataOrNull(accountKey)
    }

    suspend fun userSettings(accountKey: String): UserSettings? {
        return settingsDataStore.data.first().userSettingsOrNull(accountKey)
    }

    suspend fun currentUserSettings(): UserSettings? {
        return settingsDataStore.data.first().currentUserSettingsOrNull()
    }

    suspend fun currentGeneralSettings(): UserGeneralSettings {
        return currentUserSettings()?.generalSettings ?: UserGeneralSettings()
    }

    suspend fun updateUserData(accountKey: String, transform: (UserData) -> UserData) {
        settingsDataStore.updateData { settings ->
            settings.updateUserData(accountKey, transform)
        }
    }

    suspend fun updateCurrentUserSettings(transform: (UserSettings) -> UserSettings) {
        settingsDataStore.updateData { settings ->
            settings.updateCurrentUserData { user ->
                user.copy(settings = transform(user.settings))
            }
        }
    }

    suspend fun updateCurrentGeneralSettings(value: UserGeneralSettings) {
        updateCurrentUserSettings { settings ->
            settings.copy(generalSettings = value)
        }
    }

    suspend fun markAvatarSyncPending(accountKey: String, avatarUri: String) {
        updateUserData(accountKey) { existing ->
            existing.copy(
                settings = existing.settings.copy(
                    avatarUri = avatarUri,
                    avatarSyncPending = true,
                )
            )
        }
    }

    suspend fun clearAvatarSyncPending(accountKey: String, updatedAccount: Account.KeerV2) {
        updateUserData(accountKey) { existing ->
            updatedAccount.toPersistedUserData(
                existing.settings.copy(avatarSyncPending = false)
            )
        }
    }

    suspend fun readMemoSyncAnchor(accountKey: String): Instant? {
        return userSettings(accountKey)
            ?.memoSyncAnchor
            .orEmpty()
            .trim()
            .takeIf(String::isNotEmpty)
            ?.let { raw -> runCatching { Instant.parse(raw) }.getOrNull() }
    }

    suspend fun writeMemoSyncAnchor(accountKey: String, anchor: Instant) {
        val normalizedAnchor = anchor.toString()
        updateUserData(accountKey) { target ->
            target.copy(settings = target.settings.copy(memoSyncAnchor = normalizedAnchor))
        }
    }

    suspend fun readMemoSyncCursor(accountKey: String): String? {
        val raw = userSettings(accountKey)
            ?.memoSyncAnchor
            .orEmpty()
            .trim()
        if (raw.isBlank()) {
            return null
        }
        return if (raw.all(Char::isDigit) && raw.toLongOrNull() != null) {
            raw
        } else {
            null
        }
    }

    suspend fun writeMemoSyncCursor(accountKey: String, cursor: String) {
        val normalizedCursor = cursor.trim()
            .takeIf { value -> value.isNotEmpty() && value.all(Char::isDigit) && value.toLongOrNull() != null }
            ?: "0"
        updateUserData(accountKey) { target ->
            target.copy(settings = target.settings.copy(memoSyncAnchor = normalizedCursor))
        }
    }

    suspend fun readUserSyncAnchor(accountKey: String): Instant? {
        return userSettings(accountKey)
            ?.userSyncAnchor
            .orEmpty()
            .trim()
            .takeIf(String::isNotEmpty)
            ?.let { raw -> runCatching { Instant.parse(raw) }.getOrNull() }
    }

    suspend fun writeUserSyncAnchor(accountKey: String, anchor: Instant) {
        val normalizedAnchor = anchor.toString()
        updateUserData(accountKey) { target ->
            target.copy(settings = target.settings.copy(userSyncAnchor = normalizedAnchor))
        }
    }

    suspend fun readSyncedUserIDs(accountKey: String): List<String> {
        return userSettings(accountKey)
            ?.syncedUserIds
            .orEmpty()
            .asSequence()
            .map { id -> id.trim() }
            .filter { id -> id.isNotEmpty() }
            .distinct()
            .toList()
    }

    suspend fun writeSyncedUserIDs(accountKey: String, userIDs: List<String>) {
        val normalizedUserIDs = userIDs
            .asSequence()
            .map { userID -> userID.trim() }
            .filter { userID -> userID.isNotEmpty() }
            .distinct()
            .toList()
        updateUserData(accountKey) { target ->
            target.copy(settings = target.settings.copy(syncedUserIds = normalizedUserIDs))
        }
    }

    private fun Account.toPersistedUserData(settings: UserSettings): UserData {
        return when (this) {
            is Account.KeerV2 -> UserData(
                settings = settings,
                accountKey = accountKey(),
                keerV2 = info.copy(
                    accessToken = "",
                    refreshToken = "",
                )
            )

            is Account.Local -> UserData(
                settings = settings,
                accountKey = accountKey(),
                local = info,
            )
        }
    }
}
