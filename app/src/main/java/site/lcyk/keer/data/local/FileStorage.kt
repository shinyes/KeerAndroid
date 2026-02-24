package site.lcyk.keer.data.local

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.roundToInt

@Singleton
class FileStorage @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private fun encodedAccountKey(accountKey: String): String {
        return Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(accountKey.toByteArray(Charsets.UTF_8))
    }

    private fun cacheResourceDir(accountKey: String): File {
        val encoded = encodedAccountKey(accountKey)
        return File(context.cacheDir, "resources/$encoded").also { it.mkdirs() }
    }

    private fun persistentResourceDir(accountKey: String): File {
        val encoded = encodedAccountKey(accountKey)
        return File(context.filesDir, "resources/$encoded").also { it.mkdirs() }
    }

    private fun thumbnailDir(accountKey: String): File {
        val encoded = encodedAccountKey(accountKey)
        return File(context.filesDir, "thumbnails/$encoded").also { it.mkdirs() }
    }

    fun saveFile(accountKey: String, content: ByteArray, filename: String): Uri {
        val file = File(cacheResourceDir(accountKey), filename)
        file.writeBytes(content)
        return Uri.fromFile(file)
    }

    fun saveFileFromUri(
        accountKey: String,
        sourceUri: Uri,
        filename: String,
        onProgress: ((writtenBytes: Long, totalBytes: Long) -> Unit)? = null
    ): Uri {
        val target = File(cacheResourceDir(accountKey), filename)
        copyUriToFile(sourceUri = sourceUri, target = target, onProgress = onProgress)
        return Uri.fromFile(target)
    }

    fun savePersistentFileFromUri(
        accountKey: String,
        sourceUri: Uri,
        filename: String,
        onProgress: ((writtenBytes: Long, totalBytes: Long) -> Unit)? = null
    ): Uri {
        val target = File(persistentResourceDir(accountKey), filename)
        copyUriToFile(sourceUri = sourceUri, target = target, onProgress = onProgress)
        return Uri.fromFile(target)
    }

    fun copyFileToCache(
        accountKey: String,
        sourceFile: File,
        filename: String
    ): Uri {
        val target = File(cacheResourceDir(accountKey), filename)
        if (sourceFile.canonicalPath == target.canonicalPath) {
            return Uri.fromFile(target)
        }
        sourceFile.inputStream().use { input ->
            target.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        return Uri.fromFile(target)
    }

    fun saveThumbnailFromUri(
        accountKey: String,
        sourceUri: Uri,
        filename: String
    ): Uri {
        val target = File(thumbnailDir(accountKey), filename)
        copyUriToFile(sourceUri = sourceUri, target = target)
        return Uri.fromFile(target)
    }

    fun saveImageThumbnailFromUri(
        accountKey: String,
        sourceUri: Uri,
        filename: String,
        maxEdge: Int = 640,
        quality: Int = 85
    ): Uri? {
        if (maxEdge <= 0) {
            return null
        }
        val decodedBitmap = decodeSampledBitmap(sourceUri, maxEdge) ?: return null
        val scaledBitmap = scaleDown(decodedBitmap, maxEdge)
        val target = File(thumbnailDir(accountKey), filename)
        return try {
            val compressed = target.outputStream().use { output ->
                scaledBitmap.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(1, 100), output)
            }
            if (!compressed) {
                target.delete()
                null
            } else {
                Uri.fromFile(target)
            }
        } finally {
            if (scaledBitmap !== decodedBitmap) {
                scaledBitmap.recycle()
            }
            decodedBitmap.recycle()
        }
    }

    fun deleteFile(uri: Uri) {
        uri.path?.let { path ->
            File(path).delete()
        }
    }

    fun deleteAccountFiles(accountKey: String) {
        cacheResourceDir(accountKey).deleteRecursively()
        persistentResourceDir(accountKey).deleteRecursively()
        thumbnailDir(accountKey).deleteRecursively()
    }

    private fun queryContentLength(uri: Uri): Long {
        return context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            pfd.statSize.takeIf { it >= 0L } ?: -1L
        } ?: -1L
    }

    private fun copyUriToFile(
        sourceUri: Uri,
        target: File,
        onProgress: ((writtenBytes: Long, totalBytes: Long) -> Unit)? = null
    ) {
        val totalBytes = queryContentLength(sourceUri)
        context.contentResolver.openInputStream(sourceUri)?.use { input ->
            target.outputStream().use { output ->
                val buffer = ByteArray(8 * 1024)
                var written = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) {
                        break
                    }
                    output.write(buffer, 0, read)
                    written += read
                    onProgress?.invoke(written, totalBytes)
                }
            }
        } ?: throw IllegalStateException("Unable to open source uri")
    }

    private fun decodeSampledBitmap(sourceUri: Uri, maxEdge: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(sourceUri)?.use { input ->
            BitmapFactory.decodeStream(input, null, bounds)
        } ?: return null

        val sourceWidth = bounds.outWidth
        val sourceHeight = bounds.outHeight
        if (sourceWidth <= 0 || sourceHeight <= 0) {
            return null
        }

        val maxSourceEdge = max(sourceWidth, sourceHeight)
        var sampleSize = 1
        while (maxSourceEdge / sampleSize > maxEdge * 2) {
            sampleSize *= 2
        }

        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }

        return context.contentResolver.openInputStream(sourceUri)?.use { input ->
            BitmapFactory.decodeStream(input, null, decodeOptions)
        }
    }

    private fun scaleDown(bitmap: Bitmap, maxEdge: Int): Bitmap {
        val maxSourceEdge = max(bitmap.width, bitmap.height)
        if (maxSourceEdge <= maxEdge) {
            return bitmap
        }
        val scale = maxEdge.toFloat() / maxSourceEdge.toFloat()
        val targetWidth = (bitmap.width * scale).roundToInt().coerceAtLeast(1)
        val targetHeight = (bitmap.height * scale).roundToInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
    }
}
