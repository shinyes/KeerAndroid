package site.lcyk.keer.ui.page.memos

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.max
import site.lcyk.keer.util.NormalizedCoordinate
import site.lcyk.keer.util.toNormalizedCoordinate
import site.lcyk.keer.util.viewportWorldWidthPx
import site.lcyk.keer.viewmodel.GlobalHeatmapViewport

private const val GLOBAL_BASEMAP_ASSET_PATH = "global_basemap_50m.json"

data class OfflineGlobalBasemapPalette(
    val oceanTop: Color,
    val oceanBottom: Color,
    val oceanGlow: Color,
    val gridMajor: Color,
    val gridMinor: Color,
    val landTop: Color,
    val landBottom: Color,
    val coastline: Color,
    val border: Color,
    val lakeTop: Color,
    val lakeBottom: Color,
)

/** 归一化地理路径：顶点已投影到 Web-Mercator 归一化空间并缓存，每帧只需线性变换。 */
data class NormalizedGeoPath(
    val coordinates: FloatArray, // [x0, y0, x1, y1, ...] 归一化坐标
)

/**
 * 归一化后的底图数据。加载时一次性把全部经纬度投影到归一化空间，
 * 拖动/缩放时渲染只做 scale+translate 的线性映射，不再每帧重算 sin/ln。
 */
data class NormalizedGlobalBasemapData(
    val landRings: List<NormalizedGeoPath>,
    val borderLines: List<NormalizedGeoPath>,
    val lakeRings: List<NormalizedGeoPath>,
    val admin1Lines: List<NormalizedGeoPath>,
    val graticuleMajorLines: List<NormalizedGeoPath>,
    val graticuleMinorLines: List<NormalizedGeoPath>,
)

@Composable
internal fun rememberOfflineGlobalBasemapData(): NormalizedGlobalBasemapData? {
    val context = LocalContext.current.applicationContext
    var data by remember { mutableStateOf(OfflineGlobalBasemapCache.current()) }
    LaunchedEffect(context) {
        if (data == null) {
            data = OfflineGlobalBasemapCache.load(context)
        }
    }
    return data
}

internal fun DrawScope.drawOfflineGlobalBasemap(
    viewport: GlobalHeatmapViewport,
    palette: OfflineGlobalBasemapPalette,
    data: NormalizedGlobalBasemapData?,
) {
    // 海洋渐变与辉光：屏幕空间，每帧廉价。
    drawRect(
        brush = Brush.verticalGradient(
            colors = listOf(palette.oceanTop, palette.oceanBottom),
        ),
    )
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                palette.oceanGlow.copy(alpha = 0.24f),
                Color.Transparent,
            ),
            center = Offset(size.width * 0.26f, size.height * 0.16f),
            radius = max(size.width, size.height) * 0.74f,
        ),
        radius = max(size.width, size.height) * 0.74f,
        center = Offset(size.width * 0.26f, size.height * 0.16f),
    )

    if (data == null) {
        return
    }
    val center = toNormalizedCoordinate(viewport.centerLatitude, viewport.centerLongitude)
    val scalePx = viewportWorldWidthPx(viewport).toDouble()
    val worldWidthPx = scalePx.toFloat()

    drawNormalizedLines(
        paths = data.graticuleMinorLines,
        center = center,
        scalePx = scalePx,
        worldWidthPx = worldWidthPx,
        color = palette.gridMinor,
        strokeWidth = 0.8f,
    )
    drawNormalizedLines(
        paths = data.graticuleMajorLines,
        center = center,
        scalePx = scalePx,
        worldWidthPx = worldWidthPx,
        color = palette.gridMajor,
        strokeWidth = 1.1f,
    )
    drawNormalizedPolygons(
        paths = data.landRings,
        center = center,
        scalePx = scalePx,
        worldWidthPx = worldWidthPx,
        fillBrush = Brush.linearGradient(
            colors = listOf(palette.landTop, palette.landBottom),
            start = Offset(size.width * 0.45f, 0f),
            end = Offset(size.width * 0.55f, size.height),
        ),
        strokeColor = palette.coastline,
        strokeWidth = 1.25f,
    )
    drawNormalizedPolygons(
        paths = data.lakeRings,
        center = center,
        scalePx = scalePx,
        worldWidthPx = worldWidthPx,
        fillBrush = Brush.verticalGradient(
            colors = listOf(palette.lakeTop, palette.lakeBottom),
        ),
        strokeColor = palette.lakeBottom.copy(alpha = 0.55f),
        strokeWidth = 0.8f,
    )
    drawNormalizedLines(
        paths = data.admin1Lines,
        center = center,
        scalePx = scalePx,
        worldWidthPx = worldWidthPx,
        color = palette.border.copy(alpha = 0.45f),
        strokeWidth = 0.55f,
    )
    drawNormalizedLines(
        paths = data.borderLines,
        center = center,
        scalePx = scalePx,
        worldWidthPx = worldWidthPx,
        color = palette.border,
        strokeWidth = 0.9f,
    )
}

private fun DrawScope.drawNormalizedPolygons(
    paths: List<NormalizedGeoPath>,
    center: NormalizedCoordinate,
    scalePx: Double,
    worldWidthPx: Float,
    fillBrush: Brush,
    strokeColor: Color,
    strokeWidth: Float,
) {
    paths.forEach { path ->
        val coords = path.coordinates
        (-1..1).forEach { wrapOffset ->
            val p = Path()
            var previousX: Float? = null
            var i = 0
            while (i < coords.size) {
                var x = (
                    size.width.toDouble() / 2.0 + (coords[i].toDouble() + wrapOffset - center.x) * scalePx
                    ).toFloat()
                val y = (
                    size.height.toDouble() / 2.0 + (coords[i + 1].toDouble() - center.y) * scalePx
                    ).toFloat()
                val prev = previousX
                if (prev != null) {
                    while (x - prev > worldWidthPx / 2f) x -= worldWidthPx
                    while (x - prev < -worldWidthPx / 2f) x += worldWidthPx
                }
                previousX = x
                if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
                i += 2
            }
            p.close()
            drawPath(path = p, brush = fillBrush)
            drawPath(
                path = p,
                color = strokeColor,
                style = Stroke(width = strokeWidth),
            )
        }
    }
}

private fun DrawScope.drawNormalizedLines(
    paths: List<NormalizedGeoPath>,
    center: NormalizedCoordinate,
    scalePx: Double,
    worldWidthPx: Float,
    color: Color,
    strokeWidth: Float,
) {
    paths.forEach { path ->
        val coords = path.coordinates
        (-1..1).forEach { wrapOffset ->
            val p = Path()
            var previousX: Float? = null
            var i = 0
            while (i < coords.size) {
                var x = (
                    size.width.toDouble() / 2.0 + (coords[i].toDouble() + wrapOffset - center.x) * scalePx
                    ).toFloat()
                val y = (
                    size.height.toDouble() / 2.0 + (coords[i + 1].toDouble() - center.y) * scalePx
                    ).toFloat()
                val prev = previousX
                if (prev != null) {
                    while (x - prev > worldWidthPx / 2f) x -= worldWidthPx
                    while (x - prev < -worldWidthPx / 2f) x += worldWidthPx
                }
                previousX = x
                if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
                i += 2
            }
            drawPath(
                path = p,
                color = color,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
            )
        }
    }
}

private object OfflineGlobalBasemapCache {
    private val cache = AtomicReference<NormalizedGlobalBasemapData?>(null)
    private val json = Json {
        ignoreUnknownKeys = true
    }

    fun current(): NormalizedGlobalBasemapData? = cache.get()

    suspend fun load(context: Context): NormalizedGlobalBasemapData {
        cache.get()?.let { return it }
        return withContext(Dispatchers.IO) {
            cache.get() ?: context.assets.open(GLOBAL_BASEMAP_ASSET_PATH).bufferedReader().use { reader ->
                val asset = json.decodeFromString<OfflineGlobalBasemapAsset>(reader.readText())
                asset.toNormalizedData().also(cache::set)
            }
        }
    }
}

@Serializable
private data class OfflineGlobalBasemapAsset(
    val landRings: List<List<List<Double>>>,
    val borderLines: List<List<List<Double>>>,
    val lakeRings: List<List<List<Double>>>,
    val admin1Lines: List<List<List<Double>>> = emptyList(),
) {
    fun toNormalizedData(): NormalizedGlobalBasemapData {
        return NormalizedGlobalBasemapData(
            landRings = landRings.map(::toNormalizedPath),
            borderLines = borderLines.map(::toNormalizedPath),
            lakeRings = lakeRings.map(::toNormalizedPath),
            admin1Lines = admin1Lines.map(::toNormalizedPath),
            graticuleMajorLines = buildGraticule(major = true),
            graticuleMinorLines = buildGraticule(major = false),
        )
    }

    private fun toNormalizedPath(points: List<List<Double>>): NormalizedGeoPath {
        val flat = FloatArray(points.size * 2)
        var used = 0
        points.forEach { point ->
            if (point.size >= 2) {
                val coord = toNormalizedCoordinate(point[0], point[1])
                flat[used] = coord.x.toFloat()
                flat[used + 1] = coord.y.toFloat()
                used += 2
            }
        }
        return NormalizedGeoPath(coordinates = if (used == flat.size) flat else flat.copyOf(used))
    }
}

private fun buildGraticule(major: Boolean): List<NormalizedGeoPath> {
    val lines = mutableListOf<NormalizedGeoPath>()
    if (major) {
        // 经线每 30°
        for (longitude in -150..180 step 30) {
            lines += normalizedLine(
                longitudes = List(36) { longitude.toDouble() },
                latitudes = List(36) { index -> -85.0 + index * 5.0 },
            )
        }
    }
    // 纬线每 15°，整 30° 为主格网
    for (latitude in -75..75 step 15) {
        val isMajor = latitude % 30 == 0
        if (isMajor != major) continue
        lines += normalizedLine(
            longitudes = List(73) { index -> -180.0 + index * 5.0 },
            latitudes = List(73) { latitude.toDouble() },
        )
    }
    return lines
}

private fun normalizedLine(
    longitudes: List<Double>,
    latitudes: List<Double>,
): NormalizedGeoPath {
    val flat = FloatArray(longitudes.size * 2)
    longitudes.indices.forEach { index ->
        val coord = toNormalizedCoordinate(latitudes[index], longitudes[index])
        flat[index * 2] = coord.x.toFloat()
        flat[index * 2 + 1] = coord.y.toFloat()
    }
    return NormalizedGeoPath(coordinates = flat)
}
