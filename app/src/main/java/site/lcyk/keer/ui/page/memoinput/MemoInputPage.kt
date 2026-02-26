package site.lcyk.keer.ui.page.memoinput

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Looper
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.CaptureVideo
import androidx.activity.result.contract.ActivityResultContracts.OpenDocument
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions
import androidx.activity.result.contract.ActivityResultContracts.TakePicture
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.core.content.ContextCompat
import com.skydoves.sandwich.suspendOnSuccess
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import site.lcyk.keer.KeerFileProvider
import site.lcyk.keer.R
import site.lcyk.keer.data.model.MemoVisibility
import site.lcyk.keer.data.model.ShareContent
import site.lcyk.keer.ext.popBackStackIfLifecycleIsResumed
import site.lcyk.keer.ext.suspendOnErrorMessage
import site.lcyk.keer.ext.string
import site.lcyk.keer.ui.page.common.LocalRootNavController
import site.lcyk.keer.util.normalizeTagList
import site.lcyk.keer.util.normalizeTagName
import site.lcyk.keer.viewmodel.LocalMemos
import site.lcyk.keer.viewmodel.LocalUserState
import site.lcyk.keer.viewmodel.MemoInputViewModel
import kotlin.coroutines.resume

@Composable
fun MemoInputPage(
    viewModel: MemoInputViewModel = hiltViewModel(),
    memoIdentifier: String? = null,
    shareContent: ShareContent? = null
) {
    val focusRequester = remember { FocusRequester() }
    val coroutineScope = rememberCoroutineScope()
    val snackbarState = remember { SnackbarHostState() }
    val navController = LocalRootNavController.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val memosViewModel = LocalMemos.current
    val userStateViewModel = LocalUserState.current
    val currentAccount by userStateViewModel.currentAccount.collectAsState()
    val memo = remember { memosViewModel.memos.toList().find { it.identifier == memoIdentifier } }
    var initialContent by remember { mutableStateOf(memo?.content ?: "") }
    var initialTags by remember { mutableStateOf(normalizeTagList(memo?.tags ?: emptyList())) }
    var text by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(memo?.content ?: "", TextRange(memo?.content?.length ?: 0)))
    }
    var selectedTags by rememberSaveable { mutableStateOf(normalizeTagList(memo?.tags ?: emptyList())) }
    var visibilityMenuExpanded by remember { mutableStateOf(false) }
    var showTagSelector by remember { mutableStateOf(false) }
    var photoImageUri by remember { mutableStateOf<Uri?>(null) }
    var videoUri by remember { mutableStateOf<Uri?>(null) }
    var showExitConfirmation by remember { mutableStateOf(false) }
    var prefetchedLocation by remember { mutableStateOf<Location?>(null) }
    var isLocationPrefetching by remember { mutableStateOf(false) }
    var stopLocationTracking by remember { mutableStateOf<(() -> Unit)?>(null) }
    val normalizedSelectedTags = remember(selectedTags) { normalizeTagList(selectedTags) }
    val locationPermissions = remember {
        arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
    }
    var pendingSubmitAfterLocationPermission by remember { mutableStateOf(false) }

    val defaultVisibility = userStateViewModel.currentUser?.defaultVisibility ?: MemoVisibility.PRIVATE
    var currentVisibility by remember { mutableStateOf(memo?.visibility ?: defaultVisibility) }

    val validMimeTypePrefixes = remember {
        setOf("text/")
    }

    fun startLocationPrefetch(force: Boolean = false) {
        if (memo != null || !hasLocationPermission(navController.context)) {
            return
        }

        if (force) {
            stopLocationTracking?.invoke()
            stopLocationTracking = null
        } else if (stopLocationTracking != null) {
            return
        }

        val stopCallbacks = mutableListOf<() -> Unit>()
        startPlatformLocationTracking(navController.context) { candidate ->
            if (isLocationFresh(candidate)) {
                prefetchedLocation = pickMoreAccurateLocation(prefetchedLocation, candidate)
            }
        }?.let { stopCallbacks.add(it) }

        startGnssLocationTracking(navController.context) { candidate ->
            if (isLocationFresh(candidate)) {
                prefetchedLocation = pickMoreAccurateLocation(prefetchedLocation, candidate)
            }
        }?.let { stopCallbacks.add(it) }

        stopLocationTracking = if (stopCallbacks.isEmpty()) {
            null
        } else {
            {
                stopCallbacks.forEach { stop -> stop() }
            }
        }

        if (isLocationPrefetching) {
            return
        }

        isLocationPrefetching = true
        coroutineScope.launch {
            try {
                val location = getCurrentLocationBestEffort(
                    context = navController.context,
                    maxWaitMillis = PREFETCH_LOCATION_TIMEOUT_MILLIS
                )
                if (location != null && isQualifiedLocation(location)) {
                    prefetchedLocation = pickMoreAccurateLocation(prefetchedLocation, location)
                }
            } finally {
                isLocationPrefetching = false
            }
        }
    }

    fun submit(collectCoordinates: Boolean = true) = coroutineScope.launch {
        if (viewModel.hasActiveUpload()) {
            snackbarState.showSnackbar(R.string.upload_in_progress_wait.string)
            return@launch
        }

        memo?.let {
            viewModel.editMemo(memo.identifier, text.text, currentVisibility, normalizedSelectedTags).suspendOnSuccess {
                memosViewModel.refreshLocalSnapshot()
                navController.popBackStack()
            }.suspendOnErrorMessage { message ->
                snackbarState.showSnackbar(message)
            }
            return@launch
        }

        val location = if (collectCoordinates && hasLocationPermission(navController.context)) {
            val cached = prefetchedLocation?.takeIf(::isQualifiedLocation)
            cached ?: getCurrentLocationBestEffort(
                context = navController.context,
                maxWaitMillis = SUBMIT_LOCATION_TIMEOUT_MILLIS
            )
                ?.takeIf(::isQualifiedLocation)
                ?.also { fresh ->
                    prefetchedLocation = pickMoreAccurateLocation(prefetchedLocation, fresh)
                }
        } else {
            null
        }
        viewModel.createMemo(
            content = text.text,
            visibility = currentVisibility,
            tags = normalizedSelectedTags,
            latitude = location?.latitude,
            longitude = location?.longitude
        ).suspendOnSuccess {
            text = TextFieldValue("")
            selectedTags = emptyList()
            viewModel.updateDraft("")
            memosViewModel.refreshLocalSnapshot()
            navController.popBackStack()
        }.suspendOnErrorMessage { message ->
            snackbarState.showSnackbar(message)
        }
    }

    fun handleExit() {
        if (viewModel.hasActiveUpload()) {
            coroutineScope.launch {
                snackbarState.showSnackbar(R.string.upload_in_progress_wait.string)
            }
            return
        }
        if (text.text != initialContent || normalizedSelectedTags != initialTags || viewModel.uploadResources.size != (memo?.resources?.size ?: 0)) {
            showExitConfirmation = true
        } else {
            navController.popBackStackIfLifecycleIsResumed(lifecycleOwner)
        }
    }

    fun uploadResource(uri: Uri) = coroutineScope.launch {
        viewModel.upload(uri, memo?.identifier).suspendOnSuccess {
            delay(300)
            focusRequester.requestFocus()
        }.suspendOnErrorMessage { message ->
            snackbarState.showSnackbar(message)
        }
    }

    val pickImage = rememberLauncherForActivityResult(PickVisualMedia()) { uri ->
        uri?.let { uploadResource(it) }
    }

    val takePhoto = rememberLauncherForActivityResult(TakePicture()) { success ->
        if (success) {
            photoImageUri?.let { uploadResource(it) }
        }
    }

    val captureVideo = rememberLauncherForActivityResult(CaptureVideo()) { success ->
        if (success) {
            videoUri?.let { uploadResource(it) }
        }
    }

    val requestLocationPermissions = rememberLauncherForActivityResult(RequestMultiplePermissions()) { _ ->
        if (hasLocationPermission(navController.context)) {
            startLocationPrefetch(force = true)
        }
        if (pendingSubmitAfterLocationPermission) {
            pendingSubmitAfterLocationPermission = false
            submit()
        }
    }

    fun launchTakePhoto() {
        try {
            val uri = KeerFileProvider.getImageUri(navController.context)
            photoImageUri = uri
            takePhoto.launch(uri)
        } catch (e: ActivityNotFoundException) {
            coroutineScope.launch {
                snackbarState.showSnackbar(e.localizedMessage ?: "Unable to take picture.")
            }
        }
    }

    fun launchCaptureVideo() {
        try {
            val uri = KeerFileProvider.getVideoUri(navController.context)
            videoUri = uri
            captureVideo.launch(uri)
        } catch (e: ActivityNotFoundException) {
            coroutineScope.launch {
                snackbarState.showSnackbar(e.localizedMessage ?: R.string.unable_to_record_video.string)
            }
        }
    }

    val pickAttachment = rememberLauncherForActivityResult(OpenDocument()) { uri ->
        uri?.let {
            coroutineScope.launch {
                viewModel.upload(it, memo?.identifier).suspendOnErrorMessage { message ->
                    snackbarState.showSnackbar(message)
                }
            }
        }
    }

    fun attemptSubmit() {
        if (memo == null && !hasLocationPermission(navController.context)) {
            pendingSubmitAfterLocationPermission = true
            requestLocationPermissions.launch(locationPermissions)
        } else {
            submit()
        }
    }

    BackHandler {
        handleExit()
    }

    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            MemoInputTopBar(
                isEditMode = memo != null,
                canSubmit = (text.text.isNotEmpty() || viewModel.uploadResources.isNotEmpty()) && !viewModel.hasActiveUpload(),
                onClose = { handleExit() },
                onSubmit = { attemptSubmit() }
            )
        },
        bottomBar = {
            MemoInputBottomBar(
                currentAccount = currentAccount,
                currentVisibility = currentVisibility,
                visibilityMenuExpanded = visibilityMenuExpanded,
                onVisibilityExpandedChange = { visibilityMenuExpanded = it },
                onVisibilitySelected = { currentVisibility = it },
                selectedTags = selectedTags,
                selectedTagCount = normalizedSelectedTags.size,
                onTagSelectorClick = {
                    showTagSelector = true
                },
                onTagRemove = { tagToRemove ->
                    val normalizedTagToRemove = normalizeTagName(tagToRemove)
                    selectedTags = normalizeTagList(
                        selectedTags.filterNot { normalizeTagName(it) == normalizedTagToRemove }
                    )
                },
                onToggleTodoItem = {
                    text = toggleTodoItemInText(text)
                },
                onPickImage = {
                    pickImage.launch(PickVisualMediaRequest(PickVisualMedia.ImageAndVideo))
                },
                onPickAttachment = {
                    pickAttachment.launch(arrayOf("*/*"))
                },
                onTakePhoto = {
                    launchTakePhoto()
                },
                onTakeVideo = {
                    launchCaptureVideo()
                },
                onFormat = { format ->
                    text = applyMarkdownFormatToText(text, format)
                }
            )
        },
        snackbarHost = {
            SnackbarHost(hostState = snackbarState)
        }
    ) { innerPadding ->
        MemoInputEditor(
            modifier = Modifier.padding(innerPadding),
            text = text,
            onTextChange = { updated ->
                if (
                    text.text != updated.text &&
                    updated.selection.start == updated.selection.end &&
                    updated.text.length == text.text.length + 1 &&
                    updated.selection.start > 0 &&
                    updated.text[updated.selection.start - 1] == '\n'
                ) {
                    val handled = handleEnterInText(text)
                    if (handled != null) {
                        text = handled
                        return@MemoInputEditor
                    }
                }
                text = updated
            },
            focusRequester = focusRequester,
            validMimeTypePrefixes = validMimeTypePrefixes,
            onDroppedText = { droppedText ->
                text = text.copy(text = text.text + droppedText)
            },
            uploadResources = viewModel.uploadResources.toList(),
            inputViewModel = viewModel,
            uploadTasks = viewModel.uploadTasks.toList(),
            onDismissUploadTask = { taskId -> viewModel.dismissUploadTask(taskId) }
        )
    }

    if (showTagSelector) {
        MemoTagSelectorDialog(
            availableTags = memosViewModel.tags.toList(),
            selectedTags = selectedTags,
            onSelectedTagsChange = { selectedTags = normalizeTagList(it) },
            onDismiss = { showTagSelector = false }
        )
    }

    if (showExitConfirmation) {
        SaveChangesDialog(
            onSave = {
                showExitConfirmation = false
                attemptSubmit()
            },
            onDiscard = {
                showExitConfirmation = false
                text = TextFieldValue("")
                navController.popBackStackIfLifecycleIsResumed(lifecycleOwner)
            },
            onDismiss = {
                showExitConfirmation = false
            }
        )
    }

    LaunchedEffect(Unit) {
        viewModel.uploadResources.clear()
        viewModel.uploadTasks.clear()
        memosViewModel.loadTags()
        when {
            memo != null -> {
                viewModel.uploadResources.addAll(memo.resources)
                initialContent = memo.content
                initialTags = normalizeTagList(memo.tags)
                selectedTags = normalizeTagList(memo.tags)
            }

            shareContent != null -> {
                text = TextFieldValue(shareContent.text, TextRange(shareContent.text.length))
                for (item in shareContent.images) {
                    uploadResource(item)
                }
            }

            else -> {
                viewModel.draft.first()?.let {
                    text = TextFieldValue(it, TextRange(it.length))
                }
            }
        }
        startLocationPrefetch(force = true)
        delay(300)
        focusRequester.requestFocus()
    }

    DisposableEffect(Unit) {
        onDispose {
            stopLocationTracking?.invoke()
            if (memo == null && shareContent == null) {
                viewModel.updateDraft(text.text)
            }
        }
    }
}

private fun hasLocationPermission(context: Context): Boolean {
    val fineLocationGranted = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED
    val coarseLocationGranted = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_COARSE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED
    return fineLocationGranted || coarseLocationGranted
}

@SuppressLint("MissingPermission")
private fun startPlatformLocationTracking(
    context: Context,
    onLocation: (Location) -> Unit
): (() -> Unit)? {
    val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        ?: return null
    val providers = resolveRealtimeTrackingProviders(locationManager)
    if (providers.isEmpty()) {
        return null
    }

    return runCatching<() -> Unit> {
        val listener = LocationListener { location ->
            if (isLocationFresh(location)) {
                onLocation(location)
            }
        }

        providers.forEach { provider ->
            locationManager.requestLocationUpdates(
                provider,
                NETWORK_TRACKING_MIN_TIME_MILLIS,
                NETWORK_TRACKING_MIN_DISTANCE_METERS,
                listener,
                Looper.getMainLooper()
            )
            runCatching {
                locationManager.getLastKnownLocation(provider)
            }.getOrNull()?.let { candidate ->
                if (isLocationFresh(candidate)) {
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
private fun startGnssLocationTracking(
    context: Context,
    onLocation: (Location) -> Unit
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
            if (isLocationFresh(location)) {
                onLocation(location)
            }
        }

        locationManager.requestLocationUpdates(
            LocationManager.GPS_PROVIDER,
            GNSS_TRACKING_MIN_TIME_MILLIS,
            GNSS_TRACKING_MIN_DISTANCE_METERS,
            listener,
            Looper.getMainLooper()
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                locationManager.getCurrentLocation(
                    LocationManager.GPS_PROVIDER,
                    null,
                    context.mainExecutor
                ) { location ->
                    if (location != null && isLocationFresh(location)) {
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
private suspend fun getCurrentLocationBestEffort(
    context: Context,
    maxWaitMillis: Long
): Location? {
    val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        ?: return null
    val preferPreciseProvider = hasPreciseLocationPermission(context)
    val deadlineMillis = System.currentTimeMillis() + maxWaitMillis

    var bestLocation = getBestLastKnownLocation(locationManager, preferPreciseProvider)
    if (bestLocation != null && shouldStopSearching(bestLocation, preferPreciseProvider)) {
        return bestLocation.takeIf(::isQualifiedLocation)
    }

    val providers = resolveLocationProviders(
        locationManager = locationManager,
        preferPreciseProvider = preferPreciseProvider,
        fastFirst = true
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

        if (current == null || !isLocationFresh(current)) {
            continue
        }
        bestLocation = pickMoreAccurateLocation(bestLocation, current)
        if (shouldStopSearching(bestLocation, preferPreciseProvider)) {
            break
        }
    }
    return bestLocation?.takeIf(::isQualifiedLocation)
}

@SuppressLint("MissingPermission")
private fun getBestLastKnownLocation(
    locationManager: LocationManager,
    preferPreciseProvider: Boolean
): Location? {
    var bestLocation: Location? = null
    val providers = resolveLocationProviders(
        locationManager = locationManager,
        preferPreciseProvider = preferPreciseProvider,
        fastFirst = true
    )
    for (provider in providers) {
        val candidate = runCatching {
            locationManager.getLastKnownLocation(provider)
        }.getOrNull() ?: continue
        if (!isLocationFresh(candidate)) {
            continue
        }
        bestLocation = pickMoreAccurateLocation(bestLocation, candidate)
    }
    return bestLocation
}

private fun resolveRealtimeTrackingProviders(locationManager: LocationManager): List<String> {
    val preferredOrder = listOf(
        LocationManager.NETWORK_PROVIDER,
        LocationManager.PASSIVE_PROVIDER
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
    fastFirst: Boolean = false
): List<String> {
    val preferredOrder = when {
        preferPreciseProvider && fastFirst -> listOf(
            LocationManager.NETWORK_PROVIDER,
            LocationManager.GPS_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        )
        preferPreciseProvider -> listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        )
        else -> listOf(
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
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
    provider: String
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

private fun pickMoreAccurateLocation(currentBest: Location?, candidate: Location): Location {
    val best = currentBest ?: return candidate
    val candidateAccuracy = if (candidate.accuracy > 0f) candidate.accuracy else Float.MAX_VALUE
    val bestAccuracy = if (best.accuracy > 0f) best.accuracy else Float.MAX_VALUE
    return when {
        candidateAccuracy + 12f < bestAccuracy -> candidate
        candidate.time > best.time + 45_000L && candidateAccuracy <= bestAccuracy + 20f -> candidate
        else -> best
    }
}

private fun hasPreciseLocationPermission(context: Context): Boolean {
    return ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED
}

private fun shouldStopSearching(location: Location, preferPreciseProvider: Boolean): Boolean {
    if (!location.hasAccuracy()) {
        return false
    }
    val target = if (preferPreciseProvider) {
        TARGET_PRECISE_LOCATION_ACCURACY_METERS
    } else {
        TARGET_COARSE_LOCATION_ACCURACY_METERS
    }
    return location.accuracy <= target
}

private fun isLocationFresh(location: Location): Boolean {
    if (location.time <= 0L) {
        return false
    }
    val ageMillis = System.currentTimeMillis() - location.time
    return ageMillis in 0..MAX_LOCATION_AGE_MILLIS
}

private fun isQualifiedLocation(location: Location): Boolean {
    return location.hasAccuracy() &&
            location.accuracy <= MAX_ACCEPTABLE_LOCATION_ACCURACY_METERS &&
            isLocationFresh(location)
}

private const val TARGET_PRECISE_LOCATION_ACCURACY_METERS = 25f
private const val TARGET_COARSE_LOCATION_ACCURACY_METERS = 80f
private const val MAX_ACCEPTABLE_LOCATION_ACCURACY_METERS = 100f
private const val MAX_LOCATION_AGE_MILLIS = 2 * 60 * 1000L
private const val SUBMIT_LOCATION_TIMEOUT_MILLIS = 650L
private const val PREFETCH_LOCATION_TIMEOUT_MILLIS = 9_000L
private const val NETWORK_TRACKING_MIN_TIME_MILLIS = 1_500L
private const val NETWORK_TRACKING_MIN_DISTANCE_METERS = 0f
private const val GNSS_TRACKING_MIN_TIME_MILLIS = 800L
private const val GNSS_TRACKING_MIN_DISTANCE_METERS = 0f
