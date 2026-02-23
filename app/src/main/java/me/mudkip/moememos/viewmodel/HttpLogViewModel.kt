package me.mudkip.moememos.viewmodel

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import me.mudkip.moememos.data.model.HttpLogEntry
import me.mudkip.moememos.data.service.HttpLogStore
import javax.inject.Inject

@HiltViewModel
class HttpLogViewModel @Inject constructor(
    private val httpLogStore: HttpLogStore
) : ViewModel() {
    val logs: StateFlow<List<HttpLogEntry>> = httpLogStore.logs

    fun clearLogs() {
        httpLogStore.clear()
    }
}

