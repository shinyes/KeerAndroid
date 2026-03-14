package site.lcyk.keer.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import site.lcyk.keer.data.model.DebugLogSettings
import site.lcyk.keer.data.service.AppPreferencesStore
import site.lcyk.keer.data.service.DebugLogManager
import javax.inject.Inject

@HiltViewModel
class DebugLogViewModel @Inject constructor(
    private val debugLogManager: DebugLogManager,
    private val appPreferencesStore: AppPreferencesStore,
) : ViewModel() {
    val logs = debugLogManager.logs.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList()
    )

    val settings = appPreferencesStore.observeDebugLogSettings()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = DebugLogSettings()
        )

    fun setAppDebugLogEnabled(enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            appPreferencesStore.setAppDebugLogEnabled(enabled)
        }
    }

    fun setHttpDebugLogEnabled(enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            appPreferencesStore.setHttpDebugLogEnabled(enabled)
        }
    }

    fun clearLogs() {
        debugLogManager.clear()
    }
}
