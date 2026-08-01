package site.lcyk.keer.data.repository

import kotlinx.serialization.Serializable
import java.time.Instant


@Serializable
internal data class RemoteMemoPayload(
    val content: String,
    val tags: List<String> = emptyList(),
    val latitude: Double? = null,
    val longitude: Double? = null,
)

@Serializable
internal data class RemoteEncryptedPayload(
    val version: Int = 1,
    val algorithm: String = "AES_GCM_PAYLOAD_V1",
    val iv: String,
    val ciphertext: String,
)

@Serializable
internal data class RemoteAttachmentDescriptor(
    val filename: String,
    val originalMimeType: String? = null,
    val thumbnailMimeType: String? = null,
)

internal data class DecodedMemoPayload(
    val content: String,
    val tags: List<String>,
    val latitude: Double?,
    val longitude: Double?,
)

internal data class QuotePreviewSnapshot(
    val status: String,
    val contentPreview: String?,
    val date: Instant?,
    val hasAttachments: Boolean,
)
