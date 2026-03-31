package site.lcyk.keer.ui.page.memoinput

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

data class MemoEditorLocationConfig(
    val targetPreciseLocationAccuracyMeters: Float = 25f,
    val targetCoarseLocationAccuracyMeters: Float = 80f,
    val maxAcceptableLocationAccuracyMeters: Float = 100f,
    val maxLocationAgeMillis: Long = 2 * 60 * 1000L,
    val submitLocationTimeoutMillis: Long = 650L,
    val prefetchLocationTimeoutMillis: Long = 9_000L,
    val networkTrackingMinTimeMillis: Long = 1_500L,
    val networkTrackingMinDistanceMeters: Float = 0f,
    val gnssTrackingMinTimeMillis: Long = 800L,
    val gnssTrackingMinDistanceMeters: Float = 0f,
)

data class MemoEditorLocationPermissionWorkflowState(
    val attemptSubmit: () -> Unit,
    val resetPendingSubmitRequest: () -> Unit,
)

internal val LOCATION_PERMISSIONS = arrayOf(
    Manifest.permission.ACCESS_FINE_LOCATION,
    Manifest.permission.ACCESS_COARSE_LOCATION,
)

internal fun hasLocationPermission(context: Context): Boolean {
    val fineLocationGranted = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_FINE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED
    val coarseLocationGranted = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED
    return fineLocationGranted || coarseLocationGranted
}

@Composable
fun rememberMemoEditorLocationPermissionWorkflowState(
    context: Context,
    locationState: site.lcyk.keer.viewmodel.MemoEditorLocationState,
    onPermissionGranted: () -> Unit = {},
    onSubmit: (collectCoordinates: Boolean) -> Unit,
): MemoEditorLocationPermissionWorkflowState {
    val latestLocationState = rememberUpdatedState(locationState)
    val latestOnPermissionGranted = rememberUpdatedState(onPermissionGranted)
    val latestOnSubmit = rememberUpdatedState(onSubmit)
    var pendingSubmitAfterPermission by remember { mutableStateOf(false) }

    val requestLocationPermissions = rememberLauncherForActivityResult(RequestMultiplePermissions()) { _ ->
        if (hasLocationPermission(context)) {
            latestOnPermissionGranted.value()
        }
        if (pendingSubmitAfterPermission) {
            pendingSubmitAfterPermission = false
            val submitState = site.lcyk.keer.viewmodel.buildMemoEditorLocationSubmitState(latestLocationState.value)
            latestOnSubmit.value(submitState.shouldCollectCoordinates)
        }
    }

    return remember {
        MemoEditorLocationPermissionWorkflowState(
            attemptSubmit = {
                val submitState = site.lcyk.keer.viewmodel.buildMemoEditorLocationSubmitState(latestLocationState.value)
                if (submitState.shouldRequestPermission) {
                    pendingSubmitAfterPermission = true
                    requestLocationPermissions.launch(LOCATION_PERMISSIONS)
                } else {
                    latestOnSubmit.value(submitState.shouldCollectCoordinates)
                }
            },
            resetPendingSubmitRequest = {
                pendingSubmitAfterPermission = false
            },
        )
    }
}

private fun hasPreciseLocationPermission(context: Context): Boolean {
    return ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_FINE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED
}

@SuppressLint("MissingPermission")
internal fun startPlatformLocationTracking(
    context: Context,
    config: MemoEditorLocationConfig,
    onLocation: (Location) -> Unit,
): (() -> Unit)? {
    val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        ?: return null
    val providers = resolveRealtimeTrackingProviders(locationManager)
    if (providers.isEmpty()) {
        return null
    }

    return runCatching<() -> Unit> {
        val listener = LocationListener { location ->
            if (isLocationFresh(location, config)) {
                onLocation(location)
            }
        }

        providers.forEach { provider ->
            locationManager.requestLocationUpdates(
                provider,
                config.networkTrackingMinTimeMillis,
                config.networkTrackingMinDistanceMeters,
                listener,
                Looper.getMainLooper(),
            )
            runCatching {
                locationManager.getLastKnownLocation(provider)
            }.getOrNull()?.let { candidate ->
                if (isLocationFresh(candidate, config)) {
                    onLocation(candidate)
                }
            }
        }

        val stopTracking: () -> Unit = {
            locationManager.removeUpdates(listener)
        }
        stopTracking
    }.getOrNull()
}

@SuppressLint("MissingPermission")
internal fun startGnssLocationTracking(
    context: Context,
    config: MemoEditorLocationConfig,
    onLocation: (Location) -> Unit,
): (() -> Unit)? {
    if (!hasPreciseLocationPermission(context)) {
        return null
    }
    val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        ?: return null
    val gpsEnabled = runCatching {
        locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
    }.getOrDefault(false)
    if (!gpsEnabled) {
        return null
    }

    return runCatching<() -> Unit> {
        val listener = LocationListener { location ->
            if (isLocationFresh(location, config)) {
                onLocation(location)
            }
        }

        locationManager.requestLocationUpdates(
            LocationManager.GPS_PROVIDER,
            config.gnssTrackingMinTimeMillis,
            config.gnssTrackingMinDistanceMeters,
            listener,
            Looper.getMainLooper(),
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                locationManager.getCurrentLocation(
                    LocationManager.GPS_PROVIDER,
                    null,
                    context.mainExecutor,
                ) { location ->
                    if (location != null && isLocationFresh(location, config)) {
                        onLocation(location)
                    }
                }
            }
        }

        val stopTracking: () -> Unit = {
            locationManager.removeUpdates(listener)
        }
        stopTracking
    }.getOrNull()
}

@SuppressLint("MissingPermission")
internal suspend fun getCurrentLocationBestEffort(
    context: Context,
    config: MemoEditorLocationConfig,
    maxWaitMillis: Long,
): Location? {
    val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        ?: return null
    val preferPreciseProvider = hasPreciseLocationPermission(context)
    val deadlineMillis = System.currentTimeMillis() + maxWaitMillis

    var bestLocation = getBestLastKnownLocation(
        locationManager = locationManager,
        preferPreciseProvider = preferPreciseProvider,
        config = config,
    )
    if (bestLocation != null && shouldStopSearching(bestLocation, preferPreciseProvider, config)) {
        return bestLocation.takeIf { isQualifiedLocation(it, config) }
    }

    val providers = resolveLocationProviders(
        locationManager = locationManager,
        preferPreciseProvider = preferPreciseProvider,
        fastFirst = true,
    )
    for (provider in providers) {
        val remainingMillis = remainingMillis(deadlineMillis)
        if (remainingMillis <= 0L) {
            break
        }

        val current = withTimeoutOrNull(minOf(providerTimeoutMillis(provider), remainingMillis)) {
            getCurrentLocationFromProvider(context, locationManager, provider)
        } ?: runCatching {
            locationManager.getLastKnownLocation(provider)
        }.getOrNull()

        if (current == null || !isLocationFresh(current, config)) {
            continue
        }
        bestLocation = pickMoreAccurateLocation(bestLocation, current)
        if (shouldStopSearching(bestLocation, preferPreciseProvider, config)) {
            break
        }
    }
    return bestLocation?.takeIf { isQualifiedLocation(it, config) }
}

@SuppressLint("MissingPermission")
private fun getBestLastKnownLocation(
    locationManager: LocationManager,
    preferPreciseProvider: Boolean,
    config: MemoEditorLocationConfig,
): Location? {
    var bestLocation: Location? = null
    val providers = resolveLocationProviders(
        locationManager = locationManager,
        preferPreciseProvider = preferPreciseProvider,
        fastFirst = true,
    )
    for (provider in providers) {
        val candidate = runCatching {
            locationManager.getLastKnownLocation(provider)
        }.getOrNull() ?: continue
        if (!isLocationFresh(candidate, config)) {
            continue
        }
        bestLocation = pickMoreAccurateLocation(bestLocation, candidate)
    }
    return bestLocation
}

private fun resolveRealtimeTrackingProviders(locationManager: LocationManager): List<String> {
    val preferredOrder = listOf(
        LocationManager.NETWORK_PROVIDER,
        LocationManager.PASSIVE_PROVIDER,
    )
    val enabledProviders = preferredOrder.filter { provider ->
        runCatching { locationManager.isProviderEnabled(provider) }.getOrDefault(false)
    }
    if (enabledProviders.isNotEmpty()) {
        return enabledProviders
    }
    return preferredOrder.filter { provider ->
        runCatching { locationManager.allProviders.contains(provider) }.getOrDefault(false)
    }
}

private fun resolveLocationProviders(
    locationManager: LocationManager,
    preferPreciseProvider: Boolean,
    fastFirst: Boolean = false,
): List<String> {
    val preferredOrder = when {
        preferPreciseProvider && fastFirst -> listOf(
            LocationManager.NETWORK_PROVIDER,
            LocationManager.GPS_PROVIDER,
            LocationManager.PASSIVE_PROVIDER,
        )
        preferPreciseProvider -> listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER,
        )
        else -> listOf(
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER,
        )
    }
    val enabledProviders = preferredOrder.filter { provider ->
        runCatching { locationManager.isProviderEnabled(provider) }.getOrDefault(false)
    }
    if (enabledProviders.isNotEmpty()) {
        return enabledProviders
    }
    return preferredOrder.filter { provider ->
        runCatching { locationManager.allProviders.contains(provider) }.getOrDefault(false)
    }
}

@SuppressLint("MissingPermission")
private suspend fun getCurrentLocationFromProvider(
    context: Context,
    locationManager: LocationManager,
    provider: String,
): Location? {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        return suspendCancellableCoroutine { continuation ->
            runCatching {
                locationManager.getCurrentLocation(provider, null, context.mainExecutor) { location ->
                    if (continuation.isActive) {
                        continuation.resume(location)
                    }
                }
            }.onFailure {
                if (continuation.isActive) {
                    continuation.resume(null)
                }
            }
        }
    }
    return runCatching {
        locationManager.getLastKnownLocation(provider)
    }.getOrNull()
}

private fun providerTimeoutMillis(provider: String): Long {
    return when (provider) {
        LocationManager.GPS_PROVIDER -> 4_000L
        LocationManager.NETWORK_PROVIDER -> 2_000L
        else -> 1_200L
    }
}

private fun remainingMillis(deadlineMillis: Long): Long {
    return (deadlineMillis - System.currentTimeMillis()).coerceAtLeast(0L)
}

internal fun pickMoreAccurateLocation(currentBest: Location?, candidate: Location): Location {
    val best = currentBest ?: return candidate
    val candidateAccuracy = if (candidate.accuracy > 0f) candidate.accuracy else Float.MAX_VALUE
    val bestAccuracy = if (best.accuracy > 0f) best.accuracy else Float.MAX_VALUE
    return when {
        candidateAccuracy + 12f < bestAccuracy -> candidate
        candidate.time > best.time + 45_000L && candidateAccuracy <= bestAccuracy + 20f -> candidate
        else -> best
    }
}

private fun shouldStopSearching(
    location: Location,
    preferPreciseProvider: Boolean,
    config: MemoEditorLocationConfig,
): Boolean {
    if (!location.hasAccuracy()) {
        return false
    }
    val target = if (preferPreciseProvider) {
        config.targetPreciseLocationAccuracyMeters
    } else {
        config.targetCoarseLocationAccuracyMeters
    }
    return location.accuracy <= target
}

internal fun isLocationFresh(
    location: Location,
    config: MemoEditorLocationConfig,
): Boolean {
    if (location.time <= 0L) {
        return false
    }
    val ageMillis = System.currentTimeMillis() - location.time
    return ageMillis in 0..config.maxLocationAgeMillis
}

internal fun isQualifiedLocation(
    location: Location,
    config: MemoEditorLocationConfig,
): Boolean {
    return location.hasAccuracy() &&
        location.accuracy <= config.maxAcceptableLocationAccuracyMeters &&
        isLocationFresh(location, config)
}
