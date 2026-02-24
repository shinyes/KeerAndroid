package site.lcyk.keer.ui.component

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import com.skydoves.sandwich.ApiResponse
import coil3.ImageLoader
import coil3.annotation.ExperimentalCoilApi
import coil3.compose.AsyncImage
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import site.lcyk.keer.KeerFileProvider
import site.lcyk.keer.data.model.ResourceRepresentable
import site.lcyk.keer.viewmodel.LocalMemos
import site.lcyk.keer.viewmodel.LocalUserState
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlin.math.max
import kotlin.math.roundToInt

private const val memoImageThumbnailMaxEdge = 640
private const val memoImageThumbnailJpegQuality = 80

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
    val thumbnailFile = remember(resource.remoteId, resource.uri, resource.localUri, resource.filename) {
        memoImageThumbnailFile(context, resource)
    }
    var previewModel by remember(resource.remoteId, resource.uri, resource.localUri, resource.filename) {
        mutableStateOf<Any?>(
            when {
                thumbnailFile.exists() -> thumbnailFile.toUri().toString()
                else -> existingMemoImageLocalFile(resource)?.toUri()?.toString()
            }
        )
    }
    LaunchedEffect(resource.remoteId, resource.uri, resource.localUri, resource.filename, resource.mimeType) {
        previewModel = resolveMemoImagePreviewModel(
            context = context,
            resource = resource,
            okHttpClient = userStateViewModel.okHttpClient
        )
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

private suspend fun resolveMemoImagePreviewModel(
    context: Context,
    resource: ResourceRepresentable,
    okHttpClient: OkHttpClient
): String = withContext(Dispatchers.IO) {
    val localFile = existingMemoImageLocalFile(resource)
    val fallback = localFile?.toUri()?.toString() ?: resource.uri
    if (!resource.isImageResource()) {
        return@withContext fallback
    }

    val thumbnailFile = memoImageThumbnailFile(context, resource)
    if (thumbnailFile.exists() && thumbnailFile.length() > 0L) {
        return@withContext thumbnailFile.toUri().toString()
    }

    var downloadedTempFile: File? = null
    val sourceFile = localFile ?: run {
        val uri = resource.uri.toUri()
        if (!isHttpUri(uri)) {
            return@withContext fallback
        }
        downloadMemoImageSourceForThumbnail(
            context = context,
            okHttpClient = okHttpClient,
            url = resource.uri,
            filename = resource.filename
        )
    }.also { file ->
        downloadedTempFile = file
    }
    if (sourceFile == null) {
        return@withContext fallback
    }

    val generated = generateThumbnailFile(
        sourceFile = sourceFile,
        targetFile = thumbnailFile,
        maxEdge = memoImageThumbnailMaxEdge,
        quality = memoImageThumbnailJpegQuality
    )
    downloadedTempFile?.delete()
    if (generated) {
        return@withContext thumbnailFile.toUri().toString()
    }
    fallback
}

private fun memoImageThumbnailFile(context: Context, resource: ResourceRepresentable): File {
    val dir = File(context.cacheDir, "memo_image_thumbnails")
    dir.mkdirs()
    val key = buildMemoImageThumbnailKey(resource)
    return File(dir, "$key.jpg")
}

private fun buildMemoImageThumbnailKey(resource: ResourceRepresentable): String {
    val stable = buildString {
        append(resource.remoteId.orEmpty())
        append('|')
        append(resource.uri)
        append('|')
        append(resource.filename)
        append('|')
        append(resource.localUri.orEmpty())
    }
    val bytes = MessageDigest.getInstance("SHA-256").digest(stable.toByteArray(Charsets.UTF_8))
    return bytes.joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
}

private fun downloadMemoImageSourceForThumbnail(
    context: Context,
    okHttpClient: OkHttpClient,
    url: String,
    filename: String
): File? {
    val request = Request.Builder().url(url).get().build()
    okHttpClient.newCall(request).execute().use { response ->
        if (!response.isSuccessful) {
            return null
        }
        val body = response.body
        val dir = File(context.cacheDir, "memo_image_thumbnail_source").also { it.mkdirs() }
        val suffix = "_${sanitizeMemoImageFilename(filename.ifBlank { "image" })}"
        val target = File.createTempFile("source_", suffix, dir)
        body.byteStream().use { input ->
            target.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        return target
    }
}

private fun sanitizeMemoImageFilename(filename: String): String {
    return filename.replace(Regex("[^A-Za-z0-9._-]"), "_")
}

private fun generateThumbnailFile(
    sourceFile: File,
    targetFile: File,
    maxEdge: Int,
    quality: Int
): Boolean {
    val boundsOptions = BitmapFactory.Options().apply {
        inJustDecodeBounds = true
    }
    BitmapFactory.decodeFile(sourceFile.absolutePath, boundsOptions)
    if (boundsOptions.outWidth <= 0 || boundsOptions.outHeight <= 0) {
        return false
    }

    val decodeOptions = BitmapFactory.Options().apply {
        inSampleSize = calculateInSampleSize(
            sourceWidth = boundsOptions.outWidth,
            sourceHeight = boundsOptions.outHeight,
            maxEdge = maxEdge
        )
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    val decoded = BitmapFactory.decodeFile(sourceFile.absolutePath, decodeOptions) ?: return false
    val (targetWidth, targetHeight) = scaleDownDimensions(decoded.width, decoded.height, maxEdge)
    val scaled = if (decoded.width == targetWidth && decoded.height == targetHeight) {
        decoded
    } else {
        Bitmap.createScaledBitmap(decoded, targetWidth, targetHeight, true)
    }

    val success = try {
        targetFile.parentFile?.mkdirs()
        FileOutputStream(targetFile).use { output ->
            scaled.compress(Bitmap.CompressFormat.JPEG, quality, output)
        }
    } catch (_: Throwable) {
        false
    }

    if (scaled !== decoded) {
        decoded.recycle()
    }
    scaled.recycle()
    if (!success) {
        targetFile.delete()
    }
    return success
}

private fun calculateInSampleSize(sourceWidth: Int, sourceHeight: Int, maxEdge: Int): Int {
    var sampleSize = 1
    while (sourceWidth / sampleSize > maxEdge * 2 || sourceHeight / sampleSize > maxEdge * 2) {
        sampleSize *= 2
    }
    return sampleSize.coerceAtLeast(1)
}

private fun scaleDownDimensions(width: Int, height: Int, maxEdge: Int): Pair<Int, Int> {
    val currentMaxEdge = max(width, height)
    if (currentMaxEdge <= maxEdge) {
        return width to height
    }
    val ratio = maxEdge.toFloat() / currentMaxEdge.toFloat()
    val scaledWidth = max(1, (width * ratio).roundToInt())
    val scaledHeight = max(1, (height * ratio).roundToInt())
    return scaledWidth to scaledHeight
}

private fun isHttpUri(uri: Uri): Boolean {
    return uri.scheme.equals("http", ignoreCase = true) ||
        uri.scheme.equals("https", ignoreCase = true)
}

private fun existingMemoImageLocalFile(resource: ResourceRepresentable): File? {
    val local = (resource.localUri ?: resource.uri).toUri()
    if (local.scheme != "file") {
        return null
    }
    val path = local.path ?: return null
    return File(path).takeIf { it.exists() }
}
