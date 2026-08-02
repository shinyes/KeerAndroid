package site.lcyk.keer.viewmodel

import java.time.Instant
import site.lcyk.keer.util.DisplayGeoMemoPoint
import site.lcyk.keer.util.NormalizedMemoPoint

data class GlobalHeatmapViewport(
    val widthPx: Int = 0,
    val heightPx: Int = 0,
    val centerLatitude: Double = 20.0,
    val centerLongitude: Double = 108.0,
    val zoom: Double = 3.2,
) {
    fun hasSize(): Boolean = widthPx > 0 && heightPx > 0
}

data class GeoHeatBucket(
    val key: String,
    val centerX: Float,
    val centerY: Float,
    val anchorLatitude: Double,
    val anchorLongitude: Double,
    val memoCount: Int,
    val intensity: Float,
    val radiusPx: Float,
    val referenceZoom: Double,
    val earliestDate: Instant,
    val latestDate: Instant,
)

data class GeoHeatHotspotHint(
    val bucketKey: String,
    val anchorX: Float,
    val anchorY: Float,
    val memoCount: Int,
    val intensity: Float,
    val earliestDate: Instant,
    val latestDate: Instant,
)

data class GlobalHeatmapUiState(
    val viewport: GlobalHeatmapViewport = GlobalHeatmapViewport(),
    val points: List<DisplayGeoMemoPoint> = emptyList(),
    val normalizedPoints: List<NormalizedMemoPoint> = emptyList(),
    val memoPointCount: Int = 0,
    val loading: Boolean = true,
)
