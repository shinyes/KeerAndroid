package site.lcyk.keer.util

import java.time.Instant
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import site.lcyk.keer.viewmodel.GeoHeatBucket
import site.lcyk.keer.viewmodel.GlobalHeatmapViewport

data class ProjectedScreenPoint(
    val x: Float,
    val y: Float,
)

data class ProjectedMemoPoint(
    val identifier: String,
    val latitude: Double,
    val longitude: Double,
    val x: Float,
    val y: Float,
    val date: Instant,
)

/**
 * 缓存的 Web-Mercator 归一化坐标（viewport 无关）。
 * 投影到屏幕只需一次便宜线性映射，避免每次拖拽/缩放对每个点重算 sin/ln。
 */
data class NormalizedMemoPoint(
    val identifier: String,
    val latitude: Double,
    val longitude: Double,
    val xNorm: Double,
    val yNorm: Double,
    val date: Instant,
)

fun normalizeGeoPoints(points: List<DisplayGeoMemoPoint>): List<NormalizedMemoPoint> {
    if (points.isEmpty()) {
        return emptyList()
    }
    return points.map { point ->
        val normalized = latLonToNormalized(point.latitude, point.longitude)
        NormalizedMemoPoint(
            identifier = point.identifier,
            latitude = point.latitude,
            longitude = point.longitude,
            xNorm = normalized.x,
            yNorm = normalized.y,
            date = point.date,
        )
    }
}

private data class NormalizedMercatorPoint(
    val x: Double,
    val y: Double,
)

private data class BucketAccumulator(
    var sumX: Double,
    var sumY: Double,
    var count: Int,
    var earliestDate: Instant,
    var latestDate: Instant,
    val members: MutableList<ProjectedMemoPoint>,
)

const val GLOBAL_HEATMAP_MIN_ZOOM = 2.8
const val GLOBAL_HEATMAP_MAX_ZOOM = 9.2
const val DEFAULT_GLOBAL_HEATMAP_CENTER_LATITUDE = 35.6
const val DEFAULT_GLOBAL_HEATMAP_CENTER_LONGITUDE = 104.2
const val DEFAULT_GLOBAL_HEATMAP_ZOOM = 4.15

private const val WEB_MERCATOR_MAX_LATITUDE = 85.05112878
private const val WORLD_TILE_SIZE = 256.0
private const val LOG_2 = 0.6931471805599453
private val MIN_NORMALIZED_Y = latitudeToNormalizedY(WEB_MERCATOR_MAX_LATITUDE)
private val MAX_NORMALIZED_Y = latitudeToNormalizedY(-WEB_MERCATOR_MAX_LATITUDE)

fun defaultGlobalHeatmapViewport(
    widthPx: Int = 0,
    heightPx: Int = 0,
): GlobalHeatmapViewport {
    return GlobalHeatmapViewport(
        widthPx = widthPx,
        heightPx = heightPx,
        centerLatitude = DEFAULT_GLOBAL_HEATMAP_CENTER_LATITUDE,
        centerLongitude = DEFAULT_GLOBAL_HEATMAP_CENTER_LONGITUDE,
        zoom = DEFAULT_GLOBAL_HEATMAP_ZOOM,
    )
}

fun buildProjectedMemoPoints(
    points: List<NormalizedMemoPoint>,
    viewport: GlobalHeatmapViewport,
): List<ProjectedMemoPoint> {
    if (points.isEmpty() || !viewport.hasSize()) {
        return emptyList()
    }
    val center = latLonToNormalized(
        latitude = viewport.centerLatitude,
        longitude = viewport.centerLongitude,
    )
    val scalePx = viewportScalePx(viewport.zoom)
    return points.map { point ->
        val deltaX = shortestWrappedDelta(point.xNorm, center.x)
        val deltaY = point.yNorm - center.y
        ProjectedMemoPoint(
            identifier = point.identifier,
            latitude = point.latitude,
            longitude = point.longitude,
            x = (viewport.widthPx / 2.0 + deltaX * scalePx).toFloat(),
            y = (viewport.heightPx / 2.0 + deltaY * scalePx).toFloat(),
            date = point.date,
        )
    }
}

fun buildGeoHeatBuckets(
    points: List<NormalizedMemoPoint>,
    viewport: GlobalHeatmapViewport,
): List<GeoHeatBucket> {
    return buildGeoHeatBucketsFromProjectedPoints(
        points = buildProjectedMemoPoints(points, viewport),
        widthPx = viewport.widthPx,
        heightPx = viewport.heightPx,
        zoom = viewport.zoom,
    )
}

fun buildGeoHeatBucketsFromProjectedPoints(
    points: List<ProjectedMemoPoint>,
    widthPx: Int,
    heightPx: Int,
    zoom: Double,
): List<GeoHeatBucket> {
    if (points.isEmpty() || widthPx <= 0 || heightPx <= 0) {
        return emptyList()
    }

    val cellSizePx = resolveBucketCellSizePx(zoom)
    val visibilityMarginPx = cellSizePx * 2.05f
    val bucketsByCell = LinkedHashMap<Pair<Int, Int>, BucketAccumulator>()

    points.forEach { point ->
        if (
            point.x < -visibilityMarginPx ||
            point.x > widthPx + visibilityMarginPx ||
            point.y < -visibilityMarginPx ||
            point.y > heightPx + visibilityMarginPx
        ) {
            return@forEach
        }
        val cellKey = (point.x / cellSizePx).toInt() to (point.y / cellSizePx).toInt()
        val existing = bucketsByCell[cellKey]
        if (existing == null) {
            bucketsByCell[cellKey] = BucketAccumulator(
                sumX = point.x.toDouble(),
                sumY = point.y.toDouble(),
                count = 1,
                earliestDate = point.date,
                latestDate = point.date,
                members = mutableListOf(point),
            )
        } else {
            existing.sumX += point.x
            existing.sumY += point.y
            existing.count += 1
            if (point.date.isBefore(existing.earliestDate)) {
                existing.earliestDate = point.date
            }
            if (point.date.isAfter(existing.latestDate)) {
                existing.latestDate = point.date
            }
            existing.members += point
        }
    }

    if (bucketsByCell.isEmpty()) {
        return emptyList()
    }

    val maxBucketCount = bucketsByCell.values.maxOf { bucket -> bucket.count }.coerceAtLeast(1)
    return bucketsByCell.entries.map { (cellKey, bucket) ->
        val averageX = (bucket.sumX / bucket.count).toFloat()
        val averageY = (bucket.sumY / bucket.count).toFloat()
        val anchorPoint = bucket.members.minByOrNull { member ->
            val dx = member.x - averageX
            val dy = member.y - averageY
            dx * dx + dy * dy
        } ?: bucket.members.first()
        val count = bucket.count
        val normalizedCount = (count.toFloat() / maxBucketCount.toFloat()).coerceIn(0f, 1f)
        val intensity = (0.12f + normalizedCount.pow(0.72f) * 0.88f).coerceIn(0.16f, 1f)
        val radiusPx = (
            cellSizePx * 0.72 +
                ln(count.toDouble() + 1.0) * 12.5
            ).coerceIn(cellSizePx * 0.62, cellSizePx * 1.92)
            .toFloat()

        GeoHeatBucket(
            key = "${cellKey.first}:${cellKey.second}",
            centerX = averageX,
            centerY = averageY,
            anchorLatitude = anchorPoint.latitude,
            anchorLongitude = anchorPoint.longitude,
            memoCount = count,
            intensity = intensity,
            radiusPx = radiusPx,
            referenceZoom = zoom,
            earliestDate = bucket.earliestDate,
            latestDate = bucket.latestDate,
        )
    }.sortedBy { bucket -> bucket.memoCount }
}

fun projectBucketsToScreen(
    buckets: List<GeoHeatBucket>,
    viewport: GlobalHeatmapViewport,
): List<GeoHeatBucket> {
    if (buckets.isEmpty()) {
        return emptyList()
    }
    return buckets.mapNotNull { bucket ->
        projectCoordinateToScreen(
            viewport = viewport,
            latitude = bucket.anchorLatitude,
            longitude = bucket.anchorLongitude,
        )?.let { projected ->
            val radiusScale = 2.0.pow(viewport.zoom - bucket.referenceZoom).toFloat()
                .coerceIn(0.35f, 3.2f)
            bucket.copy(
                centerX = projected.x,
                centerY = projected.y,
                radiusPx = (bucket.radiusPx * radiusScale).coerceIn(14f, 164f),
            )
        }
    }
}

fun projectBucketsToScreen(
    buckets: List<GeoHeatBucket>,
    currentZoom: Double,
    projector: (latitude: Double, longitude: Double) -> ProjectedScreenPoint?,
): List<GeoHeatBucket> {
    if (buckets.isEmpty()) {
        return emptyList()
    }
    return buckets.mapNotNull { bucket ->
        val projected = projector(bucket.anchorLatitude, bucket.anchorLongitude) ?: return@mapNotNull null
        val radiusScale = 2.0.pow(currentZoom - bucket.referenceZoom).toFloat()
            .coerceIn(0.35f, 3.2f)
        bucket.copy(
            centerX = projected.x,
            centerY = projected.y,
            radiusPx = (bucket.radiusPx * radiusScale).coerceIn(14f, 164f),
        )
    }
}

fun projectCoordinateToScreen(
    viewport: GlobalHeatmapViewport,
    latitude: Double,
    longitude: Double,
): ProjectedScreenPoint? {
    if (!viewport.hasSize()) {
        return null
    }
    val center = latLonToNormalized(
        latitude = viewport.centerLatitude,
        longitude = viewport.centerLongitude,
    )
    val point = latLonToNormalized(
        latitude = latitude,
        longitude = longitude,
    )
    val scalePx = viewportScalePx(viewport.zoom)
    val deltaX = shortestWrappedDelta(point.x, center.x)
    val deltaY = point.y - center.y
    return ProjectedScreenPoint(
        x = (viewport.widthPx / 2.0 + deltaX * scalePx).toFloat(),
        y = (viewport.heightPx / 2.0 + deltaY * scalePx).toFloat(),
    )
}

fun projectCoordinateToScreenWrapped(
    viewport: GlobalHeatmapViewport,
    latitude: Double,
    longitude: Double,
    wrapOffsetWorlds: Int,
): ProjectedScreenPoint? {
    if (!viewport.hasSize()) {
        return null
    }
    val center = latLonToNormalized(
        latitude = viewport.centerLatitude,
        longitude = viewport.centerLongitude,
    )
    val point = latLonToNormalized(
        latitude = latitude,
        longitude = longitude,
    )
    val scalePx = viewportScalePx(viewport.zoom)
    val deltaX = point.x + wrapOffsetWorlds - center.x
    val deltaY = point.y - center.y
    return ProjectedScreenPoint(
        x = (viewport.widthPx / 2.0 + deltaX * scalePx).toFloat(),
        y = (viewport.heightPx / 2.0 + deltaY * scalePx).toFloat(),
    )
}

fun viewportWorldWidthPx(viewport: GlobalHeatmapViewport): Float {
    return viewportScalePx(viewport.zoom).toFloat()
}

fun wrapScreenXNear(
    viewport: GlobalHeatmapViewport,
    candidateX: Float,
    referenceX: Float,
): Float {
    val worldWidth = viewportWorldWidthPx(viewport)
    if (worldWidth <= 0f) {
        return candidateX
    }
    var adjusted = candidateX
    while (adjusted - referenceX > worldWidth / 2f) {
        adjusted -= worldWidth
    }
    while (adjusted - referenceX < -worldWidth / 2f) {
        adjusted += worldWidth
    }
    return adjusted
}

fun panViewport(
    viewport: GlobalHeatmapViewport,
    panXPx: Float,
    panYPx: Float,
): GlobalHeatmapViewport {
    if (!viewport.hasSize()) {
        return viewport
    }
    val center = latLonToNormalized(
        latitude = viewport.centerLatitude,
        longitude = viewport.centerLongitude,
    )
    val scalePx = viewportScalePx(viewport.zoom)
    val nextCenter = NormalizedMercatorPoint(
        x = wrapUnit(center.x - panXPx / scalePx),
        y = (center.y - panYPx / scalePx).coerceIn(MIN_NORMALIZED_Y, MAX_NORMALIZED_Y),
    )
    return viewport.copy(
        centerLatitude = normalizedYToLatitude(nextCenter.y),
        centerLongitude = normalizedXToLongitude(nextCenter.x),
    )
}

fun scaleViewportAround(
    viewport: GlobalHeatmapViewport,
    zoomScaleFactor: Float,
    focusX: Float,
    focusY: Float,
): GlobalHeatmapViewport {
    if (!viewport.hasSize()) {
        return viewport
    }
    val safeScale = zoomScaleFactor.toDouble().coerceIn(0.6, 1.8)
    val zoomDelta = ln(safeScale) / LOG_2
    val nextZoom = (viewport.zoom + zoomDelta).coerceIn(GLOBAL_HEATMAP_MIN_ZOOM, GLOBAL_HEATMAP_MAX_ZOOM)
    if (nextZoom == viewport.zoom) {
        return viewport
    }
    val focus = screenPointToNormalized(
        viewport = viewport,
        x = focusX,
        y = focusY,
    )
    val nextScalePx = viewportScalePx(nextZoom)
    val nextCenter = NormalizedMercatorPoint(
        x = wrapUnit(focus.x - (focusX - viewport.widthPx / 2f) / nextScalePx),
        y = (
            focus.y - (focusY - viewport.heightPx / 2f) / nextScalePx
            ).coerceIn(MIN_NORMALIZED_Y, MAX_NORMALIZED_Y),
    )
    return viewport.copy(
        centerLatitude = normalizedYToLatitude(nextCenter.y),
        centerLongitude = normalizedXToLongitude(nextCenter.x),
        zoom = nextZoom,
    )
}

fun findGeoHeatBucketHit(
    buckets: List<GeoHeatBucket>,
    x: Float,
    y: Float,
): GeoHeatBucket? {
    return buckets
        .asSequence()
        .filter { bucket ->
            val dx = x - bucket.centerX
            val dy = y - bucket.centerY
            dx * dx + dy * dy <= bucket.radiusPx * bucket.radiusPx
        }
        .maxWithOrNull(
            compareBy<GeoHeatBucket> { bucket -> bucket.memoCount }
                .thenByDescending { bucket -> bucket.intensity }
        )
}

private fun screenPointToNormalized(
    viewport: GlobalHeatmapViewport,
    x: Float,
    y: Float,
): NormalizedMercatorPoint {
    val center = latLonToNormalized(
        latitude = viewport.centerLatitude,
        longitude = viewport.centerLongitude,
    )
    val scalePx = viewportScalePx(viewport.zoom)
    return NormalizedMercatorPoint(
        x = wrapUnit(center.x + (x - viewport.widthPx / 2f) / scalePx),
        y = (center.y + (y - viewport.heightPx / 2f) / scalePx).coerceIn(MIN_NORMALIZED_Y, MAX_NORMALIZED_Y),
    )
}

private fun latLonToNormalized(
    latitude: Double,
    longitude: Double,
): NormalizedMercatorPoint {
    val clampedLatitude = latitude.coerceIn(-WEB_MERCATOR_MAX_LATITUDE, WEB_MERCATOR_MAX_LATITUDE)
    return NormalizedMercatorPoint(
        x = wrapUnit((longitude + 180.0) / 360.0),
        y = latitudeToNormalizedY(clampedLatitude),
    )
}

private fun latitudeToNormalizedY(latitude: Double): Double {
    val radians = Math.toRadians(latitude.coerceIn(-WEB_MERCATOR_MAX_LATITUDE, WEB_MERCATOR_MAX_LATITUDE))
    return 0.5 - ln((1.0 + sin(radians)) / (1.0 - sin(radians))) / (4.0 * PI)
}

private fun normalizedYToLatitude(normalizedY: Double): Double {
    val mercator = PI * (1.0 - 2.0 * normalizedY)
    return Math.toDegrees(asin((exp(mercator) - exp(-mercator)) / (exp(mercator) + exp(-mercator))))
        .coerceIn(-WEB_MERCATOR_MAX_LATITUDE, WEB_MERCATOR_MAX_LATITUDE)
}

private fun normalizedXToLongitude(normalizedX: Double): Double {
    return wrapUnit(normalizedX) * 360.0 - 180.0
}

private fun viewportScalePx(zoom: Double): Double {
    return WORLD_TILE_SIZE * 2.0.pow(zoom)
}

private fun shortestWrappedDelta(
    target: Double,
    center: Double,
): Double {
    var delta = target - center
    if (delta < -0.5) {
        delta += 1.0
    } else if (delta > 0.5) {
        delta -= 1.0
    }
    return delta
}

private fun wrapUnit(value: Double): Double {
    var wrapped = value - floor(value)
    if (wrapped < 0.0) {
        wrapped += 1.0
    }
    return wrapped
}

private fun resolveBucketCellSizePx(zoom: Double): Float {
    val relativeZoom = 2.0.pow(zoom - DEFAULT_GLOBAL_HEATMAP_ZOOM)
    return (72.0 / relativeZoom).coerceIn(22.0, 84.0).toFloat()
}
