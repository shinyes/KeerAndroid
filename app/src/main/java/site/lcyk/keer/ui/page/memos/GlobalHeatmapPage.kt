package site.lcyk.keer.ui.page.memos

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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.amap.api.maps.AMap
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.MapView
import com.amap.api.maps.model.CameraPosition
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.LatLngBounds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToInt
import site.lcyk.keer.R
import site.lcyk.keer.ext.string
import site.lcyk.keer.ui.page.common.PageScaffold
import site.lcyk.keer.util.DEFAULT_GLOBAL_HEATMAP_CENTER_LATITUDE
import site.lcyk.keer.util.DEFAULT_GLOBAL_HEATMAP_CENTER_LONGITUDE
import site.lcyk.keer.util.DEFAULT_GLOBAL_HEATMAP_ZOOM
import site.lcyk.keer.util.ProjectedMemoPoint
import site.lcyk.keer.util.buildGeoHeatBuckets
import site.lcyk.keer.util.buildGeoHeatBucketsFromProjectedPoints
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
            if (uiState.mapAvailable) {
                AmapHeatmapSurface(
                    uiState = uiState,
                    viewModel = viewModel,
                    resetNonce = resetNonce,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                OfflineHeatmapSurface(
                    uiState = uiState,
                    viewModel = viewModel,
                    resetNonce = resetNonce,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@Composable
private fun AmapHeatmapSurface(
    uiState: GlobalHeatmapUiState,
    viewModel: GlobalHeatmapViewModel,
    resetNonce: Int,
    modifier: Modifier = Modifier,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestPoints by rememberUpdatedState(uiState.points)
    val latestViewport by rememberUpdatedState(uiState.viewport)
    var mapView by remember { mutableStateOf<MapView?>(null) }
    var amap by remember { mutableStateOf<AMap?>(null) }
    var committedBuckets by remember { mutableStateOf<List<GeoHeatBucket>>(emptyList()) }
    var renderedBuckets by remember { mutableStateOf<List<GeoHeatBucket>>(emptyList()) }
    var isTouchInteracting by remember { mutableStateOf(false) }
    var lastOverlayProjectionAtMillis by remember { mutableLongStateOf(0L) }
    var lastKnownMapWidth by remember { mutableIntStateOf(0) }
    var lastKnownMapHeight by remember { mutableIntStateOf(0) }
    var isMapLoaded by remember { mutableStateOf(false) }

    fun refreshInteractiveBuckets(
        map: AMap,
        force: Boolean = false,
    ) {
        val now = SystemClock.uptimeMillis()
        if (!force && now - lastOverlayProjectionAtMillis < GLOBAL_HEATMAP_DRAG_PROJECT_THROTTLE_MILLIS) {
            return
        }
        val currentMapView = mapView ?: return
        if (currentMapView.width <= 0 || currentMapView.height <= 0) {
            return
        }
        val projection = map.projection ?: return
        val projectedPoints = latestPoints.mapNotNull { point ->
            projection.toScreenLocation(LatLng(point.latitude, point.longitude))?.let { screen ->
                ProjectedMemoPoint(
                    identifier = point.identifier,
                    latitude = point.latitude,
                    longitude = point.longitude,
                    x = screen.x.toFloat(),
                    y = screen.y.toFloat(),
                    date = point.date,
                )
            }
        }
        val buckets = buildGeoHeatBucketsFromProjectedPoints(
            points = projectedPoints,
            widthPx = currentMapView.width,
            heightPx = currentMapView.height,
            zoom = map.cameraPosition.zoom.toDouble(),
        )
        committedBuckets = buckets
        renderedBuckets = buckets
        lastOverlayProjectionAtMillis = now
    }

    DisposableEffect(lifecycleOwner, mapView) {
        val observedMapView = mapView
        if (observedMapView == null) {
            onDispose { }
        } else {
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_RESUME -> observedMapView.onResume()
                    Lifecycle.Event.ON_PAUSE -> observedMapView.onPause()
                    Lifecycle.Event.ON_DESTROY -> observedMapView.onDestroy()
                    else -> Unit
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose {
                lifecycleOwner.lifecycle.removeObserver(observer)
            }
        }
    }

    LaunchedEffect(uiState.points, amap, isMapLoaded) {
        val map = amap
        if (map != null && isMapLoaded) {
            refreshInteractiveBuckets(
                map = map,
                force = true,
            )
        }
    }

    LaunchedEffect(resetNonce, amap, uiState.points) {
        val map = amap ?: return@LaunchedEffect
        if (resetNonce <= 0) {
            return@LaunchedEffect
        }
        if (uiState.points.isNotEmpty()) {
            moveCameraToHeatmapBounds(
                map = map,
                points = uiState.points,
                animate = true,
            )
            return@LaunchedEffect
        }
        val resetViewport = defaultGlobalHeatmapViewport(
            widthPx = uiState.viewport.widthPx,
            heightPx = uiState.viewport.heightPx,
        )
        map.animateCamera(
            CameraUpdateFactory.newLatLngZoom(
                LatLng(
                    resetViewport.centerLatitude,
                    resetViewport.centerLongitude,
                ),
                resetViewport.zoom.toFloat(),
            )
        )
    }

    Box(modifier = modifier) {
        AndroidView(
            factory = { context ->
                MapView(context).also { createdMapView ->
                    createdMapView.onCreate(null)
                    val map = createdMapView.map
                    mapView = createdMapView
                    amap = map
                    map.mapType = AMap.MAP_TYPE_NORMAL
                    map.uiSettings.isZoomControlsEnabled = false
                    map.uiSettings.isCompassEnabled = false
                    map.uiSettings.isScaleControlsEnabled = false
                    map.uiSettings.isTiltGesturesEnabled = false
                    map.uiSettings.isRotateGesturesEnabled = false
                    map.setOnMapLoadedListener {
                        isMapLoaded = true
                        lastKnownMapWidth = createdMapView.width
                        lastKnownMapHeight = createdMapView.height
                        if (createdMapView.width > 0 && createdMapView.height > 0) {
                            viewModel.updateViewportSize(
                                widthPx = createdMapView.width,
                                heightPx = createdMapView.height,
                            )
                            refreshInteractiveBuckets(
                                map = map,
                                force = true,
                            )
                        }
                    }
                    map.setOnMapTouchListener { motionEvent ->
                        when (motionEvent.actionMasked) {
                            MotionEvent.ACTION_DOWN,
                            MotionEvent.ACTION_POINTER_DOWN,
                            MotionEvent.ACTION_MOVE -> {
                                isTouchInteracting = true
                            }

                            MotionEvent.ACTION_UP,
                            MotionEvent.ACTION_CANCEL -> {
                                isTouchInteracting = false
                            }
                        }
                    }
                    map.setOnCameraChangeListener(object : AMap.OnCameraChangeListener {
                        override fun onCameraChange(position: CameraPosition) {
                            refreshInteractiveBuckets(
                                map = map,
                            )
                        }

                        override fun onCameraChangeFinish(position: CameraPosition) {
                            isTouchInteracting = false
                            val updatedViewport = latestViewport.copy(
                                widthPx = createdMapView.width,
                                heightPx = createdMapView.height,
                                centerLatitude = position.target.latitude,
                                centerLongitude = position.target.longitude,
                                zoom = position.zoom.toDouble(),
                            )
                            viewModel.updateViewport(updatedViewport)
                            refreshInteractiveBuckets(
                                map = map,
                                force = true,
                            )
                        }
                    })
                    map.moveCamera(
                        CameraUpdateFactory.newLatLngZoom(
                            LatLng(
                                uiState.viewport.centerLatitude,
                                uiState.viewport.centerLongitude,
                            ),
                            uiState.viewport.zoom.toFloat(),
                        )
                    )
                }
            },
            update = { updatedMapView ->
                val map = updatedMapView.map
                mapView = updatedMapView
                amap = map
                if (updatedMapView.width > 0 && updatedMapView.height > 0) {
                    val sizeChanged = updatedMapView.width != lastKnownMapWidth || updatedMapView.height != lastKnownMapHeight
                    lastKnownMapWidth = updatedMapView.width
                    lastKnownMapHeight = updatedMapView.height
                    viewModel.updateViewportSize(
                        widthPx = updatedMapView.width,
                        heightPx = updatedMapView.height,
                    )
                    if (sizeChanged || committedBuckets.isEmpty()) {
                        refreshInteractiveBuckets(
                            map = map,
                            force = true,
                        )
                    }
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        GlobalHeatmapOverlay(
            buckets = renderedBuckets,
            modifier = Modifier.fillMaxSize(),
        )

        GlobalHeatmapInfoChips(
            uiState = uiState,
            basemapReady = true,
            statusMessage = null,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(16.dp),
        )
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

    val renderedBuckets by remember(committedBuckets, interactiveViewport) {
        derivedStateOf {
            projectBucketsToScreen(
                buckets = committedBuckets,
                viewport = interactiveViewport,
            )
        }
    }
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

    LaunchedEffect(uiState.points, uiState.viewport) {
        committedBuckets = if (!uiState.viewport.hasSize()) {
            emptyList()
        } else {
            withContext(Dispatchers.Default) {
                buildGeoHeatBuckets(
                    points = uiState.points,
                    viewport = uiState.viewport,
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
            statusMessage = uiState.mapErrorMessage,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(16.dp),
        )
    }
}

private const val GLOBAL_HEATMAP_RECOMPUTE_DELAY_MILLIS = 84L
private const val GLOBAL_HEATMAP_VIEWPORT_COMMIT_DELAY_MILLIS = 96L
private const val GLOBAL_HEATMAP_DRAG_PROJECT_THROTTLE_MILLIS = 12L

private fun moveCameraToHeatmapBounds(
    map: AMap,
    points: List<site.lcyk.keer.util.DisplayGeoMemoPoint>,
    animate: Boolean,
) {
    if (points.isEmpty()) {
        return
    }
    val focusPoints = points
        .groupBy { point ->
            AmapHeatmapClusterKey(
                latitudeBucket = (point.latitude * 100.0).roundToInt(),
                longitudeBucket = (point.longitude * 100.0).roundToInt(),
            )
        }
        .maxByOrNull { (_, groupedPoints) -> groupedPoints.size }
        ?.value
        ?.takeIf { clusteredPoints ->
            clusteredPoints.size >= (points.size * 0.45f).roundToInt().coerceAtLeast(4)
        }
        ?: points
    val distinctPoints = focusPoints
        .map { LatLng(it.latitude, it.longitude) }
        .distinctBy { latLng -> "${latLng.latitude},${latLng.longitude}" }
    val update = if (distinctPoints.size == 1) {
        CameraUpdateFactory.newLatLngZoom(distinctPoints.first(), 13f)
    } else {
        val boundsBuilder = LatLngBounds.builder()
        distinctPoints.forEach(boundsBuilder::include)
        CameraUpdateFactory.newLatLngBounds(boundsBuilder.build(), 180)
    }
    if (animate) {
        map.animateCamera(update)
    } else {
        map.moveCamera(update)
    }
}

private data class AmapHeatmapClusterKey(
    val latitudeBucket: Int,
    val longitudeBucket: Int,
)

@Composable
private fun GlobalHeatmapOverlay(
    buckets: List<GeoHeatBucket>,
    modifier: Modifier = Modifier,
) {
    val blueBand = Color(0xFF4D6BFF)
    val cyanBand = Color(0xFF37C9FF)
    val greenBand = Color(0xFF4CDE72)
    val yellowBand = Color(0xFFF0EA52)
    val orangeBand = Color(0xFFFF963C)
    val redBand = Color(0xFFFF5637)
    Canvas(modifier = modifier) {
        buckets.sortedBy { it.intensity }.forEach { bucket ->
            val intensity = bucket.intensity.coerceIn(0.14f, 1f)
            val radius = (bucket.radiusPx * (1.16f + intensity * 0.22f)).coerceAtLeast(18f)
            val center = Offset(bucket.centerX, bucket.centerY)
            drawCircle(
                brush = Brush.radialGradient(
                    colorStops = arrayOf(
                        0.00f to redBand.copy(alpha = 0.20f + intensity * 0.08f),
                        0.14f to orangeBand.copy(alpha = 0.24f + intensity * 0.10f),
                        0.28f to yellowBand.copy(alpha = 0.26f + intensity * 0.10f),
                        0.46f to greenBand.copy(alpha = 0.24f + intensity * 0.08f),
                        0.68f to cyanBand.copy(alpha = 0.18f + intensity * 0.06f),
                        0.86f to blueBand.copy(alpha = 0.12f + intensity * 0.05f),
                        1.00f to Color.Transparent,
                    ),
                    center = center,
                    radius = radius * 1.58f,
                ),
                radius = radius * 1.58f,
                center = center,
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colorStops = arrayOf(
                        0.00f to redBand.copy(alpha = 0.16f + intensity * 0.08f),
                        0.22f to orangeBand.copy(alpha = 0.16f + intensity * 0.08f),
                        0.44f to yellowBand.copy(alpha = 0.10f + intensity * 0.05f),
                        1.00f to Color.Transparent,
                    ),
                    center = center,
                    radius = radius * 0.68f,
                ),
                radius = radius * 0.68f,
                center = center,
            )
            drawCircle(
                color = redBand.copy(alpha = 0.04f + intensity * 0.03f),
                radius = radius * 0.18f,
                center = center,
            )
        }
    }
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
