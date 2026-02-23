package me.mudkip.moememos.data.service

import me.mudkip.moememos.data.model.HttpLogEntry
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okio.Buffer
import java.time.Instant
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HttpLogInterceptor @Inject constructor(
    private val httpLogStore: HttpLogStore
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val startedAt = Instant.now()
        val startNanos = System.nanoTime()
        val requestHeaders = formatHeaders(request.headers)
        val requestBody = captureRequestBody(request)

        return try {
            val response = chain.proceed(request)
            httpLogStore.append(
                HttpLogEntry(
                    timestamp = startedAt,
                    method = request.method,
                    url = request.url.toString(),
                    requestHeaders = requestHeaders,
                    requestBody = requestBody,
                    responseCode = response.code,
                    responseMessage = response.message,
                    responseHeaders = formatHeaders(response.headers),
                    responseBody = captureResponseBody(response),
                    durationMs = elapsedMs(startNanos),
                )
            )
            response
        } catch (e: Exception) {
            httpLogStore.append(
                HttpLogEntry(
                    timestamp = startedAt,
                    method = request.method,
                    url = request.url.toString(),
                    requestHeaders = requestHeaders,
                    requestBody = requestBody,
                    durationMs = elapsedMs(startNanos),
                    error = e.localizedMessage ?: e.javaClass.simpleName,
                )
            )
            throw e
        }
    }

    private fun captureRequestBody(request: Request): String? {
        val body = request.body ?: return null
        if (request.url.encodedPath.contains("/attachments")) {
            return ATTACHMENT_BODY_OMITTED
        }

        val contentType = body.contentType()?.toString().orEmpty()
        if (!isTextContentType(contentType)) {
            return NON_TEXT_BODY_OMITTED
        }

        if (body.isDuplex() || body.isOneShot()) {
            return STREAMING_BODY_OMITTED
        }

        val contentLength = runCatching { body.contentLength() }.getOrDefault(-1L)
        if (contentLength > MAX_CAPTURE_BODY_BYTES) {
            return "Omitted request body ($contentLength bytes)"
        }
        if (contentLength < 0L) {
            return "Omitted request body (unknown size)"
        }

        return runCatching {
            val buffer = Buffer()
            body.writeTo(buffer)
            buffer.readUtf8().truncate(MAX_CAPTURE_BODY_CHARS)
        }.getOrElse {
            "Failed to capture request body: ${it.localizedMessage ?: it.javaClass.simpleName}"
        }
    }

    private fun captureResponseBody(response: Response): String? {
        val responseBody = response.body ?: return null
        val contentType = responseBody.contentType()?.toString().orEmpty()
        if (!isTextContentType(contentType)) {
            return NON_TEXT_BODY_OMITTED
        }
        return runCatching {
            response.peekBody(MAX_CAPTURE_BODY_BYTES.toLong())
                .string()
                .truncate(MAX_CAPTURE_BODY_CHARS)
        }.getOrElse {
            "Failed to capture response body: ${it.localizedMessage ?: it.javaClass.simpleName}"
        }
    }

    private fun formatHeaders(headers: Headers): String {
        if (headers.size == 0) {
            return ""
        }
        return buildString {
            for (i in 0 until headers.size) {
                val name = headers.name(i)
                val value = if (name.isSensitiveHeader()) {
                    "<redacted>"
                } else {
                    headers.value(i)
                }
                append(name).append(": ").append(value)
                if (i < headers.size - 1) {
                    append('\n')
                }
            }
        }
    }

    private fun isTextContentType(contentType: String): Boolean {
        if (contentType.isBlank()) {
            return true
        }
        val normalized = contentType.lowercase(Locale.US)
        return normalized.startsWith("text/") ||
            normalized.contains("json") ||
            normalized.contains("xml") ||
            normalized.contains("x-www-form-urlencoded") ||
            normalized.contains("html")
    }

    private fun elapsedMs(startNanos: Long): Long {
        return (System.nanoTime() - startNanos) / 1_000_000
    }

    private fun String.truncate(maxChars: Int): String {
        if (length <= maxChars) {
            return this
        }
        return take(maxChars) + "\n...[truncated]"
    }

    private fun String.isSensitiveHeader(): Boolean {
        return equals("Authorization", ignoreCase = true) ||
            equals("Cookie", ignoreCase = true) ||
            equals("Set-Cookie", ignoreCase = true)
    }

    companion object {
        private const val MAX_CAPTURE_BODY_BYTES = 16 * 1024L
        private const val MAX_CAPTURE_BODY_CHARS = 16 * 1024
        private const val ATTACHMENT_BODY_OMITTED = "Omitted attachment upload payload"
        private const val NON_TEXT_BODY_OMITTED = "Omitted non-text body"
        private const val STREAMING_BODY_OMITTED = "Omitted streaming request body"
    }
}

