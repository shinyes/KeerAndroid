package site.lcyk.keer.data.model

import kotlinx.serialization.Serializable

@Serializable
data class ErrorMessage(
    val code: String? = null,
    val error: String? = null,
    val requestId: String? = null,
    val message: String
)
