package site.lcyk.keer.ui.component

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import java.io.File
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import com.skydoves.sandwich.ApiResponse
import coil3.ImageLoader
import coil3.annotation.ExperimentalCoilApi
import coil3.compose.AsyncImage
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import kotlinx.coroutines.launch
import site.lcyk.keer.viewmodel.MemosViewModel
import site.lcyk.keer.data.local.FileStorage
import site.lcyk.keer.KeerFileProvider
import site.lcyk.keer.R
import site.lcyk.keer.data.local.entity.ResourceEntity
import site.lcyk.keer.data.model.ResourceRepresentable
import site.lcyk.keer.ext.string
import site.lcyk.keer.viewmodel.LocalMemos
import site.lcyk.keer.viewmodel.LocalUserState
import timber.log.Timber

@OptIn(ExperimentalCoilApi::class)
@Composable
fun MemoImage(
    resource: ResourceRepresentable,
    modifier: Modifier = Modifier,
    autoPreviewPrefetch: Boolean = true,
    mediaImageLoader: ImageLoader? = null,
    observeLiveResource: Boolean = true,
) {
    val context = LocalContext.current
    val userStateViewModel = LocalUserState.current
    val currentAccount by userStateViewModel.currentAccount.collectAsState(initial = null)
    val memosViewModel = LocalMemos.current
    val observedResource = rememberObservedMemoResource(
        resource = resource,
        observeLiveResource = observeLiveResource,
    )
    val liveResource = observedResource.resource
    val scope = rememberCoroutineScope()
    var fallbackPreviewUri by remember(resource.remoteId, resource.uri) { mutableStateOf<String?>(null) }
    var opening by remember(resource.remoteId, resource.uri, resource.localUri) { mutableStateOf(false) }
    var viewerSelection by remember(resource.remoteId, resource.uri, resource.localUri) {
        mutableStateOf<ImageViewerSelectionState?>(null)
    }
    var imageOpenError by remember(resource.remoteId, resource.uri, resource.localUri) {
        mutableStateOf<ImageOpenErrorDialogState?>(null)
    }
    val imageLoader = mediaImageLoader ?: rememberMemoMediaImageLoader()
    val runtimeCachedPreviewUri = MediaPreviewRuntimeCache.resolvePreviewUri(previewCacheKey(liveResource))
    val previewIdentity = (liveResource as? ResourceEntity)?.identifier
        ?: "${liveResource.remoteId}|${liveResource.uri}"
    var lastStablePreviewModel by remember(previewIdentity) { mutableStateOf<String?>(null) }
    val previewCandidate = remember(
        fallbackPreviewUri,
        runtimeCachedPreviewUri,
        liveResource.thumbnailLocalUri,
        liveResource.thumbnailUri,
        liveResource.localUri,
        liveResource.uri
    ) {
        fallbackPreviewUri ?: runtimeCachedPreviewUri ?: resolveMemoImagePreviewUri(liveResource)
    }
    val previewModelState = remember(previewCandidate, lastStablePreviewModel) {
        resolveStablePreviewModelState(
            candidateModel = previewCandidate,
            lastStableModel = lastStablePreviewModel,
        )
    }
    LaunchedEffect(previewModelState.retainedModel) {
        if (previewModelState.retainedModel != lastStablePreviewModel) {
            lastStablePreviewModel = previewModelState.retainedModel
        }
    }
    val previewModel = previewModelState.model

    LaunchedEffect(
        autoPreviewPrefetch,
        (liveResource as? ResourceEntity)?.identifier,
        liveResource.thumbnailUri,
        liveResource.thumbnailLocalUri,
        liveResource.localUri,
        currentAccount?.accountKey()
    ) {
        if (!autoPreviewPrefetch) {
            // Keep tracked resources reactive via observeResource; only skip per-card tracked prefetch.
        }
        val resourceEntity = liveResource as? ResourceEntity
        if (resourceEntity != null) {
            if (!autoPreviewPrefetch) {
                return@LaunchedEffect
            }
            ensureMemoImageCardPreview(
                context = context,
                okHttpClient = userStateViewModel.okHttpClient,
                resource = resourceEntity,
                currentAccountKey = currentAccount?.accountKey(),
                cacheResourceFile = { identifier, downloadedUri ->
                    memosViewModel.cacheResourceFile(identifier, downloadedUri)
                },
                cacheResourceThumbnail = { identifier, downloadedUri ->
                    memosViewModel.cacheResourceThumbnail(identifier, downloadedUri)
                },
                updateResourceThumbnail = { identifier, localThumbnailUri ->
                    memosViewModel.updateResourceThumbnail(identifier, localThumbnailUri)
                },
            )
            return@LaunchedEffect
        }

        if (fallbackPreviewUri != null || liveResource.encryptionMetadata.isNullOrBlank()) {
            return@LaunchedEffect
        }
        val prefetched = MediaPreviewPrefetchCoordinator.prefetchUntrackedResourcePreview(
            context = context,
            okHttpClient = userStateViewModel.okHttpClient,
            resource = liveResource,
            currentAccountKey = currentAccount?.accountKey(),
        )
        if (!prefetched.isNullOrBlank()) {
            fallbackPreviewUri = prefetched
        }
    }

    Box(
        modifier = modifier.clickable(enabled = !opening) {
            if (opening) return@clickable
            scope.launch {
                opening = true
                try {
                    val resolvedResource = (liveResource as? ResourceEntity)?.let { entity ->
                        memosViewModel.getResourceById(entity.identifier) ?: liveResource
                    } ?: liveResource
                    val localFile = resolveMemoImageResource(
                        context = context,
                        resource = resolvedResource,
                        okHttpClient = userStateViewModel.okHttpClient,
                        currentAccountKey = currentAccount?.accountKey(),
                        memosViewModel = memosViewModel
                    )
                    if (localFile == null) {
                        imageOpenError = ImageOpenErrorDialogState(
                            titleResId = R.string.image_open_original_unavailable_title,
                            messageResId = R.string.image_open_original_unavailable_message
                        )
                        return@launch
                    }

                    val fileUri: Uri = KeerFileProvider.getFileUri(context, localFile)
                    val mimeType = resolveMemoImageViewerMimeType(resolvedResource, localFile)
                    if (mimeType == null) {
                        imageOpenError = ImageOpenErrorDialogState(
                            titleResId = R.string.image_open_unknown_mime_title,
                            messageResId = R.string.image_open_unknown_mime_message
                        )
                        return@launch
                    }
                    when (val launch = prepareImageViewerLaunch(context, fileUri, mimeType)) {
                        is ImageViewerLaunch.Direct -> {
                            context.startActivity(launch.intent)
                        }
                        is ImageViewerLaunch.Pick -> {
                            viewerSelection = ImageViewerSelectionState(
                                fileUri = fileUri,
                                mimeType = mimeType,
                                options = launch.options
                            )
                        }
                    }
                } catch (e: Throwable) {
                    Timber.d(e)
                    imageOpenError = ImageOpenErrorDialogState(
                        titleResId = R.string.image_open_failed_title,
                        messageResId = R.string.image_open_failed_message
                    )
                    return@launch
                } finally {
                    opening = false
                }
            }
        }
    ) {
        AsyncImage(
            model = previewModel,
            imageLoader = imageLoader,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            onError = {
                Timber.d("Failed to load memo image preview: %s", previewModel)
            }
        )

        if (opening) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.35f)),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        }
    }

    val selection = viewerSelection
    if (selection != null) {
        AlertDialog(
            onDismissRequest = { viewerSelection = null },
            title = { androidx.compose.material3.Text(R.string.choose_image_viewer.string) },
            text = {
                Column {
                    selection.options.forEach { option ->
                        androidx.compose.material3.TextButton(
                            onClick = {
                                savePreferredImageViewerPackage(context, option.packageName)
                                context.startActivity(
                                    buildImageViewerIntent(
                                        context = context,
                                        fileUri = selection.fileUri,
                                        mimeType = selection.mimeType,
                                        packageName = option.packageName
                                    )
                                )
                                viewerSelection = null
                            }
                        ) {
                            androidx.compose.material3.Text(option.label)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { viewerSelection = null }) {
                    androidx.compose.material3.Text(R.string.cancel.string)
                }
            }
        )
    }

    val openError = imageOpenError
    if (openError != null) {
        AlertDialog(
            onDismissRequest = { imageOpenError = null },
            title = { androidx.compose.material3.Text(openError.titleResId.string) },
            text = { androidx.compose.material3.Text(openError.messageResId.string) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { imageOpenError = null }) {
                    androidx.compose.material3.Text(R.string.confirm.string)
                }
            }
        )
    }
}

internal fun resolveMemoImagePreviewUri(resource: ResourceRepresentable): String {
    MediaPreviewRuntimeCache.resolvePreviewUri(previewCacheKey(resource))?.let { runtimePreview ->
        return runtimePreview
    }
    val localThumbnail = resolveUsableThumbnailLocalUri(resource.thumbnailLocalUri)
    if (!localThumbnail.isNullOrBlank()) {
        return localThumbnail
    }
    val thumbnail = resource.resolveUsableRemoteThumbnailUri().orEmpty()
    if (thumbnail.isNotEmpty()) {
        return thumbnail
    }
    val local = resource.localUri?.trim().orEmpty()
    if (local.isNotEmpty()) {
        return local
    }
    // Do not fallback to remote main blob in card previews.
    return ""
}

private const val IMAGE_VIEWER_PREFS_FILE = "image_viewer_preferences"
private const val PREFERRED_IMAGE_VIEWER_PACKAGE_KEY = "preferred_image_viewer_package"

private data class ImageViewerOption(
    val packageName: String,
    val label: String
)

private data class ImageViewerSelectionState(
    val fileUri: Uri,
    val mimeType: String,
    val options: List<ImageViewerOption>
)

private data class ImageOpenErrorDialogState(
    val titleResId: Int,
    val messageResId: Int
)

private sealed interface ImageViewerLaunch {
    data class Direct(val intent: Intent) : ImageViewerLaunch
    data class Pick(val options: List<ImageViewerOption>) : ImageViewerLaunch
}

private fun imageViewerPreferences(context: Context): SharedPreferences {
    return context.getSharedPreferences(IMAGE_VIEWER_PREFS_FILE, Context.MODE_PRIVATE)
}

private fun savePreferredImageViewerPackage(context: Context, packageName: String) {
    imageViewerPreferences(context)
        .edit()
        .putString(PREFERRED_IMAGE_VIEWER_PACKAGE_KEY, packageName)
        .apply()
}

private fun readPreferredImageViewerPackage(context: Context): String? {
    return imageViewerPreferences(context)
        .getString(PREFERRED_IMAGE_VIEWER_PACKAGE_KEY, null)
        ?.trim()
        ?.ifBlank { null }
}

private fun prepareImageViewerLaunch(
    context: Context,
    fileUri: Uri,
    mimeType: String
): ImageViewerLaunch {
    val options = queryImageViewerOptions(context, fileUri, mimeType)
    if (options.isEmpty()) {
        return ImageViewerLaunch.Direct(buildImageViewerIntent(context, fileUri, mimeType, null))
    }

    val preferredPackage = readPreferredImageViewerPackage(context)
    val preferredOption = preferredPackage?.let { packageName ->
        options.firstOrNull { it.packageName == packageName }
    }
    if (preferredOption != null) {
        return ImageViewerLaunch.Direct(
            buildImageViewerIntent(context, fileUri, mimeType, preferredOption.packageName)
        )
    }

    if (options.size == 1) {
        savePreferredImageViewerPackage(context, options[0].packageName)
        return ImageViewerLaunch.Direct(
            buildImageViewerIntent(context, fileUri, mimeType, options[0].packageName)
        )
    }

    return ImageViewerLaunch.Pick(options)
}

private fun buildImageViewerIntent(
    context: Context,
    fileUri: Uri,
    mimeType: String,
    packageName: String?
): Intent {
    return Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(fileUri, mimeType)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        clipData = ClipData.newUri(context.contentResolver, "image", fileUri)
        if (!packageName.isNullOrBlank()) {
            setPackage(packageName)
        }
    }
}

private fun queryImageViewerOptions(
    context: Context,
    fileUri: Uri,
    mimeType: String
): List<ImageViewerOption> {
    val packageManager = context.packageManager
    val intent = buildImageViewerIntent(context, fileUri, mimeType, null)
    val resolved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        packageManager.queryIntentActivities(
            intent,
            PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong())
        )
    } else {
        @Suppress("DEPRECATION")
        packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
    }
    val options = resolved
        .mapNotNull { info ->
            val packageName = info.activityInfo?.packageName?.trim().orEmpty()
            if (packageName.isEmpty()) {
                null
            } else {
                val label = info.loadLabel(packageManager).toString().trim()
                ImageViewerOption(
                    packageName = packageName,
                    label = label.ifBlank { packageName }
                )
            }
        }
        .distinctBy { it.packageName }
        .sortedBy { it.label.lowercase() }
    val browserPackages = queryBrowserPackages(context)
    val nonBrowserOptions = options.filterNot { option ->
        option.packageName in browserPackages
    }
    return if (nonBrowserOptions.isNotEmpty()) {
        nonBrowserOptions
    } else {
        options
    }
}

private fun queryBrowserPackages(context: Context): Set<String> {
    val packageManager = context.packageManager
    val browserIntent = Intent(Intent.ACTION_VIEW, "https://example.com/".toUri())
    val resolved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        packageManager.queryIntentActivities(
            browserIntent,
            PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong())
        )
    } else {
        @Suppress("DEPRECATION")
        packageManager.queryIntentActivities(browserIntent, PackageManager.MATCH_DEFAULT_ONLY)
    }
    return resolved
        .mapNotNull { info -> info.activityInfo?.packageName?.trim()?.ifBlank { null } }
        .toSet()
}

private fun resolveMemoImageViewerMimeType(
    resource: ResourceRepresentable,
    file: File
): String? {
    val resolvedMimeType = resolveMimeType(resource, file)
    if (resolvedMimeType.startsWith("image/")) {
        return resolvedMimeType
    }
    return null
}

private fun isLikelyThumbnailFile(
    resource: ResourceRepresentable,
    file: File
): Boolean {
    val normalizedPath = file.absolutePath.replace('\\', '/')
    if (normalizedPath.contains("/thumbnail_cache/")) {
        return true
    }
    val thumbnailPath = resource.thumbnailLocalUri
        ?.toUri()
        ?.takeIf { uri -> uri.scheme == "file" }
        ?.path
        ?.let(::File)
        ?.absolutePath
    if (!thumbnailPath.isNullOrBlank() && file.absolutePath == thumbnailPath) {
        return true
    }
    return file.name.contains(".thumb", ignoreCase = true)
}

/**
 * Resolve memo image file for click-to-open.
 * Always requires the original file and never falls back to thumbnail.
 */
private suspend fun resolveMemoImageResource(
    context: Context,
    resource: ResourceRepresentable,
    okHttpClient: OkHttpClient,
    currentAccountKey: String?,
    memosViewModel: MemosViewModel
): File? {
    resource.localUri
        ?.toUri()
        ?.takeIf { uri -> uri.scheme == "file" }
        ?.path
        ?.let(::File)
        ?.takeIf { file -> file.exists() && !isLikelyThumbnailFile(resource, file) }
        ?.let { localMain -> return localMain }

    resolveAttachmentFile(
        context = context,
        resource = resource,
        okHttpClient = okHttpClient,
        currentAccountKey = currentAccountKey,
        cacheCanonical = { resourceIdentifier, downloadedUri ->
            val result = memosViewModel.cacheResourceFile(resourceIdentifier, downloadedUri)
            if (result is ApiResponse.Success<Unit>) {
                // Return the updated resource
                memosViewModel.getResourceById(resourceIdentifier)
            } else {
                null
            }
        }
    )?.let { downloadedMain ->
        return downloadedMain
    }

    return null
}
