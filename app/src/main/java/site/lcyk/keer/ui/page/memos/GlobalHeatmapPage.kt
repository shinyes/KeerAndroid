package site.lcyk.keer.ui.page.memos

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import site.lcyk.keer.R
import site.lcyk.keer.ext.string
import site.lcyk.keer.ui.page.common.PageScaffold
import site.lcyk.keer.util.HeatDensityField
import site.lcyk.keer.util.buildHeatDensityField
import site.lcyk.keer.util.panViewport
import site.lcyk.keer.util.projectCoordinateToScreen
import site.lcyk.keer.util.projectNormalizedToScreen
import site.lcyk.keer.util.scaleViewportAround
import site.lcyk.keer.util.viewportWorldWidthPx
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
    var densityField by remember { mutableStateOf<HeatDensityField?>(null) }
    var overlaySize by remember { mutableStateOf(IntSize.Zero) }
    var viewportCommitJob by remember { mutableStateOf<Job?>(null) }
    val colorScheme = MaterialTheme.colorScheme

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

    // 密度场构建：KDE 位图按"锚点 viewport"栅格化（地理对齐），拖拽/缩放渲染时
    // 只需对当前 viewport 做仿射变换，热力与底图严格同步；仅当缩放偏移或平移越界
    // 才异步重建，避免拖拽时每帧重建导致的卡顿。
    LaunchedEffect(uiState.normalizedPoints, overlaySize) {
        while (isActive) {
            val buildViewport = interactiveViewport
            val canBuild = buildViewport.hasSize() &&
                uiState.normalizedPoints.isNotEmpty() &&
                overlaySize.width > 0 && overlaySize.height > 0
            if (!canBuild) break
            densityField = withContext(Dispatchers.Default) {
                buildHeatDensityField(
                    points = uiState.normalizedPoints,
                    viewport = buildViewport,
                    widthPx = overlaySize.width,
                    heightPx = overlaySize.height,
                )
            }
            val built = densityField ?: break
            // 等到 viewport 需要重建（缩放偏移 / 平移越界）再重建。
            while (isActive) {
                delay(GLOBAL_HEATMAP_RECOMPUTE_DELAY_MILLIS)
                if (heatDensityFieldNeedsRebuild(interactiveViewport, built)) break
            }
        }
    }

    Box(
        modifier = modifier
            .onSizeChanged { size ->
                overlaySize = size
                interactiveViewport = interactiveViewport.copy(
                    widthPx = size.width,
                    heightPx = size.height,
                )
                viewModel.updateViewportSize(
                    widthPx = size.width,
                    heightPx = size.height,
                )
            }
            .pointerInput(Unit) {
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
            densityField?.let { field ->
                drawHeatDensityField(field = field, viewport = interactiveViewport)
            }
        }

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
private const val GLOBAL_HEATMAP_ZOOM_REBUILD_SCALE = 1.28f
private const val GLOBAL_HEATMAP_ZOOM_REBUILD_SCALE_INV = 0.78f
private const val GLOBAL_HEATMAP_REBUILD_EDGE_PX = 48f

/** 把地理对齐的密度场位图按当前 viewport 做仿射变换后绘制（scale + translate）。 */
private fun DrawScope.drawHeatDensityField(
    field: HeatDensityField,
    viewport: GlobalHeatmapViewport,
) {
    val topLeft = projectNormalizedToScreen(
        viewport = viewport,
        normX = field.topLeftXNorm,
        normY = field.topLeftYNorm,
    ) ?: return
    val zoomScale = (viewportWorldWidthPx(viewport).toDouble() / field.anchorScalePx).toFloat()
    if (zoomScale <= 0f) return
    withTransform({
        translate(left = topLeft.x, top = topLeft.y)
        scale(scaleX = zoomScale, scaleY = zoomScale, pivot = Offset.Zero)
    }) {
        drawImage(image = field.image)
    }
}

/** 缩放偏移超过阈值，或平移使屏幕边缘逼近纹理边界时，需要重建密度场。 */
private fun heatDensityFieldNeedsRebuild(
    viewport: GlobalHeatmapViewport,
    field: HeatDensityField,
): Boolean {
    val zoomScale = viewportWorldWidthPx(viewport).toDouble() / field.anchorScalePx
    if (
        zoomScale > GLOBAL_HEATMAP_ZOOM_REBUILD_SCALE ||
        zoomScale < GLOBAL_HEATMAP_ZOOM_REBUILD_SCALE_INV
    ) {
        return true
    }
    val anchorCenterScreen = projectCoordinateToScreen(
        viewport = viewport,
        latitude = field.anchorViewport.centerLatitude,
        longitude = field.anchorViewport.centerLongitude,
    ) ?: return false
    val distX = anchorCenterScreen.x - viewport.widthPx / 2f
    val distY = anchorCenterScreen.y - viewport.heightPx / 2f
    val coveredHalfX = field.image.width / 2f * zoomScale.toFloat()
    val coveredHalfY = field.image.height / 2f * zoomScale.toFloat()
    return abs(distX) > coveredHalfX - viewport.widthPx / 2f - GLOBAL_HEATMAP_REBUILD_EDGE_PX ||
        abs(distY) > coveredHalfY - viewport.heightPx / 2f - GLOBAL_HEATMAP_REBUILD_EDGE_PX
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
