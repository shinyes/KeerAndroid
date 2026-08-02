package site.lcyk.keer.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import site.lcyk.keer.data.map.AmapMapRuntime
import site.lcyk.keer.data.model.GeoMemoPoint
import site.lcyk.keer.data.service.MemoService
import site.lcyk.keer.util.defaultGlobalHeatmapViewport
import site.lcyk.keer.util.GeoCoordinateTransformer
import site.lcyk.keer.util.NormalizedMemoPoint
import site.lcyk.keer.util.normalizeGeoPoints

private data class GeoPointSignature(
    val identifier: String,
    val latitudeBits: Long,
    val longitudeBits: Long,
    val dateEpochMillis: Long,
)

@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class GlobalHeatmapViewModel @Inject constructor(
    memoService: MemoService,
    private val geoCoordinateTransformer: GeoCoordinateTransformer,
) : ViewModel() {

    private val viewportState = MutableStateFlow(defaultGlobalHeatmapViewport())
    private val validGeoPoints = memoService.geoPoints
        .map { points ->
            points
                .asSequence()
                .filter(::isValidGeoPoint)
                .toList()
        }
        .distinctUntilChangedBy { points -> points.map(::toGeoPointSignature) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    private val displayPoints = validGeoPoints
        .mapLatest { points ->
            points.map(geoCoordinateTransformer::toDisplayPoint)
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // 一次性缓存 Web-Mercator 归一化坐标（viewport 无关），
    // 拖拽/缩放时只做便宜线性投影，不再对每个点重算 sin/ln。
    private val normalizedPoints = displayPoints
        .mapLatest { points -> normalizeGeoPoints(points) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val uiState: StateFlow<GlobalHeatmapUiState> = combine(
        viewportState,
        displayPoints,
        validGeoPoints,
        normalizedPoints,
        AmapMapRuntime.state,
    ) { viewport, points, rawPoints, normalized, mapRuntimeState ->
        GlobalHeatmapUiState(
            viewport = viewport,
            points = points,
            normalizedPoints = normalized,
            memoPointCount = rawPoints.size,
            loading = false,
            mapAvailable = mapRuntimeState.available,
            mapErrorMessage = mapRuntimeState.errorMessage,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        GlobalHeatmapUiState(),
    )

    fun updateViewport(viewport: GlobalHeatmapViewport) {
        if (viewport != viewportState.value) {
            viewportState.value = viewport
        }
    }

    fun updateViewportSize(
        widthPx: Int,
        heightPx: Int,
    ) {
        val current = viewportState.value
        if (current.widthPx == widthPx && current.heightPx == heightPx) {
            return
        }
        if (!current.hasSize()) {
            viewportState.value = defaultGlobalHeatmapViewport(widthPx, heightPx)
            return
        }
        viewportState.value = current.copy(
            widthPx = widthPx,
            heightPx = heightPx,
        )
    }

    fun resetViewport() {
        val current = viewportState.value
        viewportState.value = defaultGlobalHeatmapViewport(current.widthPx, current.heightPx)
    }

    private fun isValidGeoPoint(point: GeoMemoPoint): Boolean {
        return point.latitude.isFinite() &&
            point.longitude.isFinite() &&
            point.latitude in -90.0..90.0 &&
            point.longitude in -180.0..180.0
    }

    private fun toGeoPointSignature(point: GeoMemoPoint): GeoPointSignature {
        return GeoPointSignature(
            identifier = point.identifier,
            latitudeBits = point.latitude.toBits(),
            longitudeBits = point.longitude.toBits(),
            dateEpochMillis = point.date.toEpochMilli(),
        )
    }
}
