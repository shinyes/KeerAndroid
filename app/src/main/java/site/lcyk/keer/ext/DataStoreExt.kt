package site.lcyk.keer.ext

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.dataStore
import site.lcyk.keer.data.model.Settings
import site.lcyk.keer.util.SettingsSerializer

val Context.settingsDataStore: DataStore<Settings> by dataStore(
    fileName = "settings_v2.json",
    serializer = SettingsSerializer
)
