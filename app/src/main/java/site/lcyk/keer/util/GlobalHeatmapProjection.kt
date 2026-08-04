package site.lcyk.keer.util

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.time.Instant
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
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

fun projectNormalizedToScreen(
    viewport: GlobalHeatmapViewport,
    normX: Double,
    normY: Double,
): ProjectedScreenPoint? {
    if (!viewport.hasSize()) {
        return null
    }
    val center = latLonToNormalized(
        latitude = viewport.centerLatitude,
        longitude = viewport.centerLongitude,
    )
    val scalePx = viewportScalePx(viewport.zoom)
    val deltaX = shortestWrappedDelta(normX, center.x)
    val deltaY = normY - center.y
    return ProjectedScreenPoint(
        x = (viewport.widthPx / 2.0 + deltaX * scalePx).toFloat(),
        y = (viewport.heightPx / 2.0 + deltaY * scalePx).toFloat(),
    )
}

/** Web-Mercator 归一化坐标（世界 0..1）。 */
data class NormalizedCoordinate(
    val x: Double,
    val y: Double,
)

/** 经纬度 → 归一化坐标（底图加载时一次性缓存用，避免每帧重算 sin/ln）。 */
fun toNormalizedCoordinate(
    latitude: Double,
    longitude: Double,
): NormalizedCoordinate {
    val point = latLonToNormalized(latitude, longitude)
    return NormalizedCoordinate(point.x, point.y)
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

/**
 * 地理对齐的密度场纹理：在锚点 viewport 的屏幕空间（含边距）栅格化 KDE 位图。
 * 因为归一化坐标→屏幕是纯线性映射（scale + translate），
 * 拖拽/缩放时只需把这张位图对当前 viewport 做一次仿射变换即可渲染，
 * 热力与底图严格同步、无需每帧重建，也不会有异步分桶导致的抖动。
 */
data class HeatDensityField(
    val anchorViewport: GlobalHeatmapViewport,
    val image: ImageBitmap,
    /** 锚点缩放下每归一化单位的屏幕像素数（= 世界宽度的像素数）。 */
    val anchorScalePx: Double,
    /** 位图左上角对应的归一化坐标。 */
    val topLeftXNorm: Double,
    val topLeftYNorm: Double,
)

fun buildHeatDensityField(
    points: List<NormalizedMemoPoint>,
    viewport: GlobalHeatmapViewport,
    widthPx: Int,
    heightPx: Int,
    marginFactor: Float = 1.6f,
    downsample: Int = 4,
): HeatDensityField? {
    if (points.isEmpty() || widthPx <= 0 || heightPx <= 0 || !viewport.hasSize()) {
        return null
    }
    val anchorScalePx = viewportScalePx(viewport.zoom)
    val bw = max(1, (widthPx * marginFactor).roundToInt())
    val bh = max(1, (heightPx * marginFactor).roundToInt())
    val center = latLonToNormalized(viewport.centerLatitude, viewport.centerLongitude)
    // 用放大的伪 viewport 分桶：锚点中心仍在 (bw/2, bh/2)，bucket 位于 [0,bw]×[0,bh]。
    val buckets = buildGeoHeatBuckets(
        points = points,
        viewport = viewport.copy(widthPx = bw, heightPx = bh),
    )
    return HeatDensityField(
        anchorViewport = viewport,
        image = buildHeatDensityImage(
            buckets = buckets,
            widthPx = bw,
            heightPx = bh,
            downsample = downsample,
        ),
        anchorScalePx = anchorScalePx,
        // 纹理左上角 = 锚点中心向西北移半幅（bw/2 锚点屏幕像素），即锚点屏幕坐标 -（bw/2 - widthPx/2）。
        topLeftXNorm = center.x - (bw / 2.0) / anchorScalePx,
        topLeftYNorm = center.y - (bh / 2.0) / anchorScalePx,
    )
}

/**
 * 把桶列表渲染为低分辨率密度场位图（KDE 高斯累加），按密度映射"绿→黄→红"。
 * 桶的 centerX/centerY 位于 [0,widthPx]×[0,heightPx] 的栅格坐标空间。
 */
internal fun buildHeatDensityImage(
    buckets: List<GeoHeatBucket>,
    widthPx: Int,
    heightPx: Int,
    downsample: Int = 4,
): ImageBitmap {
    val gw = max(1, widthPx / downsample)
    val gh = max(1, heightPx / downsample)
    val density = FloatArray(gw * gh)
    val sigma = 3.0f
    val radius = (sigma * 2.6f).toInt().coerceAtLeast(1)
    for (bucket in buckets) {
        val cx = (bucket.centerX / downsample).toInt()
        val cy = (bucket.centerY / downsample).toInt()
        // sqrt 压缩 memoCount 差异，避免少数高密度桶主导归一化。
        val weight = sqrt(bucket.memoCount.toFloat())
        for (dy in -radius..radius) {
            for (dx in -radius..radius) {
                val d2 = dx * dx + dy * dy
                if (d2 > radius * radius) continue
                val gauss = exp(-d2 / (2f * sigma * sigma)) * weight
                val gx = cx + dx
                val gy = cy + dy
                if (gx in 0 until gw && gy in 0 until gh) {
                    density[gy * gw + gx] += gauss
                }
            }
        }
    }
    val maxDensity = density.maxOrNull() ?: 0f
    val bitmap = Bitmap.createBitmap(gw, gh, Bitmap.Config.ARGB_8888)
    if (maxDensity <= 0f) {
        return bitmap.asImageBitmap()
    }
    val pixels = IntArray(gw * gh)
    for (i in density.indices) {
        // sqrt 提升中间密度：让更多区域进入绿→黄→红区间，避免全绿。
        val raw = (density[i] / maxDensity).coerceIn(0f, 1f)
        pixels[i] = heatColor(sqrt(raw))
    }
    bitmap.setPixels(pixels, 0, gw, 0, 0, gw, gh)
    return bitmap.asImageBitmap()
}

internal fun heatColor(t: Float): Int {
    // 低密度透明（露出底图），中密度绿→黄，高密度红。
    val alpha = (t * 255f).toInt().coerceIn(0, 255)
    val red = if (t < 0.5f) (t / 0.5f * 255f).toInt() else 255
    val green = if (t < 0.5f) 255 else (255f * (1f - (t - 0.5f) / 0.5f)).toInt()
    return android.graphics.Color.argb(alpha, red, green, 0)
}
