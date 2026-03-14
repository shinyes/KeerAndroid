package site.lcyk.keer.data.service

import android.content.Context
import androidx.datastore.core.DataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import site.lcyk.keer.data.model.DebugLogSettings
import site.lcyk.keer.data.model.Settings
import site.lcyk.keer.ext.settingsDataStore

@Singleton
class AppPreferencesStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val settingsDataStore: DataStore<Settings>
        get() = context.settingsDataStore

    fun observeDebugLogSettings(): Flow<DebugLogSettings> {
        return settingsDataStore.data.map { settings ->
            DebugLogSettings(
                appDebugLogEnabled = settings.appDebugLogEnabled,
                httpDebugLogEnabled = settings.httpDebugLogEnabled,
            )
        }
    }

    suspend fun setAppDebugLogEnabled(enabled: Boolean) {
        settingsDataStore.updateData { current ->
            current.copy(appDebugLogEnabled = enabled)
        }
    }

    suspend fun setHttpDebugLogEnabled(enabled: Boolean) {
        settingsDataStore.updateData { current ->
            current.copy(httpDebugLogEnabled = enabled)
        }
    }
}
