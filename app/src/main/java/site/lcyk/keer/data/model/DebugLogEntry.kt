package site.lcyk.keer.data.model

enum class DebugLogCategory {
    APP,
    HTTP
}

enum class DebugLogLevel {
    VERBOSE,
    DEBUG,
    INFO,
    WARN,
    ERROR
}

data class DebugLogEntry(
    val id: Long,
    val timestampMillis: Long,
    val category: DebugLogCategory,
    val level: DebugLogLevel,
    val tag: String?,
    val message: String
)
