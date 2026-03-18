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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import site.lcyk.keer.ui.page.common.LocalRootNavController
import site.lcyk.keer.ui.page.common.navigateToTagPage
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
) {
    val rootNavController = LocalRootNavController.current
    val (text, previewed) = remember(memo.content, previewMode) {
        if (previewMode) {
            extractPreviewContent(markdownText = memo.content)
        } else {
            Pair(memo.content, false)
        }
    }
    val handleTagClick = remember(rootNavController, onTagClick) {
        onTagClick ?: { tag ->
            rootNavController.navigateToTagPage(tag)
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
            onTagClick = handleTagClick
        )

        MemoResourceContent(
            memo = memo,
            autoPreviewPrefetch = autoPreviewPrefetch,
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
) {
    val cols = 3

    val mediaList = memo.resources.filter { it.isMediaResource() }
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
                            )
                        }
                    } else {
                        Spacer(modifier = Modifier.fillMaxWidth(1f / cols))
                    }
                }
            }
        }
    }
    memo.resources.filterNot { it.isMediaResource() }.forEach { resource ->
        Attachment(resource)
    }
}
