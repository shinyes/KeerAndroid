package site.lcyk.keer.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.media3.common.util.UnstableApi
import coil3.ImageLoader
import site.lcyk.keer.data.model.ResourceRepresentable
import site.lcyk.keer.util.clearResourceMediaClassificationCacheForTest
import site.lcyk.keer.util.isImageMediaResource
import site.lcyk.keer.util.isMediaDisplayResource
import site.lcyk.keer.util.isVideoMediaResource
import site.lcyk.keer.util.resourceMediaClassificationCacheSizeForTest

fun ResourceRepresentable.isImageResource(): Boolean {
    return isImageMediaResource()
}

fun ResourceRepresentable.isVideoResource(): Boolean {
    return isVideoMediaResource()
}

fun ResourceRepresentable.isMediaResource(): Boolean {
    return isMediaDisplayResource()
}

@Composable
@UnstableApi
fun MemoMedia(
    resource: ResourceRepresentable,
    modifier: Modifier = Modifier,
    autoPreviewPrefetch: Boolean = true,
    mediaImageLoader: ImageLoader? = null,
    observeLiveResource: Boolean = true,
) {
    if (resource.isVideoResource()) {
        MemoVideo(
            resource = resource,
            modifier = modifier,
            autoPreviewPrefetch = autoPreviewPrefetch,
            mediaImageLoader = mediaImageLoader,
            observeLiveResource = observeLiveResource,
        )
    } else {
        MemoImage(
            resource = resource,
            modifier = modifier,
            autoPreviewPrefetch = autoPreviewPrefetch,
            mediaImageLoader = mediaImageLoader,
            observeLiveResource = observeLiveResource,
        )
    }
}

internal fun clearMediaTypeClassificationCacheForTest() {
    clearResourceMediaClassificationCacheForTest()
}

internal fun mediaTypeClassificationCacheSizeForTest(): Int {
    return resourceMediaClassificationCacheSizeForTest()
}
