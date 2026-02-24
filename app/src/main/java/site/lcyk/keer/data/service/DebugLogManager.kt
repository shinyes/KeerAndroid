package site.lcyk.keer.data.service

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import site.lcyk.keer.data.model.DebugLogCategory
import site.lcyk.keer.data.model.DebugLogEntry
import site.lcyk.keer.data.model.DebugLogLevel
import site.lcyk.keer.ext.settingsDataStore
import timber.log.Timber
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DebugLogManager @Inject constructor(
    @ApplicationContext context: Context
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val idGenerator = AtomicLong(0L)
    private val _logs = MutableStateFlow<List<DebugLogEntry>>(emptyList())
    val logs = _logs.asStateFlow()

    @Volatile
    private var appDebugLogEnabled = false

    @Volatile
    private var httpDebugLogEnabled = false

    init {
        scope.launch {
            context.settingsDataStore.data.collectLatest { settings ->
                appDebugLogEnabled = settings.appDebugLogEnabled
                httpDebugLogEnabled = settings.httpDebugLogEnabled
            }
        }
    }

    fun clear() {
        _logs.value = emptyList()
    }

    fun isHttpDebugLogEnabled(): Boolean = httpDebugLogEnabled

    fun logApp(priority: Int, tag: String?, message: String, throwable: Throwable?) {
        if (!appDebugLogEnabled) {
            return
        }
        val level = priority.toDebugLogLevel()
        val fullMessage = buildString {
            if (message.isNotBlank()) {
                append(message)
            }
            if (throwable != null) {
                if (isNotBlank()) {
                    append('\n')
                }
                append(Log.getStackTraceString(throwable))
            }
        }.ifBlank { "empty log message" }
        appendLog(
            category = DebugLogCategory.APP,
            level = level,
            tag = tag,
            message = fullMessage
        )
    }

    fun logHttp(
        level: DebugLogLevel,
        message: String
    ) {
        if (!httpDebugLogEnabled) {
            return
        }
        appendLog(
            category = DebugLogCategory.HTTP,
            level = level,
            tag = "HTTP",
            message = message
        )
    }

    private fun appendLog(
        category: DebugLogCategory,
        level: DebugLogLevel,
        tag: String?,
        message: String
    ) {
        val entry = DebugLogEntry(
            id = idGenerator.incrementAndGet(),
            timestampMillis = System.currentTimeMillis(),
            category = category,
            level = level,
            tag = tag,
            message = message.take(MAX_LOG_CHARS)
        )
        _logs.update { current ->
            val kept = if (current.size >= MAX_LOG_COUNT) {
                current.drop(current.size - MAX_LOG_COUNT + 1)
            } else {
                current
            }
            kept + entry
        }
    }

    private fun Int.toDebugLogLevel(): DebugLogLevel {
        return when (this) {
            Log.VERBOSE -> DebugLogLevel.VERBOSE
            Log.DEBUG -> DebugLogLevel.DEBUG
            Log.INFO -> DebugLogLevel.INFO
            Log.WARN -> DebugLogLevel.WARN
            Log.ERROR, Log.ASSERT -> DebugLogLevel.ERROR
            else -> DebugLogLevel.DEBUG
        }
    }

    companion object {
        private const val MAX_LOG_COUNT = 500
        private const val MAX_LOG_CHARS = 4000
    }
}

class DebugLogTree(
    private val debugLogManager: DebugLogManager
) : Timber.Tree() {
    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        debugLogManager.logApp(priority, tag, message, t)
    }
}

class DebugHttpLogInterceptor @Inject constructor(
    private val debugLogManager: DebugLogManager
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!debugLogManager.isHttpDebugLogEnabled()) {
            return chain.proceed(request)
        }

        val startNs = System.nanoTime()
        debugLogManager.logHttp(
            level = DebugLogLevel.INFO,
            message = buildRequestMessage(request)
        )

        return try {
            val response = chain.proceed(request)
            val durationMs = (System.nanoTime() - startNs) / 1_000_000
            debugLogManager.logHttp(
                level = if (response.isSuccessful) DebugLogLevel.INFO else DebugLogLevel.WARN,
                message = buildResponseMessage(request, response, durationMs)
            )
            response
        } catch (throwable: Throwable) {
            val durationMs = (System.nanoTime() - startNs) / 1_000_000
            debugLogManager.logHttp(
                level = DebugLogLevel.ERROR,
                message = "FAILED ${request.method} ${request.url} (${durationMs}ms)\n${throwable.message.orEmpty()}"
            )
            throw throwable
        }
    }

    private fun buildRequestMessage(request: Request): String {
        val headerText = request.headers.names()
            .sorted()
            .joinToString("\n") { name ->
                val value = if (name.equals("Authorization", ignoreCase = true)) {
                    "<redacted>"
                } else {
                    request.header(name).orEmpty()
                }
                "$name: $value"
            }
        return buildString {
            append("REQUEST ${request.method} ${request.url}\n")
            if (headerText.isNotBlank()) {
                append(headerText)
            }
        }
    }

    private fun buildResponseMessage(
        request: Request,
        response: Response,
        durationMs: Long
    ): String {
        val body = response.body
        val contentLength = body.contentLength().takeIf { it >= 0L }?.toString() ?: "unknown"
        val contentType = body.contentType()?.toString() ?: "unknown"
        return String.format(
            Locale.US,
            "RESPONSE %d %s %s %s (%dms)\ncontentType=%s contentLength=%s",
            response.code,
            response.message,
            request.method,
            request.url,
            durationMs,
            contentType,
            contentLength
        )
    }
}
