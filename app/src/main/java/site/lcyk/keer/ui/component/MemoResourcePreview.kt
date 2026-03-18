package site.lcyk.keer.ui.component

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.core.net.toUri
import com.skydoves.sandwich.ApiResponse
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import site.lcyk.keer.data.local.entity.ResourceEntity
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
    autoPreviewPrefetch: Boolean = true,
): ObservedMemoResource {
    val memosViewModel = LocalMemos.current
    val identifier = (resource as? ResourceEntity)?.identifier
    val resourceFlow = remember(identifier) {
        if (identifier.isNullOrBlank()) {
            flowOf(null)
        } else {
            memosViewModel.observeResource(identifier)
        }
    }
    val observed by resourceFlow.collectAsState(initial = null)
    return ObservedMemoResource(
        resource = observed ?: resource,
        tracked = observed != null,
    )
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

private fun resolveExistingLocalFileUri(rawLocalUri: String?): String? {
    val local = rawLocalUri?.trim()?.ifBlank { null } ?: return null
    val uri = local.toUri()
    if (uri.scheme != "file") {
        return local
    }
    val file = uri.path?.let(::File)?.takeIf(File::exists) ?: return null
    return if (file.length() > 0L) local else null
}

