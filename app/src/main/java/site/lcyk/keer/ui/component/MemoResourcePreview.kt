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
import site.lcyk.keer.data.security.AttachmentEncryptionManager
import site.lcyk.keer.data.security.EncryptedBlobVariant
import site.lcyk.keer.viewmodel.LocalMemos
import java.io.File
import java.util.UUID

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

internal fun ResourceEntity.isUntrackedMemoScope(): Boolean {
    val memoKey = memoId?.trim().orEmpty()
    return memoKey.startsWith("explore:") || memoKey.startsWith("group:")
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

internal enum class PreviewEnsureSource {
    LOCAL_THUMB,
    LOCAL_MAIN,
    REMOTE_THUMB,
    MAIN_FALLBACK,
    MAIN_FALLBACK_SKIPPED,
    NONE,
}

internal data class PreviewEnsureResult(
    val source: PreviewEnsureSource,
    val mainFallbackAttempted: Boolean = false,
    val previewReady: Boolean = false,
)

internal suspend fun ensureMemoImageCardPreview(
    context: Context,
    okHttpClient: OkHttpClient,
    resource: ResourceEntity,
    currentAccountKey: String?,
    cacheResourceFile: suspend (String, Uri) -> ApiResponse<Unit>,
    cacheResourceThumbnail: suspend (String, Uri) -> ApiResponse<Unit>,
    updateResourceThumbnail: suspend (String, String) -> ApiResponse<Unit>,
    allowMainFallback: Boolean = true,
): PreviewEnsureResult {
    val previewKey = previewCacheKey(resource)
    resolveUsableThumbnailLocalUri(resource.thumbnailLocalUri)?.let { localThumbnail ->
        MediaPreviewRuntimeCache.rememberPreviewUri(previewKey, localThumbnail)
        enqueueThumbnailUploadIfRemoteMissing(
            resource = resource,
            localThumbnailUri = localThumbnail,
            updateResourceThumbnail = updateResourceThumbnail,
        )
        return PreviewEnsureResult(
            source = PreviewEnsureSource.LOCAL_THUMB,
            previewReady = true,
        )
    }
    resolveExistingLocalFileUri(resource.localUri)?.let { localMain ->
        MediaPreviewRuntimeCache.rememberPreviewUri(previewKey, localMain)
        val generatedFromLocalMain = generateThumbnailFromExistingLocalMainIfNeeded(
            context = context,
            resource = resource,
            localMainUri = localMain,
            currentAccountKey = currentAccountKey,
            updateResourceThumbnail = updateResourceThumbnail,
        )
        return PreviewEnsureResult(
            source = if (generatedFromLocalMain != null) {
                PreviewEnsureSource.LOCAL_THUMB
            } else {
                PreviewEnsureSource.LOCAL_MAIN
            },
            previewReady = true,
        )
    }

    val remoteThumbnail = resource.thumbnailUri?.trim().orEmpty()
    if (remoteThumbnail.isHttpUrl()) {
        var downloadedAndCached = false
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
                    downloadedAndCached = true
                } finally {
                    downloaded.delete()
                }
            }
        }
        return PreviewEnsureResult(
            source = PreviewEnsureSource.REMOTE_THUMB,
            previewReady = downloadedAndCached,
        )
    }

    if (!allowMainFallback) {
        return PreviewEnsureResult(
            source = PreviewEnsureSource.MAIN_FALLBACK_SKIPPED,
        )
    }

    val remoteMain = resource.uri.trim()
    if (resource.isUntrackedMemoScope()) {
        return PreviewEnsureResult(
            source = PreviewEnsureSource.MAIN_FALLBACK_SKIPPED,
        )
    }
    if (!remoteMain.isHttpUrl()) {
        return PreviewEnsureResult(
            source = PreviewEnsureSource.NONE,
        )
    }
    var attemptedMainFallback = false
    var generatedOrCachedPreview = false
    MemoResourcePreviewLoader.run("main:${resource.identifier}") {
        attemptedMainFallback = true
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
                generatedOrCachedPreview = true
                val generatedThumbnailUri = if (resolveUsableThumbnailLocalUri(resource.thumbnailLocalUri).isNullOrBlank()) {
                    generateThumbnailIfNeeded(
                        context = context,
                        resource = resource,
                        downloadedUri = downloaded.toUri(),
                        currentAccountKey = currentAccountKey,
                    )
                } else {
                    null
                }
                if (generatedThumbnailUri != null) {
                    persistGeneratedThumbnail(
                        resource = resource,
                        generatedThumbnailUri = generatedThumbnailUri,
                        updateResourceThumbnail = updateResourceThumbnail,
                    )
                    generatedOrCachedPreview = true
                }
            } finally {
                downloaded.delete()
            }
        }
    }
    return PreviewEnsureResult(
        source = PreviewEnsureSource.MAIN_FALLBACK,
        mainFallbackAttempted = attemptedMainFallback,
        previewReady = generatedOrCachedPreview,
    )
}

internal suspend fun ensureMemoVideoCardPreview(
    context: Context,
    okHttpClient: OkHttpClient,
    resource: ResourceEntity,
    currentAccountKey: String?,
    cacheResourceThumbnail: suspend (String, Uri) -> ApiResponse<Unit>,
    updateResourceThumbnail: suspend (String, String) -> ApiResponse<Unit>,
    allowMainFallback: Boolean = true,
): PreviewEnsureResult {
    val previewKey = previewCacheKey(resource)
    resolveUsableThumbnailLocalUri(resource.thumbnailLocalUri)?.let { localThumbnail ->
        MediaPreviewRuntimeCache.rememberPreviewUri(previewKey, localThumbnail)
        enqueueThumbnailUploadIfRemoteMissing(
            resource = resource,
            localThumbnailUri = localThumbnail,
            updateResourceThumbnail = updateResourceThumbnail,
        )
        return PreviewEnsureResult(
            source = PreviewEnsureSource.LOCAL_THUMB,
            previewReady = true,
        )
    }
    resolveExistingLocalFileUri(resource.localUri)?.let { localMain ->
        val generatedFromLocalMain = generateThumbnailFromExistingLocalMainIfNeeded(
            context = context,
            resource = resource,
            localMainUri = localMain,
            currentAccountKey = currentAccountKey,
            updateResourceThumbnail = updateResourceThumbnail,
        )
        MediaPreviewRuntimeCache.rememberPreviewUri(previewKey, localMain)
        return PreviewEnsureResult(
            source = if (generatedFromLocalMain != null) {
                PreviewEnsureSource.LOCAL_THUMB
            } else {
                PreviewEnsureSource.LOCAL_MAIN
            },
            previewReady = true,
        )
    }
    val remoteThumbnail = resource.thumbnailUri?.trim().orEmpty()
    if (remoteThumbnail.isHttpUrl()) {
        var downloadedAndCached = false
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
                    downloadedAndCached = true
                } finally {
                    downloaded.delete()
                }
            }
        }
        return PreviewEnsureResult(
            source = PreviewEnsureSource.REMOTE_THUMB,
            previewReady = downloadedAndCached,
        )
    }

    if (!allowMainFallback) {
        return PreviewEnsureResult(
            source = PreviewEnsureSource.MAIN_FALLBACK_SKIPPED,
        )
    }

    val remoteMain = resource.uri.trim()
    if (resource.isUntrackedMemoScope()) {
        return PreviewEnsureResult(
            source = PreviewEnsureSource.MAIN_FALLBACK_SKIPPED,
        )
    }
    if (!remoteMain.isHttpUrl()) {
        return PreviewEnsureResult(
            source = PreviewEnsureSource.NONE,
        )
    }
    var attemptedMainFallback = false
    var generatedPreview = false
    MemoResourcePreviewLoader.run("video-main:${resource.identifier}") {
        attemptedMainFallback = true
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
                val generatedThumbnailUri = if (resolveUsableThumbnailLocalUri(resource.thumbnailLocalUri).isNullOrBlank()) {
                    generateThumbnailIfNeeded(
                        context = context,
                        resource = resource,
                        downloadedUri = downloaded.toUri(),
                        currentAccountKey = currentAccountKey,
                    )
                } else {
                    null
                }
                if (generatedThumbnailUri != null) {
                    persistGeneratedThumbnail(
                        resource = resource,
                        generatedThumbnailUri = generatedThumbnailUri,
                        updateResourceThumbnail = updateResourceThumbnail,
                    )
                    generatedPreview = true
                }
            } finally {
                downloaded.delete()
            }
        }
    }
    return PreviewEnsureResult(
        source = PreviewEnsureSource.MAIN_FALLBACK,
        mainFallbackAttempted = attemptedMainFallback,
        previewReady = generatedPreview,
    )
}

private suspend fun enqueueThumbnailUploadIfRemoteMissing(
    resource: ResourceEntity,
    localThumbnailUri: String,
    updateResourceThumbnail: suspend (String, String) -> ApiResponse<Unit>,
) {
    if (resource.remoteId?.trim().isNullOrEmpty()) {
        return
    }
    if (!resource.thumbnailUri?.trim().isNullOrEmpty()) {
        return
    }
    if (localThumbnailUri.isBlank()) {
        return
    }
    updateResourceThumbnail(resource.identifier, localThumbnailUri)
}

private suspend fun generateThumbnailFromExistingLocalMainIfNeeded(
    context: Context,
    resource: ResourceEntity,
    localMainUri: String,
    currentAccountKey: String?,
    updateResourceThumbnail: suspend (String, String) -> ApiResponse<Unit>,
): Uri? {
    if (!resolveUsableThumbnailLocalUri(resource.thumbnailLocalUri).isNullOrBlank()) {
        return null
    }
    val remoteThumbnail = resource.thumbnailUri?.trim().orEmpty()
    if (remoteThumbnail.isHttpUrl()) {
        return null
    }
    val sourceUri = localMainUri.toUri()
    if (sourceUri.scheme != "file") {
        return null
    }

    var generatedThumbnailUri: Uri? = null
    MemoResourcePreviewLoader.run("local-thumb:${resource.identifier}") {
        generatedThumbnailUri = generateThumbnailIfNeeded(
            context = context,
            resource = resource,
            downloadedUri = sourceUri,
            currentAccountKey = currentAccountKey,
        )
        generatedThumbnailUri?.let { generated ->
            persistGeneratedThumbnail(
                resource = resource,
                generatedThumbnailUri = generated,
                updateResourceThumbnail = updateResourceThumbnail,
            )
        }
    }
    return generatedThumbnailUri
}


private suspend fun generateThumbnailIfNeeded(
    context: Context,
    resource: ResourceEntity,
    downloadedUri: Uri,
    currentAccountKey: String?,
): Uri? {
    val mimeType = resolveThumbnailSourceMimeType(resource, downloadedUri.toString()) ?: return null
    val accountKey = resolveResourceAccountKey(resource, currentAccountKey) ?: return null
    val fileStorage = FileStorage(context)
    val thumbnailFilename = buildGeneratedThumbnailFilename(
        resource = resource,
        mimeType = mimeType,
    )

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
    return thumbnailUri
}

private fun resolveThumbnailSourceMimeType(resource: ResourceEntity, downloadedPath: String): String? {
    val decryptedMimeType = AttachmentEncryptionManager.resolveOriginalMimeType(
        resource.encryptionMetadata,
        resource.mimeType,
    )
        ?.trim()
        ?.lowercase()
        ?.takeIf { mime -> mime.startsWith("image/") || mime.startsWith("video/") }
    if (decryptedMimeType != null) {
        return decryptedMimeType
    }

    val explicitMimeType = resource.mimeType
        ?.trim()
        ?.lowercase()
        ?.takeIf { mime -> mime.startsWith("image/") || mime.startsWith("video/") }
    if (explicitMimeType != null) {
        return explicitMimeType
    }
    return inferMimeTypeFromFilename(resource.filename)
        ?: inferMimeTypeFromPath(downloadedPath)
        ?: when {
            resource.isImageResource() -> "image/jpeg"
            resource.isVideoResource() -> "video/mp4"
            else -> null
        }
}

internal fun resolveThumbnailSourceMimeTypeForTest(
    resource: ResourceEntity,
    downloadedPath: String,
): String? {
    return resolveThumbnailSourceMimeType(resource, downloadedPath)
}

private fun inferMimeTypeFromFilename(filename: String): String? {
    val extension = filename
        .substringAfterLast('.', "")
        .lowercase()
    return extensionMimeTypeMap[extension]
}

private fun inferMimeTypeFromPath(path: String): String? {
    val extension = path
        .substringBefore('?')
        .substringAfterLast('.', "")
        .lowercase()
    return extensionMimeTypeMap[extension]
}

private suspend fun persistGeneratedThumbnail(
    resource: ResourceEntity,
    generatedThumbnailUri: Uri,
    updateResourceThumbnail: suspend (String, String) -> ApiResponse<Unit>,
) {
    val persisted = updateResourceThumbnail(resource.identifier, generatedThumbnailUri.toString())
    if (persisted !is ApiResponse.Success) {
        Log.d("MemoResourcePreview", "Failed to persist generated thumbnail for ${resource.identifier}")
    }
}

private fun buildGeneratedThumbnailFilename(resource: ResourceEntity, mimeType: String): String {
    val stableToken = resource.identifier
        .trim()
        .ifEmpty { resource.filename.ifBlank { "resource" } }
        .replace(Regex("[^A-Za-z0-9._-]"), "_")
    val nonce = UUID.randomUUID().toString().replace("-", "")
    return if (mimeType.startsWith("video/")) {
        "video_thumb_${stableToken}_$nonce.jpg"
    } else {
        "thumb_${stableToken}_$nonce.jpg"
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

private val extensionMimeTypeMap = mapOf(
    "jpg" to "image/jpeg",
    "jpeg" to "image/jpeg",
    "png" to "image/png",
    "gif" to "image/gif",
    "webp" to "image/webp",
    "bmp" to "image/bmp",
    "heic" to "image/heic",
    "heif" to "image/heif",
    "avif" to "image/avif",
    "mp4" to "video/mp4",
    "mov" to "video/quicktime",
    "m4v" to "video/x-m4v",
    "webm" to "video/webm",
    "mkv" to "video/x-matroska",
    "avi" to "video/x-msvideo",
    "3gp" to "video/3gpp",
    "mpeg" to "video/mpeg",
    "mpg" to "video/mpeg",
)

