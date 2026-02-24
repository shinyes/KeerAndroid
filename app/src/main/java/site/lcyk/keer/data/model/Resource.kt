package site.lcyk.keer.data.model

import java.time.Instant

data class Resource(
    override val remoteId: String,
    override val date: Instant,
    override val filename: String,
    override val mimeType: String? = null,
    override val uri: String,
    override val localUri: String? = null,
    override val thumbnailUri: String? = null,
    override val thumbnailLocalUri: String? = null,
) : ResourceRepresentable
