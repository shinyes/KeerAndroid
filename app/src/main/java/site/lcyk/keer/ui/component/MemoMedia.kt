package site.lcyk.keer.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import site.lcyk.keer.data.local.entity.ResourceEntity
import site.lcyk.keer.data.model.ResourceRepresentable

private val imageExtensions = setOf(
    "png", "jpg", "jpeg", "gif", "webp", "bmp", "heic", "heif", "avif"
)
private val videoExtensions = setOf(
    "mp4", "mov", "m4v", "webm", "mkv", "avi", "3gp", "mpeg", "mpg"
)

fun ResourceRepresentable.isImageResource(): Boolean {
    val normalizedMimeType = mimeType?.lowercase().orEmpty()
    if (normalizedMimeType.startsWith("image/")) return true
    if (normalizedMimeType.startsWith("video/")) return false
    val extension = filename.substringAfterLast('.', "").lowercase()
    return extension in imageExtensions
}

fun ResourceRepresentable.isVideoResource(): Boolean {
    val normalizedMimeType = mimeType?.lowercase().orEmpty()
    if (normalizedMimeType.startsWith("video/")) return true
    if (normalizedMimeType.startsWith("image/")) return false
    val extension = filename.substringAfterLast('.', "").lowercase()
    return extension in videoExtensions
}

fun ResourceRepresentable.isMediaResource(): Boolean {
    return isImageResource() || isVideoResource()
}

@Composable
fun MemoMedia(
    resource: ResourceRepresentable,
    modifier: Modifier = Modifier
) {
    if (resource.isVideoResource()) {
        MemoVideo(resource = resource, modifier = modifier)
    } else {
        MemoImage(
            url = resource.localUri ?: resource.uri,
            modifier = modifier,
            resourceIdentifier = (resource as? ResourceEntity)?.identifier
        )
    }
}

