package site.lcyk.keer.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import site.lcyk.keer.data.service.DebugLogManager
import site.lcyk.keer.ext.settingsDataStore
import javax.inject.Inject


data class DebugLogSettings(
    val appDebugLogEnabled: Boolean = false,
    val httpDebugLogEnabled: Boolean = false
)

@HiltViewModel
class DebugLogViewModel @Inject constructor(
    private val debugLogManager: DebugLogManager,
    @param:ApplicationContext private val context: Context
) : ViewModel() {
    val logs = debugLogManager.logs.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList()
    )

    val settings = context.settingsDataStore.data
        .map {
            DebugLogSettings(
                appDebugLogEnabled = it.appDebugLogEnabled,
                httpDebugLogEnabled = it.httpDebugLogEnabled
            )
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = DebugLogSettings()
        )

    fun setAppDebugLogEnabled(enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            context.settingsDataStore.updateData { current ->
                current.copy(appDebugLogEnabled = enabled)
            }
        }
    }

    fun setHttpDebugLogEnabled(enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            context.settingsDataStore.updateData { current ->
                current.copy(httpDebugLogEnabled = enabled)
            }
        }
    }

    fun clearLogs() {
        debugLogManager.clear()
    }
}
