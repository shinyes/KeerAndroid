package site.lcyk.keer.data.local.entity

import site.lcyk.keer.data.model.GeoMemoPoint
import java.time.Instant

data class MemoGeoPointEntity(
    val identifier: String,
    val remoteId: String? = null,
    val latitude: Double,
    val longitude: Double,
    val date: Instant,
)

fun MemoGeoPointEntity.toGeoMemoPoint(): GeoMemoPoint {
    return GeoMemoPoint(
        identifier = identifier,
        remoteId = remoteId,
        latitude = latitude,
        longitude = longitude,
        date = date,
    )
}
