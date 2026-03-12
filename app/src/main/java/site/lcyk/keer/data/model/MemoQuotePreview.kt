package site.lcyk.keer.data.model

import java.time.Instant

data class MemoQuotePreview(
    val previewText: String,
    val date: Instant? = null,
    val hasResources: Boolean = false,
)
