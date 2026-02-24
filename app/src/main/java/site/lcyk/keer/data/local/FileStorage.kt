package site.lcyk.keer.data.local

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
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

    fun saveVideoThumbnailFromUri(
        accountKey: String,
        sourceUri: Uri,
        filename: String,
        maxEdge: Int = 640,
        quality: Int = 82
    ): Uri? {
        if (maxEdge <= 0) {
            return null
        }
        val frameBitmap = extractBestVideoFrame(sourceUri, maxEdge) ?: return null
        val scaledBitmap = scaleDown(frameBitmap, maxEdge)
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
            if (scaledBitmap !== frameBitmap) {
                scaledBitmap.recycle()
            }
            frameBitmap.recycle()
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

    private fun extractBestVideoFrame(sourceUri: Uri, maxEdge: Int): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, sourceUri)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?.coerceAtLeast(0L)
                ?: 0L
            val durationUs = durationMs * 1000L

            val sourceWidth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull()
                ?.coerceAtLeast(0)
                ?: 0
            val sourceHeight = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull()
                ?.coerceAtLeast(0)
                ?: 0
            val targetSize = resolveScaledSize(sourceWidth, sourceHeight, maxEdge)

            var fallback: Bitmap? = null
            var fallbackScore = Double.NEGATIVE_INFINITY
            for (timeUs in buildFrameCandidates(durationUs)) {
                val candidate = extractVideoFrame(retriever, timeUs, targetSize.first, targetSize.second)
                    ?: continue
                val frameScore = scoreFrameBrightness(candidate)
                val rank = frameScore.avgLuma + frameScore.brightRatio * 80.0
                if (!frameScore.isLikelyBlack()) {
                    fallback?.recycle()
                    return candidate
                }
                if (rank > fallbackScore) {
                    fallback?.recycle()
                    fallback = candidate
                    fallbackScore = rank
                } else {
                    candidate.recycle()
                }
            }

            fallback ?: extractVideoFrame(retriever, 0L, targetSize.first, targetSize.second)
        } catch (_: Exception) {
            null
        } finally {
            retriever.release()
        }
    }

    private fun extractVideoFrame(
        retriever: MediaMetadataRetriever,
        timeUs: Long,
        targetWidth: Int,
        targetHeight: Int
    ): Bitmap? {
        return try {
            if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1 &&
                targetWidth > 0 &&
                targetHeight > 0
            ) {
                retriever.getScaledFrameAtTime(
                    timeUs,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    targetWidth,
                    targetHeight
                )
            } else {
                retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun buildFrameCandidates(durationUs: Long): List<Long> {
        val candidates = linkedSetOf<Long>()
        val fixedPoints = longArrayOf(200_000L, 600_000L, 1_200_000L, 2_000_000L, 3_500_000L)
        fixedPoints.forEach { point ->
            if (durationUs <= 0L || point <= durationUs) {
                candidates.add(point)
            }
        }
        if (durationUs > 0L) {
            val ratios = doubleArrayOf(0.06, 0.12, 0.20, 0.35, 0.50)
            ratios.forEach { ratio ->
                val candidate = (durationUs * ratio).toLong().coerceIn(0L, durationUs)
                candidates.add(candidate)
            }
            candidates.add(durationUs / 2L)
        }
        candidates.add(0L)
        return candidates.toList()
    }

    private fun resolveScaledSize(width: Int, height: Int, maxEdge: Int): Pair<Int, Int> {
        if (width <= 0 || height <= 0 || maxEdge <= 0) {
            return 0 to 0
        }
        val maxSourceEdge = max(width, height)
        if (maxSourceEdge <= maxEdge) {
            return width to height
        }
        val scale = maxEdge.toFloat() / maxSourceEdge.toFloat()
        val targetWidth = (width * scale).roundToInt().coerceAtLeast(1)
        val targetHeight = (height * scale).roundToInt().coerceAtLeast(1)
        return targetWidth to targetHeight
    }

    private data class FrameBrightnessScore(
        val avgLuma: Double,
        val brightRatio: Double
    ) {
        fun isLikelyBlack(): Boolean {
            return avgLuma < 22.0 && brightRatio < 0.05
        }
    }

    private fun scoreFrameBrightness(bitmap: Bitmap): FrameBrightnessScore {
        val sourceWidth = bitmap.width
        val sourceHeight = bitmap.height
        if (sourceWidth <= 0 || sourceHeight <= 0) {
            return FrameBrightnessScore(0.0, 0.0)
        }
        val maxAnalysisEdge = 96
        val analysisBitmap =
            if (max(sourceWidth, sourceHeight) > maxAnalysisEdge) {
                val scale = maxAnalysisEdge.toFloat() / max(sourceWidth, sourceHeight).toFloat()
                val targetWidth = (sourceWidth * scale).roundToInt().coerceAtLeast(1)
                val targetHeight = (sourceHeight * scale).roundToInt().coerceAtLeast(1)
                Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
            } else {
                bitmap
            }

        val width = analysisBitmap.width
        val height = analysisBitmap.height
        val pixels = IntArray(width * height)
        analysisBitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        if (analysisBitmap !== bitmap) {
            analysisBitmap.recycle()
        }

        if (pixels.isEmpty()) {
            return FrameBrightnessScore(0.0, 0.0)
        }

        var lumaSum = 0.0
        var brightCount = 0
        pixels.forEach { pixel ->
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            val luma = (0.2126 * r) + (0.7152 * g) + (0.0722 * b)
            lumaSum += luma
            if (luma >= 40.0) {
                brightCount += 1
            }
        }
        val sampleCount = pixels.size.toDouble()
        return FrameBrightnessScore(
            avgLuma = lumaSum / sampleCount,
            brightRatio = brightCount.toDouble() / sampleCount
        )
    }
}
