package site.lcyk.keer.ui.component

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Attachment
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.PopupProperties
import androidx.core.net.toUri
import com.skydoves.sandwich.ApiResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import site.lcyk.keer.KeerFileProvider
import site.lcyk.keer.R
import site.lcyk.keer.data.local.entity.ResourceEntity
import site.lcyk.keer.data.model.Account
import site.lcyk.keer.data.model.ResourceRepresentable
import site.lcyk.keer.data.security.AttachmentEncryptionManager
import site.lcyk.keer.data.security.EncryptedBlobVariant
import site.lcyk.keer.ext.string
import site.lcyk.keer.viewmodel.LocalMemos
import site.lcyk.keer.viewmodel.LocalUserState
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.File

@Composable
fun Attachment(
    resource: ResourceRepresentable,
    onRemove: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val memosViewModel = LocalMemos.current
    val userStateViewModel = LocalUserState.current
    val currentAccount by userStateViewModel.currentAccount.collectAsState(initial = null)
    val scope = rememberCoroutineScope()
    var menuExpanded by remember { mutableStateOf(false) }
    var opening by remember { mutableStateOf(false) }

    fun openAttachment() {
        if (opening) {
            return
        }
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
                )
                if (localFile == null) {
                    Toast.makeText(context, R.string.failed_to_open_attachment.string, Toast.LENGTH_SHORT).show()
                    return@launch
                }
                val fileUri = KeerFileProvider.getFileUri(context, localFile)
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = resolveMimeType(resolvedResource, localFile)
                    putExtra(Intent.EXTRA_STREAM, fileUri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    clipData = ClipData.newUri(
                        context.contentResolver,
                        resolvedResource.filename.ifBlank { "attachment" },
                        fileUri
                    )
                }
                context.startActivity(Intent.createChooser(shareIntent, null))
            } catch (e: Throwable) {
                Timber.d(e)
                Toast.makeText(context, R.string.failed_to_open_attachment.string, Toast.LENGTH_SHORT).show()
            } finally {
                opening = false
                menuExpanded = false
            }
        }
    }

    AssistChip(
        enabled = !opening,
        onClick = {
            if (onRemove == null) {
                openAttachment()
            } else {
                menuExpanded = true
            }
        },
        label = { Text(resource.filename) },
        leadingIcon = {
            Icon(
                Icons.Outlined.Attachment,
                contentDescription = R.string.attachment.string,
                Modifier.size(AssistChipDefaults.IconSize)
            )
        }
    )

    if (onRemove != null) {
        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false },
            properties = PopupProperties(focusable = false)
        ) {
            DropdownMenuItem(
                text = { Text(R.string.open.string) },
                onClick = {
                    openAttachment()
                },
                leadingIcon = {
                    Icon(
                        Icons.AutoMirrored.Outlined.OpenInNew,
                        contentDescription = null
                    )
                }
            )
            DropdownMenuItem(
                text = { Text(R.string.remove.string) },
                onClick = {
                    onRemove()
                    menuExpanded = false
                },
                leadingIcon = {
                    Icon(
                        Icons.Outlined.Delete,
                        contentDescription = null
                    )
                }
            )
        }
    }
}

suspend fun resolveAttachmentFile(
    context: Context,
    resource: ResourceRepresentable,
    okHttpClient: OkHttpClient,
    currentAccountKey: String?,
    cacheCanonical: suspend (resourceIdentifier: String, downloadedUri: android.net.Uri) -> ResourceEntity?
): File? {
    existingLocalFile(resource)?.let { return it }

    val uri = resource.uri.toUri()
    if (uri.scheme != "http" && uri.scheme != "https") {
        return null
    }

    val downloaded = downloadResourceVariantToTemp(
        context = context,
        okHttpClient = okHttpClient,
        resource = resource,
        accountKey = resolveResourceAccountKey(resource, currentAccountKey),
        url = resource.uri,
        filename = resource.filename,
        variant = EncryptedBlobVariant.MAIN,
        cacheDirName = "attachment_cache",
        prefix = "attachment_"
    ) ?: return null
    val resourceEntity = resource as? ResourceEntity ?: return downloaded

    val cached = cacheCanonical(resourceEntity.identifier, downloaded.toUri())
    val canonical = cached?.localUri
        ?.toUri()
        ?.takeIf { it.scheme == "file" }
        ?.path
        ?.let(::File)
        ?.takeIf { it.exists() }

    if (canonical != null) {
        downloaded.delete()
        return canonical
    }

    return downloaded
}

private fun existingLocalFile(resource: ResourceRepresentable): File? {
    val local = (resource.localUri ?: resource.uri).toUri()
    if (local.scheme != "file") {
        return null
    }
    val path = local.path ?: return null
    return File(path).takeIf { it.exists() }
}

internal suspend fun downloadResourceVariantToTemp(
    context: Context,
    okHttpClient: OkHttpClient,
    resource: ResourceRepresentable,
    accountKey: String?,
    url: String,
    filename: String,
    variant: EncryptedBlobVariant,
    cacheDirName: String,
    prefix: String,
): File? = withContext(Dispatchers.IO) {
    val request = Request.Builder().url(url).get().build()
    okHttpClient.newCall(request).execute().use { response ->
        if (!response.isSuccessful) {
            return@withContext null
        }
        val body = response.body
        val dir = File(context.cacheDir, cacheDirName).also { it.mkdirs() }
        val suffix = "_${sanitizeFilename(filename.ifBlank { "attachment" })}"
        val target = File.createTempFile(prefix, suffix, dir)
        val rawMetadata = resource.encryptionMetadata?.trim().orEmpty()
        val parsedMetadata = AttachmentEncryptionManager.parseMetadata(rawMetadata)
        if (variant == EncryptedBlobVariant.MAIN && rawMetadata.isNotEmpty() && parsedMetadata == null) {
            target.delete()
            return@withContext null
        }
        val shouldDecrypt = when (variant) {
            EncryptedBlobVariant.MAIN -> parsedMetadata != null
            EncryptedBlobVariant.THUMBNAIL -> parsedMetadata?.thumbnail != null
        }
        return@withContext try {
            body.byteStream().use { input ->
                if (shouldDecrypt) {
                    AttachmentEncryptionManager(context.applicationContext).decryptVariantToFile(
                        accountKey = resolveResourceAccountKey(resource, accountKey),
                        rawMetadata = rawMetadata,
                        variant = variant,
                        input = input,
                        outputFile = target
                    )
                } else {
                    target.outputStream().use { output ->
                        input.copyTo(output)
                    }
                    target
                }
            }
        } catch (e: Throwable) {
            target.delete()
            Timber.d(e)
            null
        }
    }
}

internal fun resolveResourceAccountKey(
    resource: ResourceRepresentable,
    currentAccountKey: String?,
): String? {
    val resourceAccountKey = (resource as? ResourceEntity)?.accountKey?.trim().orEmpty()
    if (resourceAccountKey.isNotEmpty()) {
        return resourceAccountKey
    }
    return currentAccountKey?.trim()?.ifBlank { null }
}

private fun sanitizeFilename(filename: String): String {
    return filename.replace(Regex("[^A-Za-z0-9._-]"), "_")
}

fun resolveMimeType(resource: ResourceRepresentable, file: File): String {
    AttachmentEncryptionManager.resolveOriginalMimeType(
        resource.encryptionMetadata,
        resource.mimeType
    )?.takeIf { it.isNotBlank() }?.let { return it }
    val ext = file.extension.lowercase()
    if (ext.isBlank()) {
        return "*/*"
    }
    return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
}
