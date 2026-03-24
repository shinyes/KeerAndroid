package site.lcyk.keer.ui.component

import android.content.Context
import android.util.Log
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.net.toUri
import com.skydoves.sandwich.ApiResponse
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import site.lcyk.keer.data.local.entity.ResourceEntity
import site.lcyk.keer.data.local.FileStorage
import site.lcyk.keer.data.model.ResourceRepresentable
import site.lcyk.keer.data.security.EncryptedBlobVariant
import site.lcyk.keer.viewmodel.LocalMemos
import java.io.File

internal data class ObservedMemoResource(
    val resource: ResourceRepresentable,
    val tracked: Boolean,
)

@Composable
internal fun rememberObservedMemoResource(
    resource: ResourceRepresentable,
    observeLiveResource: Boolean = true,
): ObservedMemoResource {
    val memosViewModel = LocalMemos.current
    val identifier = (resource as? ResourceEntity)?.identifier?.trim().orEmpty().ifBlank { null }
    val resourceFlow = remember(identifier, observeLiveResource) {
        buildObservedResourceFlow(
            identifier = identifier,
            observeLiveResource = observeLiveResource,
        ) { trackedIdentifier ->
            memosViewModel.observeResource(trackedIdentifier)
        }
    }
    val observed by resourceFlow.collectAsState(initial = null)
    var lastObservedResource by remember(identifier) {
        mutableStateOf<ResourceRepresentable?>(resource.takeIf { identifier != null })
    }

    LaunchedEffect(observed) {
        if (observed != null) {
            lastObservedResource = observed
        }
    }

    return resolveObservedMemoResource(
        sourceResource = resource,
        observedResource = observed,
        lastObservedResource = lastObservedResource,
        observeLiveResource = observeLiveResource,
    )
}

internal fun resolveObservedMemoResource(
    sourceResource: ResourceRepresentable,
    observedResource: ResourceEntity?,
    lastObservedResource: ResourceRepresentable?,
    observeLiveResource: Boolean,
): ObservedMemoResource {
    val resolved = when {
        observedResource != null -> observedResource
        !observeLiveResource && lastObservedResource != null -> lastObservedResource
        lastObservedResource != null -> lastObservedResource
        else -> sourceResource
    }
    return ObservedMemoResource(
        resource = resolved,
        tracked = observedResource != null || lastObservedResource != null,
    )
}

internal data class StablePreviewModelState(
    val model: String?,
    val retainedModel: String?,
)

internal fun resolveStablePreviewModelState(
    candidateModel: String?,
    lastStableModel: String?,
): StablePreviewModelState {
    val normalizedCandidate = candidateModel?.trim()?.ifBlank { null }
    if (normalizedCandidate != null) {
        return StablePreviewModelState(
            model = normalizedCandidate,
            retainedModel = normalizedCandidate,
        )
    }
    val retained = lastStableModel?.trim()?.ifBlank { null }
    return StablePreviewModelState(
        model = retained,
        retainedModel = retained,
    )
}

internal fun buildObservedResourceFlow(
    identifier: String?,
    observeLiveResource: Boolean,
    observeResource: (String) -> Flow<ResourceEntity?>,
): Flow<ResourceEntity?> {
    if (!observeLiveResource) {
        return flowOf(null)
    }
    val normalizedIdentifier = identifier?.trim().orEmpty()
    if (normalizedIdentifier.isEmpty()) {
        return flowOf(null)
    }
    return observeResource(normalizedIdentifier)
}

internal object MemoResourcePreviewLoader {
    private val mutex = Mutex()
    private val inFlightKeys = mutableSetOf<String>()

    suspend fun run(key: String, block: suspend () -> Unit) {
        val acquired = mutex.withLock {
            if (key in inFlightKeys) {
                false
            } else {
                inFlightKeys += key
                true
            }
        }
        if (!acquired) {
            return
        }
        try {
            block()
        } finally {
            mutex.withLock {
                inFlightKeys.remove(key)
            }
        }
    }
}

internal suspend fun ensureMemoImageCardPreview(
    context: Context,
    okHttpClient: OkHttpClient,
    resource: ResourceEntity,
    currentAccountKey: String?,
    cacheResourceFile: suspend (String, Uri) -> ApiResponse<Unit>,
    cacheResourceThumbnail: suspend (String, Uri) -> ApiResponse<Unit>,
) {
    val previewKey = previewCacheKey(resource)
    resolveUsableThumbnailLocalUri(resource.thumbnailLocalUri)?.let { localThumbnail ->
        MediaPreviewRuntimeCache.rememberPreviewUri(previewKey, localThumbnail)
        return
    }
    resolveExistingLocalFileUri(resource.localUri)?.let { localMain ->
        MediaPreviewRuntimeCache.rememberPreviewUri(previewKey, localMain)
        return
    }

    val remoteThumbnail = resource.thumbnailUri?.trim().orEmpty()
    if (remoteThumbnail.isHttpUrl()) {
        MemoResourcePreviewLoader.run("thumb:${resource.identifier}") {
            downloadResourceVariantToTemp(
                context = context,
                okHttpClient = okHttpClient,
                resource = resource,
                accountKey = resolveResourceAccountKey(resource, currentAccountKey),
                url = remoteThumbnail,
                filename = resource.filename,
                variant = EncryptedBlobVariant.THUMBNAIL,
                cacheDirName = "thumbnail_cache",
                prefix = "thumb_",
            )?.let { downloaded ->
                try {
                    cacheResourceThumbnail(resource.identifier, downloaded.toUri())
                } finally {
                    downloaded.delete()
                }
            }
        }
        return
    }

    val remoteMain = resource.uri.trim()
    if (!remoteMain.isHttpUrl()) {
        return
    }
    MemoResourcePreviewLoader.run("main:${resource.identifier}") {
        downloadResourceVariantToTemp(
            context = context,
            okHttpClient = okHttpClient,
            resource = resource,
            accountKey = resolveResourceAccountKey(resource, currentAccountKey),
            url = remoteMain,
            filename = resource.filename,
            variant = EncryptedBlobVariant.MAIN,
            cacheDirName = "attachment_cache",
            prefix = "attachment_",
        )?.let { downloaded ->
            try {
                cacheResourceFile(resource.identifier, downloaded.toUri())
                if (resource.thumbnailLocalUri.isNullOrBlank()) {
                    generateThumbnailIfNeeded(
                        context = context,
                        resource = resource,
                        downloadedUri = downloaded.toUri(),
                        currentAccountKey = currentAccountKey,
                    )
                }
            } finally {
                downloaded.delete()
            }
        }
    }
}

internal suspend fun ensureMemoVideoCardPreview(
    context: Context,
    okHttpClient: OkHttpClient,
    resource: ResourceEntity,
    currentAccountKey: String?,
    cacheResourceThumbnail: suspend (String, Uri) -> ApiResponse<Unit>,
) {
    val previewKey = previewCacheKey(resource)
    resolveUsableThumbnailLocalUri(resource.thumbnailLocalUri)?.let { localThumbnail ->
        MediaPreviewRuntimeCache.rememberPreviewUri(previewKey, localThumbnail)
        return
    }
    val remoteThumbnail = resource.thumbnailUri?.trim().orEmpty()
    if (!remoteThumbnail.isHttpUrl()) {
        return
    }
    MemoResourcePreviewLoader.run("thumb:${resource.identifier}") {
        downloadResourceVariantToTemp(
            context = context,
            okHttpClient = okHttpClient,
            resource = resource,
            accountKey = resolveResourceAccountKey(resource, currentAccountKey),
            url = remoteThumbnail,
            filename = resource.filename,
            variant = EncryptedBlobVariant.THUMBNAIL,
            cacheDirName = "thumbnail_cache",
            prefix = "video_thumb_",
        )?.let { downloaded ->
            try {
                cacheResourceThumbnail(resource.identifier, downloaded.toUri())
            } finally {
                downloaded.delete()
            }
        }
    }
}


private suspend fun generateThumbnailIfNeeded(
    context: Context,
    resource: ResourceEntity,
    downloadedUri: Uri,
    currentAccountKey: String?,
) {
    val mimeType = resource.mimeType?.trim()?.lowercase() ?: return
    val accountKey = currentAccountKey?.trim()?.ifBlank { null } ?: return
    val fileStorage = FileStorage(context)
    val thumbnailFilename = "thumb_${resource.filename}"

    val thumbnailUri = when {
        mimeType.startsWith("image/") -> {
            fileStorage.saveImageThumbnailFromUri(
                accountKey = accountKey,
                sourceUri = downloadedUri,
                filename = thumbnailFilename,
            )
        }
        mimeType.startsWith("video/") -> {
            fileStorage.saveVideoThumbnailFromUri(
                accountKey = accountKey,
                sourceUri = downloadedUri,
                filename = thumbnailFilename,
            )
        }
        else -> null
    }

    thumbnailUri?.let { generatedThumb ->
        MediaPreviewRuntimeCache.rememberPreviewUri(previewCacheKey(resource), generatedThumb.toString())
        Log.d("MemoResourcePreview", "Generated thumbnail for ${resource.identifier}")
    }
}

private fun resolveExistingLocalFileUri(rawLocalUri: String?): String? {
    val local = rawLocalUri?.trim()?.ifBlank { null } ?: return null
    val uri = local.toUri()
    if (uri.scheme != "file") {
        return local
    }
    val file = uri.path?.let(::File)?.takeIf(File::exists) ?: return null
    return if (file.length() > 0L) local else null
}

