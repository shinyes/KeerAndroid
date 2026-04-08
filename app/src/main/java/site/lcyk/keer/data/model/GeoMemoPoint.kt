package site.lcyk.keer.data.model

import java.time.Instant

data class GeoMemoPoint(
    val identifier: String,
    val remoteId: String? = null,
    val latitude: Double,
    val longitude: Double,
    val date: Instant,
)
