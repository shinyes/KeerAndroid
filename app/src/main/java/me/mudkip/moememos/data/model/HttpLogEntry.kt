package me.mudkip.moememos.data.model

import java.time.Instant

data class HttpLogEntry(
    val id: Long = 0L,
    val timestamp: Instant = Instant.now(),
    val method: String,
    val url: String,
    val requestHeaders: String = "",
    val requestBody: String? = null,
    val responseCode: Int? = null,
    val responseMessage: String? = null,
    val responseHeaders: String? = null,
    val responseBody: String? = null,
    val durationMs: Long? = null,
    val error: String? = null,
)

