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
import site.lcyk.keer.util.projectCoordinateToScreenWrapped
import site.lcyk.keer.util.wrapScreenXNear
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

data class OfflineGlobalBasemapData(
    val landRings: List<List<GeoCoordinate>>,
    val borderLines: List<List<GeoCoordinate>>,
    val lakeRings: List<List<GeoCoordinate>>,
    val admin1Lines: List<List<GeoCoordinate>>,
)

data class GeoCoordinate(
    val latitude: Double,
    val longitude: Double,
)

@Composable
internal fun rememberOfflineGlobalBasemapData(): OfflineGlobalBasemapData? {
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
    data: OfflineGlobalBasemapData?,
) {
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

    drawGraticule(viewport, palette)
    data?.let { basemap ->
        drawGeoPolygons(
            viewport = viewport,
            rings = basemap.landRings,
            fillBrush = Brush.linearGradient(
                colors = listOf(palette.landTop, palette.landBottom),
                start = Offset(size.width * 0.45f, 0f),
                end = Offset(size.width * 0.55f, size.height),
            ),
            strokeColor = palette.coastline,
            strokeWidth = 1.25f,
        )
        drawGeoPolygons(
            viewport = viewport,
            rings = basemap.lakeRings,
            fillBrush = Brush.verticalGradient(
                colors = listOf(palette.lakeTop, palette.lakeBottom),
            ),
            strokeColor = palette.lakeBottom.copy(alpha = 0.55f),
            strokeWidth = 0.8f,
        )
        drawGeoLines(
            viewport = viewport,
            lines = basemap.admin1Lines,
            color = palette.border.copy(alpha = 0.45f),
            strokeWidth = 0.55f,
        )
        drawGeoLines(
            viewport = viewport,
            lines = basemap.borderLines,
            color = palette.border,
            strokeWidth = 0.9f,
        )
    }
}

private fun DrawScope.drawGraticule(
    viewport: GlobalHeatmapViewport,
    palette: OfflineGlobalBasemapPalette,
) {
    (-150..180 step 30).forEach { longitude ->
        drawGeoLine(
            viewport = viewport,
            longitudes = List(36) { longitude.toDouble() },
            latitudes = List(36) { index -> -85.0 + index * 5.0 },
            color = palette.gridMajor,
            strokeWidth = 1.15f,
        )
    }
    (-75..75 step 15).forEach { latitude ->
        drawGeoLine(
            viewport = viewport,
            longitudes = List(73) { index -> -180.0 + index * 5.0 },
            latitudes = List(73) { latitude.toDouble() },
            color = if (latitude % 30 == 0) palette.gridMajor else palette.gridMinor,
            strokeWidth = if (latitude % 30 == 0) 1.05f else 0.8f,
        )
    }
}

private fun DrawScope.drawGeoPolygons(
    viewport: GlobalHeatmapViewport,
    rings: List<List<GeoCoordinate>>,
    fillBrush: Brush,
    strokeColor: Color,
    strokeWidth: Float,
) {
    rings.forEach { ring ->
        (-1..1).forEach { wrapOffset ->
            val path = Path()
            var previousX: Float? = null
            ring.forEachIndexed { index, coordinate ->
                val projected = projectCoordinateToScreenWrapped(
                    viewport = viewport,
                    latitude = coordinate.latitude,
                    longitude = coordinate.longitude,
                    wrapOffsetWorlds = wrapOffset,
                ) ?: return@forEachIndexed
                val projectedX = previousX?.let { previous ->
                    wrapScreenXNear(
                        viewport = viewport,
                        candidateX = projected.x,
                        referenceX = previous,
                    )
                } ?: projected.x
                previousX = projectedX
                if (index == 0) {
                    path.moveTo(projectedX, projected.y)
                } else {
                    path.lineTo(projectedX, projected.y)
                }
            }
            path.close()
            drawPath(path = path, brush = fillBrush)
            drawPath(
                path = path,
                color = strokeColor,
                style = Stroke(width = strokeWidth),
            )
        }
    }
}

private fun DrawScope.drawGeoLines(
    viewport: GlobalHeatmapViewport,
    lines: List<List<GeoCoordinate>>,
    color: Color,
    strokeWidth: Float,
) {
    lines.forEach { line ->
        (-1..1).forEach { wrapOffset ->
            val path = Path()
            var previousX: Float? = null
            line.forEachIndexed { index, coordinate ->
                val projected = projectCoordinateToScreenWrapped(
                    viewport = viewport,
                    latitude = coordinate.latitude,
                    longitude = coordinate.longitude,
                    wrapOffsetWorlds = wrapOffset,
                ) ?: return@forEachIndexed
                val projectedX = previousX?.let { previous ->
                    wrapScreenXNear(
                        viewport = viewport,
                        candidateX = projected.x,
                        referenceX = previous,
                    )
                } ?: projected.x
                previousX = projectedX
                if (index == 0) {
                    path.moveTo(projectedX, projected.y)
                } else {
                    path.lineTo(projectedX, projected.y)
                }
            }
            drawPath(
                path = path,
                color = color,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
            )
        }
    }
}

private fun DrawScope.drawGeoLine(
    viewport: GlobalHeatmapViewport,
    longitudes: List<Double>,
    latitudes: List<Double>,
    color: Color,
    strokeWidth: Float,
) {
    (-1..1).forEach { wrapOffset ->
        val path = Path()
        var previousX: Float? = null
        longitudes.indices.forEach { index ->
            val projected = projectCoordinateToScreenWrapped(
                viewport = viewport,
                latitude = latitudes[index],
                longitude = longitudes[index],
                wrapOffsetWorlds = wrapOffset,
            ) ?: return@forEach
            val projectedX = previousX?.let { previous ->
                wrapScreenXNear(
                    viewport = viewport,
                    candidateX = projected.x,
                    referenceX = previous,
                )
            } ?: projected.x
            previousX = projectedX
            if (index == 0) {
                path.moveTo(projectedX, projected.y)
            } else {
                path.lineTo(projectedX, projected.y)
            }
        }
        drawPath(
            path = path,
            color = color,
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
        )
    }
}

private object OfflineGlobalBasemapCache {
    private val cache = AtomicReference<OfflineGlobalBasemapData?>(null)
    private val json = Json {
        ignoreUnknownKeys = true
    }

    fun current(): OfflineGlobalBasemapData? = cache.get()

    suspend fun load(context: Context): OfflineGlobalBasemapData {
        cache.get()?.let { return it }
        return withContext(Dispatchers.IO) {
            cache.get() ?: context.assets.open(GLOBAL_BASEMAP_ASSET_PATH).bufferedReader().use { reader ->
                val asset = json.decodeFromString<OfflineGlobalBasemapAsset>(reader.readText())
                asset.toRuntimeData().also(cache::set)
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
    fun toRuntimeData(): OfflineGlobalBasemapData {
        return OfflineGlobalBasemapData(
            landRings = landRings.map(::decodeLine),
            borderLines = borderLines.map(::decodeLine),
            lakeRings = lakeRings.map(::decodeLine),
            admin1Lines = admin1Lines.map(::decodeLine),
        )
    }

    private fun decodeLine(points: List<List<Double>>): List<GeoCoordinate> {
        return points.mapNotNull { point ->
            if (point.size < 2) {
                null
            } else {
                GeoCoordinate(
                    latitude = point[0],
                    longitude = point[1],
                )
            }
        }
    }
}
