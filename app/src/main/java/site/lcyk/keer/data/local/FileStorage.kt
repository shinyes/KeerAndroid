package site.lcyk.keer.data.local

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FileStorage @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private fun accountDir(accountKey: String): File {
        val encoded = Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(accountKey.toByteArray(Charsets.UTF_8))
        return File(context.filesDir, "resources/$encoded").also { it.mkdirs() }
    }

    fun saveFile(accountKey: String, content: ByteArray, filename: String): Uri {
        val file = File(accountDir(accountKey), filename)
        file.writeBytes(content)
        return Uri.fromFile(file)
    }

    fun saveFileFromUri(
        accountKey: String,
        sourceUri: Uri,
        filename: String,
        onProgress: ((writtenBytes: Long, totalBytes: Long) -> Unit)? = null
    ): Uri {
        val target = File(accountDir(accountKey), filename)
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
        return Uri.fromFile(target)
    }

    fun deleteFile(uri: Uri) {
        uri.path?.let { path ->
            File(path).delete()
        }
    }

    fun deleteAccountFiles(accountKey: String) {
        accountDir(accountKey).deleteRecursively()
    }

    private fun queryContentLength(uri: Uri): Long {
        return context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            pfd.statSize.takeIf { it >= 0L } ?: -1L
        } ?: -1L
    }
}
