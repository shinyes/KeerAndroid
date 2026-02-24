package site.lcyk.keer.ui.component

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import com.skydoves.sandwich.ApiResponse
import coil3.ImageLoader
import coil3.annotation.ExperimentalCoilApi
import coil3.compose.AsyncImage
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import kotlinx.coroutines.launch
import site.lcyk.keer.KeerFileProvider
import site.lcyk.keer.data.model.ResourceRepresentable
import site.lcyk.keer.viewmodel.LocalMemos
import site.lcyk.keer.viewmodel.LocalUserState
import timber.log.Timber

@OptIn(ExperimentalCoilApi::class)
@Composable
fun MemoImage(
    resource: ResourceRepresentable,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val userStateViewModel = LocalUserState.current
    val memosViewModel = LocalMemos.current
    val scope = rememberCoroutineScope()
    var opening by remember(resource.remoteId, resource.uri, resource.localUri) { mutableStateOf(false) }
    val imageLoader = remember(context, userStateViewModel.okHttpClient) {
        ImageLoader.Builder(context)
            .components {
                add(
                    OkHttpNetworkFetcherFactory(
                        callFactory = { userStateViewModel.okHttpClient }
                    )
                )
            }
            .build()
    }
    val previewModel = remember(resource.thumbnailUri, resource.localUri, resource.uri) {
        resolveMemoImagePreviewUri(resource)
    }

    AsyncImage(
        model = previewModel,
        imageLoader = imageLoader,
        contentDescription = null,
        modifier = modifier.clickable(enabled = !opening) {
            if (opening) return@clickable
            scope.launch {
                opening = true
                try {
                    val localFile = resolveAttachmentFile(
                        context = context,
                        resource = resource,
                        okHttpClient = userStateViewModel.okHttpClient,
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
        },
        contentScale = ContentScale.Crop,
        onError = {
            Timber.d("Failed to load memo image preview: %s", previewModel)
        }
    )
}

private fun resolveMemoImagePreviewUri(resource: ResourceRepresentable): String {
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

