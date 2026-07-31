package site.lcyk.keer.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import site.lcyk.keer.R
import site.lcyk.keer.data.model.MemoRepresentable
import site.lcyk.keer.ext.string
import site.lcyk.keer.viewmodel.LocalUserState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.ceil

@Composable
fun MemoContent(
    memo: MemoRepresentable,
    previewMode: Boolean = false,
    checkboxChange: (checked: Boolean, startOffset: Int, endOffset: Int) -> Unit = { _, _, _ -> },
    onViewMore: (() -> Unit)? = null,
    selectable: Boolean = false,
    onTagClick: ((String) -> Unit)? = null,
    autoPreviewPrefetch: Boolean = true,
    mediaImageLoader: ImageLoader? = null,
    uiFrozen: Boolean = false,
) {
    val renderVersionKey = remember(memo) {
        buildMemoRenderVersionKey(memo)
    }
    // A preview cache hit is a cheap synchronized LRU lookup that can stay in composition.
    // A cache miss parses the whole Markdown document, so it is warmed off the main thread
    // and the result is applied on the next recomposition via previewWarmVersion.
    var previewWarmVersion by remember(renderVersionKey, memo.content, previewMode) {
        mutableStateOf(0)
    }
    LaunchedEffect(renderVersionKey, memo.content, previewMode) {
        if (previewMode && MemoRenderCache.getPreview(renderVersionKey) == null) {
            withContext(Dispatchers.Default) {
                resolveMemoPreviewSnapshot(
                    markdownText = memo.content,
                    versionKey = renderVersionKey,
                )
            }
            previewWarmVersion++
        }
    }
    val (text, previewed) = remember(renderVersionKey, memo.content, previewMode, previewWarmVersion) {
        if (previewMode) {
            MemoRenderCache.getPreview(renderVersionKey)
                ?.let { snapshot -> snapshot.text to snapshot.previewed }
                ?: Pair("", false)
        } else {
            Pair(memo.content, false)
        }
    }

    Column(
        modifier = Modifier.padding(start = 15.dp, end = 15.dp, bottom = 10.dp)
    ) {
        Markdown(
            text,
            imageBaseUrl = LocalUserState.current.host,
            checkboxChange = checkboxChange,
            selectable = selectable,
            // Inline #tags in memo text no longer trigger navigation.
            onTagClick = null
        )

        MemoResourceContent(
            memo = memo,
            autoPreviewPrefetch = autoPreviewPrefetch,
            mediaImageLoader = mediaImageLoader,
            uiFrozen = uiFrozen,
        )

        if (previewed && onViewMore != null) {
            Row {
                Text(
                    text = R.string.view_more.string,
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyMedium.copy(textDecoration = TextDecoration.Underline),
                    modifier = Modifier.clickable(onClick = onViewMore)
                )
            }
        }
    }
}

@Composable
fun MemoResourceContent(
    memo: MemoRepresentable,
    autoPreviewPrefetch: Boolean = true,
    mediaImageLoader: ImageLoader? = null,
    uiFrozen: Boolean = false,
) {
    val cols = 3

    val (mediaList, attachmentList) = remember(memo.resources) {
        val media = mutableListOf<site.lcyk.keer.data.model.ResourceRepresentable>()
        val attachments = mutableListOf<site.lcyk.keer.data.model.ResourceRepresentable>()
        memo.resources.forEach { resource ->
            if (resource.isMediaResource()) {
                media += resource
            } else {
                attachments += resource
            }
        }
        media to attachments
    }
    if (mediaList.isNotEmpty()) {
        val rows = ceil(mediaList.size.toFloat() / cols).toInt()
        for (rowIndex in 0 until rows) {
            Row {
                for (colIndex in 0 until cols) {
                    val index = rowIndex * cols + colIndex
                    if (index < mediaList.size) {
                        Box(modifier = Modifier.fillMaxWidth(1f / (cols - colIndex))) {
                            MemoMedia(
                                resource = mediaList[index],
                                modifier = Modifier
                                    .aspectRatio(1f)
                                    .padding(2.dp)
                                    .clip(RoundedCornerShape(4.dp)),
                                autoPreviewPrefetch = autoPreviewPrefetch,
                                mediaImageLoader = mediaImageLoader,
                                observeLiveResource = !uiFrozen,
                            )
                        }
                    } else {
                        Spacer(modifier = Modifier.fillMaxWidth(1f / cols))
                    }
                }
            }
        }
    }
    attachmentList.forEach { resource ->
        Attachment(resource)
    }
}
