package site.lcyk.keer.ui.component

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
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
import coil3.annotation.ExperimentalCoilApi
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
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
) {
    val context = LocalContext.current
    val userStateViewModel = LocalUserState.current
    val currentAccount by userStateViewModel.currentAccount.collectAsState(initial = null)
    val memosViewModel = LocalMemos.current
    val observedResource = rememberObservedMemoResource(
        resource = resource,
        autoPreviewPrefetch = autoPreviewPrefetch,
    )
    val liveResource = observedResource.resource
    val scope = rememberCoroutineScope()
    var fallbackPreviewUri by remember(resource.remoteId, resource.uri) { mutableStateOf<String?>(null) }
    var opening by remember(resource.remoteId, resource.uri, resource.localUri) { mutableStateOf(false) }
    var viewerSelection by remember(resource.remoteId, resource.uri, resource.localUri) {
        mutableStateOf<ImageViewerSelectionState?>(null)
    }
    val imageLoader = rememberAuthorizedImageLoader()
    val runtimeCachedPreviewUri = MediaPreviewRuntimeCache.resolvePreviewUri(previewCacheKey(liveResource))
    val previewModel = remember(
        fallbackPreviewUri,
        runtimeCachedPreviewUri,
        liveResource.thumbnailLocalUri,
        liveResource.thumbnailUri,
        liveResource.localUri,
        liveResource.uri
    ) {
        fallbackPreviewUri ?: runtimeCachedPreviewUri ?: resolveMemoImagePreviewUri(liveResource)
    }

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
                }
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
                    val localFile = resolveAttachmentFile(
                        context = context,
                        resource = resolvedResource,
                        okHttpClient = userStateViewModel.okHttpClient,
                        currentAccountKey = currentAccount?.accountKey(),
                        cacheCanonical = { resourceIdentifier, downloadedUri ->
                            val result = memosViewModel.cacheResourceFile(resourceIdentifier, downloadedUri)
                            if (result is ApiResponse.Success) {
                                memosViewModel.getResourceById(resourceIdentifier)
                            } else {
                                null
                            }
                        }
                    ) ?: return@launch

                    val fileUri: Uri = KeerFileProvider.getFileUri(context, localFile)
                    val mimeType = "image/*"
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
}

private fun resolveMemoImagePreviewUri(resource: ResourceRepresentable): String {
    MediaPreviewRuntimeCache.resolvePreviewUri(previewCacheKey(resource))?.let { runtimePreview ->
        return runtimePreview
    }
    val localThumbnail = resolveUsableThumbnailLocalUri(resource.thumbnailLocalUri)
    if (!localThumbnail.isNullOrBlank()) {
        return localThumbnail
    }
    if (!resource.encryptionMetadata.isNullOrBlank()) {
        val local = resource.localUri?.trim().orEmpty()
        return local
    }
    val thumbnail = resource.thumbnailUri?.trim().orEmpty()
    if (thumbnail.isNotEmpty()) {
        return thumbnail
    }
    val local = resource.localUri?.trim().orEmpty()
    if (local.isNotEmpty()) {
        return local
    }
    return resource.uri
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
    return resolved
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
}
