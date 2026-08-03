package site.lcyk.keer.ui.page.memos

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.MotionEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt
import site.lcyk.keer.R
import site.lcyk.keer.ext.string
import site.lcyk.keer.ui.page.common.PageScaffold
import site.lcyk.keer.util.DEFAULT_GLOBAL_HEATMAP_CENTER_LATITUDE
import site.lcyk.keer.util.DEFAULT_GLOBAL_HEATMAP_CENTER_LONGITUDE
import site.lcyk.keer.util.DEFAULT_GLOBAL_HEATMAP_ZOOM
import site.lcyk.keer.util.ProjectedMemoPoint
import site.lcyk.keer.util.buildGeoHeatBuckets
import site.lcyk.keer.util.buildGeoHeatBucketsFromProjectedPoints
import site.lcyk.keer.util.buildProjectedMemoPoints
import site.lcyk.keer.util.defaultGlobalHeatmapViewport
import site.lcyk.keer.util.panViewport
import site.lcyk.keer.util.projectBucketsToScreen
import site.lcyk.keer.util.scaleViewportAround
import site.lcyk.keer.viewmodel.GeoHeatBucket
import site.lcyk.keer.viewmodel.GlobalHeatmapUiState
import site.lcyk.keer.viewmodel.GlobalHeatmapViewport
import site.lcyk.keer.viewmodel.GlobalHeatmapViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlobalHeatmapPage(
    drawerState: DrawerState? = null,
    onMenuButtonOpenRequested: (() -> Unit)? = null,
    viewModel: GlobalHeatmapViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var resetNonce by remember { mutableIntStateOf(0) }

    PageScaffold(
        title = R.string.global_heatmap_title.string,
        drawerState = drawerState,
        onMenuButtonOpenRequested = onMenuButtonOpenRequested,
        actions = {
            IconButton(
                onClick = {
                    resetNonce += 1
                    viewModel.resetViewport()
                },
            ) {
                Icon(
                    imageVector = Icons.Outlined.MyLocation,
                    contentDescription = R.string.global_heatmap_reset.string,
                )
            }
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.surfaceContainerLowest),
        ) {
            // 仅使用离线自绘底图（移除 AMap 在线地图，无需高德 API Key）。
            OfflineHeatmapSurface(
                uiState = uiState,
                viewModel = viewModel,
                resetNonce = resetNonce,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun OfflineHeatmapSurface(
    uiState: GlobalHeatmapUiState,
    viewModel: GlobalHeatmapViewModel,
    resetNonce: Int,
    modifier: Modifier = Modifier,
) {
    val basemapData = rememberOfflineGlobalBasemapData()
    val scope = rememberCoroutineScope()
    var interactiveViewport by remember { mutableStateOf(uiState.viewport) }
    var committedBuckets by remember { mutableStateOf<List<GeoHeatBucket>>(emptyList()) }
    var viewportCommitJob by remember { mutableStateOf<Job?>(null) }
    val colorScheme = MaterialTheme.colorScheme

    // 分桶基于 interactiveViewport（与底图同一坐标系），screen 坐标可直接渲染，
    // 避免拖动时分桶中心与投影中心不一致导致的错位/抖动。
    val renderedBuckets = committedBuckets
    val basemapPalette = remember(colorScheme) {
        OfflineGlobalBasemapPalette(
            oceanTop = Color(0xFF0F223A),
            oceanBottom = Color(0xFF183856),
            oceanGlow = colorScheme.primary.copy(alpha = 0.34f),
            gridMajor = Color.White.copy(alpha = 0.12f),
            gridMinor = Color.White.copy(alpha = 0.06f),
            landTop = Color(0xFF506D52),
            landBottom = Color(0xFF394E38),
            coastline = Color(0xFFF2F4E9).copy(alpha = 0.44f),
            border = Color(0xFFF8F4E2).copy(alpha = 0.22f),
            lakeTop = Color(0xFF234E77),
            lakeBottom = Color(0xFF183A58),
        )
    }

    fun scheduleViewportCommit(viewport: GlobalHeatmapViewport) {
        viewportCommitJob?.cancel()
        viewportCommitJob = scope.launch {
            delay(GLOBAL_HEATMAP_VIEWPORT_COMMIT_DELAY_MILLIS)
            viewModel.updateViewport(viewport)
        }
    }

    LaunchedEffect(uiState.viewport) {
        interactiveViewport = uiState.viewport
    }

    LaunchedEffect(uiState.normalizedPoints, interactiveViewport) {
        committedBuckets = if (!interactiveViewport.hasSize()) {
            emptyList()
        } else {
            withContext(Dispatchers.Default) {
                buildGeoHeatBuckets(
                    points = uiState.normalizedPoints,
                    viewport = interactiveViewport,
                )
            }
        }
    }

    Box(
        modifier = modifier
            .onSizeChanged { size ->
                interactiveViewport = interactiveViewport.copy(
                    widthPx = size.width,
                    heightPx = size.height,
                )
                viewModel.updateViewportSize(
                    widthPx = size.width,
                    heightPx = size.height,
                )
            }
            .pointerInput(renderedBuckets) {
                detectTapGestures(
                    onDoubleTap = { offset ->
                        val updated = scaleViewportAround(
                            viewport = interactiveViewport,
                            zoomScaleFactor = 1.7f,
                            focusX = offset.x,
                            focusY = offset.y,
                        )
                        interactiveViewport = updated
                        scheduleViewportCommit(updated)
                    },
                )
            }
            .pointerInput(Unit) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    val zoomed = scaleViewportAround(
                        viewport = interactiveViewport,
                        zoomScaleFactor = zoom,
                        focusX = centroid.x,
                        focusY = centroid.y,
                    )
                    val transformed = panViewport(
                        viewport = zoomed,
                        panXPx = pan.x,
                        panYPx = pan.y,
                    )
                    interactiveViewport = transformed
                    scheduleViewportCommit(transformed)
                }
            },
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawOfflineGlobalBasemap(
                viewport = interactiveViewport,
                palette = basemapPalette,
                data = basemapData,
            )
        }
        GlobalHeatmapOverlay(
            buckets = renderedBuckets,
            modifier = Modifier.fillMaxSize(),
        )

        GlobalHeatmapInfoChips(
            uiState = uiState,
            basemapReady = basemapData != null,
            statusMessage = null,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(16.dp),
        )
    }
}

private const val GLOBAL_HEATMAP_RECOMPUTE_DELAY_MILLIS = 84L
private const val GLOBAL_HEATMAP_VIEWPORT_COMMIT_DELAY_MILLIS = 96L
private const val GLOBAL_HEATMAP_DRAG_PROJECT_THROTTLE_MILLIS = 12L

@Composable
private fun GlobalHeatmapOverlay(
    buckets: List<GeoHeatBucket>,
    modifier: Modifier = Modifier,
) {
    var overlaySize by remember { mutableStateOf(IntSize.Zero) }
    // 核密度估计（KDE）密度场：每个桶的高斯贡献累加到低分辨率网格，
    // 形成连续平滑的热力区（绿→黄→红按密度映射），比离散圆叠加更美观。
    val densityImage = remember(buckets, overlaySize) {
        if (overlaySize == IntSize.Zero) {
            null
        } else {
            buildHeatDensityImage(
                buckets = buckets,
                widthPx = overlaySize.width,
                heightPx = overlaySize.height,
            )
        }
    }
    Canvas(
        modifier = modifier.onSizeChanged { size -> overlaySize = size },
    ) {
        val image = densityImage
        if (image != null) {
            drawImage(
                image = image,
                dstSize = IntSize(size.width.toInt(), size.height.toInt()),
            )
        }
    }
}

/**
 * 把桶列表渲染为低分辨率密度场位图（KDE 高斯累加），按密度映射"绿→黄→红"。
 */
private fun buildHeatDensityImage(
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

private fun heatColor(t: Float): Int {
    // 低密度透明（露出底图），中密度绿→黄，高密度红。
    val alpha = (t * 255f).toInt().coerceIn(0, 255)
    val red = if (t < 0.5f) (t / 0.5f * 255f).toInt() else 255
    val green = if (t < 0.5f) 255 else (255f * (1f - (t - 0.5f) / 0.5f)).toInt()
    return android.graphics.Color.argb(alpha, red, green, 0)
}

@Composable
private fun GlobalHeatmapInfoChips(
    uiState: GlobalHeatmapUiState,
    basemapReady: Boolean,
    statusMessage: String?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.widthIn(max = 280.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (uiState.loading || !basemapReady) {
            Surface(
                shape = RoundedCornerShape(999.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                    )
                    Text(
                        text = if (uiState.loading) {
                            R.string.loading.string
                        } else {
                            R.string.global_heatmap_basemap_loading.string
                        },
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
        if (statusMessage != null) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ),
            ) {
                Text(
                    text = statusMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(14.dp),
                )
            }
        } else if (uiState.memoPointCount == 0 && !uiState.loading) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ),
            ) {
                Text(
                    text = R.string.global_heatmap_empty.string,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(14.dp),
                )
            }
        }
    }
}
