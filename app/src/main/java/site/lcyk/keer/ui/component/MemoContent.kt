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
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import kotlinx.coroutines.delay
import site.lcyk.keer.R
import site.lcyk.keer.data.model.MemoRepresentable
import site.lcyk.keer.ext.string
import site.lcyk.keer.util.extractPreviewContent
import site.lcyk.keer.viewmodel.LocalUserState
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
    progressiveMediaEnabled: Boolean = false,
) {
    val (text, previewed) = remember(memo.content, previewMode) {
        if (previewMode) {
            extractPreviewContent(markdownText = memo.content)
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
            previewMode = previewMode,
            autoPreviewPrefetch = autoPreviewPrefetch,
            mediaImageLoader = mediaImageLoader,
            uiFrozen = uiFrozen,
            progressiveMediaEnabled = progressiveMediaEnabled,
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
    previewMode: Boolean = false,
    autoPreviewPrefetch: Boolean = true,
    mediaImageLoader: ImageLoader? = null,
    uiFrozen: Boolean = false,
    progressiveMediaEnabled: Boolean = false,
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
    var settlePhaseReady by remember(mediaList.size, previewMode, progressiveMediaEnabled) {
        mutableStateOf(false)
    }
    LaunchedEffect(mediaList.size, previewMode, progressiveMediaEnabled, uiFrozen) {
        if (!previewMode || !progressiveMediaEnabled || mediaList.isEmpty()) {
            settlePhaseReady = true
            return@LaunchedEffect
        }
        if (uiFrozen) {
            settlePhaseReady = false
            return@LaunchedEffect
        }
        settlePhaseReady = false
        if (mediaList.size > SETTLE_PHASE_MEDIA_LIMIT) {
            delay(SETTLE_PHASE_FULL_DELAY_MS)
        }
        settlePhaseReady = true
    }

    val visibleMediaCount = remember(
        mediaList.size,
        previewMode,
        progressiveMediaEnabled,
        uiFrozen,
        settlePhaseReady,
    ) {
        computeProgressiveVisibleMediaCount(
            totalMediaCount = mediaList.size,
            previewMode = previewMode,
            progressiveMediaEnabled = progressiveMediaEnabled,
            uiFrozen = uiFrozen,
            settlePhaseReady = settlePhaseReady,
        )
    }
    val renderedMedia = remember(mediaList, visibleMediaCount) {
        mediaList.take(visibleMediaCount.coerceAtMost(mediaList.size))
    }
    val hiddenMediaCount = (mediaList.size - renderedMedia.size).coerceAtLeast(0)
    val renderSlotCount = renderedMedia.size + if (hiddenMediaCount > 0) 1 else 0

    if (renderSlotCount > 0) {
        val rows = ceil(renderSlotCount.toFloat() / cols).toInt()
        for (rowIndex in 0 until rows) {
            Row {
                for (colIndex in 0 until cols) {
                    val index = rowIndex * cols + colIndex
                    if (index < renderedMedia.size) {
                        Box(modifier = Modifier.fillMaxWidth(1f / (cols - colIndex))) {
                            MemoMedia(
                                resource = renderedMedia[index],
                                modifier = Modifier
                                    .aspectRatio(1f)
                                    .padding(2.dp)
                                    .clip(RoundedCornerShape(4.dp)),
                                autoPreviewPrefetch = autoPreviewPrefetch,
                                mediaImageLoader = mediaImageLoader,
                                observeLiveResource = !uiFrozen,
                            )
                        }
                    } else if (hiddenMediaCount > 0 && index == renderedMedia.size) {
                        HiddenMediaCountCell(
                            hiddenMediaCount = hiddenMediaCount,
                            modifier = Modifier
                                .fillMaxWidth(1f / (cols - colIndex))
                                .aspectRatio(1f)
                                .padding(2.dp)
                                .clip(RoundedCornerShape(4.dp))
                        )
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

@Composable
private fun HiddenMediaCountCell(
    hiddenMediaCount: Int,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "+$hiddenMediaCount",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

internal fun computeProgressiveVisibleMediaCount(
    totalMediaCount: Int,
    previewMode: Boolean,
    progressiveMediaEnabled: Boolean,
    uiFrozen: Boolean,
    settlePhaseReady: Boolean,
): Int {
    val normalizedTotal = totalMediaCount.coerceAtLeast(0)
    if (normalizedTotal == 0) {
        return 0
    }
    if (!previewMode || !progressiveMediaEnabled) {
        return normalizedTotal
    }
    if (uiFrozen) {
        return minOf(normalizedTotal, SCROLL_PHASE_MEDIA_LIMIT)
    }
    if (settlePhaseReady) {
        return normalizedTotal
    }
    return minOf(normalizedTotal, SETTLE_PHASE_MEDIA_LIMIT)
}

internal const val SCROLL_PHASE_MEDIA_LIMIT = 6
internal const val SETTLE_PHASE_MEDIA_LIMIT = 12
internal const val SETTLE_PHASE_FULL_DELAY_MS = 120L
