package me.mudkip.moememos.data.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.mudkip.moememos.data.model.HttpLogEntry
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HttpLogStore @Inject constructor() {
    private val lock = Any()
    private var nextId = 1L
    private val logsBuffer = ArrayDeque<HttpLogEntry>()
    private val _logs = MutableStateFlow<List<HttpLogEntry>>(emptyList())
    val logs: StateFlow<List<HttpLogEntry>> = _logs.asStateFlow()

    fun append(log: HttpLogEntry) {
        synchronized(lock) {
            if (logsBuffer.size >= MAX_LOG_COUNT) {
                logsBuffer.removeFirst()
            }
            logsBuffer.addLast(log.copy(id = nextId++))
            _logs.value = logsBuffer.toList().asReversed()
        }
    }

    fun clear() {
        synchronized(lock) {
            logsBuffer.clear()
            _logs.value = emptyList()
        }
    }

    companion object {
        private const val MAX_LOG_COUNT = 300
    }
}

