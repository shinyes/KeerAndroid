package site.lcyk.keer.ui.component

import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import com.skydoves.sandwich.ApiResponse
import coil3.annotation.ExperimentalCoilApi
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import site.lcyk.keer.KeerFileProvider
import site.lcyk.keer.data.local.entity.ResourceEntity
import site.lcyk.keer.data.model.ResourceRepresentable
import site.lcyk.keer.data.security.AttachmentEncryptionManager
import site.lcyk.keer.data.security.EncryptedBlobVariant
import site.lcyk.keer.viewmodel.LocalMemos
import site.lcyk.keer.viewmodel.LocalUserState
import okhttp3.OkHttpClient
import timber.log.Timber
import java.io.File

@OptIn(ExperimentalCoilApi::class)
@Composable
fun MemoImage(
    resource: ResourceRepresentable,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val hapticFeedback = LocalHapticFeedback.current
    val userStateViewModel = LocalUserState.current
    val currentAccount by userStateViewModel.currentAccount.collectAsState(initial = null)
    val memosViewModel = LocalMemos.current
    val scope = rememberCoroutineScope()
    var opening by remember(resource.remoteId, resource.uri, resource.localUri) { mutableStateOf(false) }
    val imageLoader = rememberAuthorizedImageLoader()
    val previewModel = remember(resource.thumbnailLocalUri, resource.thumbnailUri, resource.localUri, resource.uri) {
        resolveMemoImagePreviewUri(resource)
    }

    LaunchedEffect(resource.remoteId, resource.thumbnailUri, resource.thumbnailLocalUri) {
        val resourceEntity = resource as? ResourceEntity ?: return@LaunchedEffect
        val localThumbnail = resourceEntity.thumbnailLocalUri?.trim().orEmpty()
        if (localThumbnail.isNotEmpty()) {
            return@LaunchedEffect
        }

        val remoteThumbnail = resourceEntity.thumbnailUri?.trim().orEmpty()
        if (remoteThumbnail.isEmpty()) {
            return@LaunchedEffect
        }
        val thumbnailUri = remoteThumbnail.toUri()
        if (thumbnailUri.scheme != "http" && thumbnailUri.scheme != "https") {
            return@LaunchedEffect
        }

        val downloaded = downloadResourceVariantToTemp(
            context = context,
            okHttpClient = userStateViewModel.okHttpClient,
            resource = resourceEntity,
            accountKey = currentAccount?.accountKey(),
            url = remoteThumbnail,
            filename = resourceEntity.filename,
            variant = EncryptedBlobVariant.THUMBNAIL,
            cacheDirName = "thumbnail_cache",
            prefix = "thumb_",
        ) ?: return@LaunchedEffect

        try {
            memosViewModel.cacheResourceThumbnail(resourceEntity.identifier, downloaded.toUri())
        } catch (e: Throwable) {
            Timber.d(e)
        } finally {
            downloaded.delete()
        }
    }

    Box(
        modifier = modifier.clickable(enabled = !opening) {
            if (opening) return@clickable
            hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
            scope.launch {
                opening = true
                try {
                    val resolvedResource = (resource as? ResourceEntity)?.let { entity ->
                        memosViewModel.getResourceById(entity.identifier) ?: resource
                    } ?: resource
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
                    val intent = Intent().apply {
                        action = Intent.ACTION_VIEW
                        setDataAndType(fileUri, "image/*")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(intent)
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
}

private fun resolveMemoImagePreviewUri(resource: ResourceRepresentable): String {
    val localThumbnail = resource.thumbnailLocalUri?.trim().orEmpty()
    if (localThumbnail.isNotEmpty()) {
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

private fun sanitizeThumbnailFilename(filename: String): String {
    return filename.replace(Regex("[^A-Za-z0-9._-]"), "_")
}
