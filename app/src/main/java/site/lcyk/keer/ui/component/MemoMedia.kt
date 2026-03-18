package site.lcyk.keer.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.media3.common.util.UnstableApi
import site.lcyk.keer.data.model.ResourceRepresentable
import site.lcyk.keer.data.security.AttachmentEncryptionManager

private val imageExtensions = setOf(
    "png", "jpg", "jpeg", "gif", "webp", "bmp", "heic", "heif", "avif"
)
private val videoExtensions = setOf(
    "mp4", "mov", "m4v", "webm", "mkv", "avi", "3gp", "mpeg", "mpg"
)

fun ResourceRepresentable.isImageResource(): Boolean {
    val normalizedMimeType = AttachmentEncryptionManager.resolveOriginalMimeType(
        encryptionMetadata,
        mimeType
    )?.lowercase().orEmpty()
    if (normalizedMimeType.startsWith("image/")) return true
    if (normalizedMimeType.startsWith("video/")) return false
    val extension = filename.substringAfterLast('.', "").lowercase()
    return extension in imageExtensions
}

fun ResourceRepresentable.isVideoResource(): Boolean {
    val normalizedMimeType = AttachmentEncryptionManager.resolveOriginalMimeType(
        encryptionMetadata,
        mimeType
    )?.lowercase().orEmpty()
    if (normalizedMimeType.startsWith("video/")) return true
    if (normalizedMimeType.startsWith("image/")) return false
    val extension = filename.substringAfterLast('.', "").lowercase()
    return extension in videoExtensions
}

fun ResourceRepresentable.isMediaResource(): Boolean {
    return isImageResource() || isVideoResource()
}

@Composable
@UnstableApi
fun MemoMedia(
    resource: ResourceRepresentable,
    modifier: Modifier = Modifier,
    autoPreviewPrefetch: Boolean = true,
) {
    if (resource.isVideoResource()) {
        MemoVideo(
            resource = resource,
            modifier = modifier,
            autoPreviewPrefetch = autoPreviewPrefetch,
        )
    } else {
        MemoImage(
            resource = resource,
            modifier = modifier,
            autoPreviewPrefetch = autoPreviewPrefetch,
        )
    }
}
