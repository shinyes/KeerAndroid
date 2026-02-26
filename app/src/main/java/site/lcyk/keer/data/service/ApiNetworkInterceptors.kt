package site.lcyk.keer.data.service

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import kotlin.math.min

class ApiRequestMetadataInterceptor @Inject constructor(
    @param:ApplicationContext private val context: Context
) : Interceptor {

    private val appVersion: String by lazy {
        resolveAppVersion(context)
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!request.url.encodedPath.startsWith("/api/")) {
            return chain.proceed(request)
        }

        val builder = request.newBuilder()
            .header("X-Client-Platform", "android")
            .header("X-Client-Version", appVersion)
            .header("User-Agent", "KeerAndroid/$appVersion (Android)")

        if (request.header("Accept").isNullOrBlank()) {
            builder.header("Accept", "application/json")
        }
        if (request.header("X-Request-ID").isNullOrBlank()) {
            builder.header("X-Request-ID", UUID.randomUUID().toString())
        }

        return chain.proceed(builder.build())
    }

    @Suppress("DEPRECATION")
    private fun resolveAppVersion(context: Context): String {
        return runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: "unknown"
    }
}

class ApiRetryInterceptor @Inject constructor() : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        var attempt = 0
        var delayMillis = INITIAL_DELAY_MILLIS
        val request = chain.request()

        while (true) {
            try {
                val response = chain.proceed(request)
                if (!shouldRetryResponse(request, response, attempt)) {
                    return response
                }
                response.close()
            } catch (e: IOException) {
                if (!shouldRetryException(request, attempt)) {
                    throw e
                }
            }

            attempt += 1
            safeSleep(delayMillis)
            delayMillis = min(delayMillis * 2, MAX_DELAY_MILLIS)
        }
    }

    private fun shouldRetryResponse(request: Request, response: Response, attempt: Int): Boolean {
        if (!isRetryableMethod(request.method)) {
            return false
        }
        if (attempt >= MAX_RETRY_COUNT) {
            return false
        }
        return RETRYABLE_STATUS_CODES.contains(response.code)
    }

    private fun shouldRetryException(request: Request, attempt: Int): Boolean {
        return isRetryableMethod(request.method) && attempt < MAX_RETRY_COUNT
    }

    private fun isRetryableMethod(method: String): Boolean {
        return RETRYABLE_METHODS.contains(method.uppercase())
    }

    private fun safeSleep(delayMillis: Long) {
        try {
            Thread.sleep(delayMillis)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private companion object {
        private val RETRYABLE_METHODS = setOf("GET", "HEAD", "OPTIONS")
        private val RETRYABLE_STATUS_CODES = setOf(408, 429, 500, 502, 503, 504)
        private const val MAX_RETRY_COUNT = 2
        private const val INITIAL_DELAY_MILLIS = 250L
        private const val MAX_DELAY_MILLIS = 1200L
    }
}
