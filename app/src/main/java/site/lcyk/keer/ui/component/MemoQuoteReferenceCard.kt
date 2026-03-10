package site.lcyk.keer.ui.component

import android.text.format.DateUtils
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import site.lcyk.keer.R
import site.lcyk.keer.data.local.entity.MemoEntity
import site.lcyk.keer.ext.string

@Composable
fun MemoQuoteReferenceCard(
    quotedMemo: MemoEntity?,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    val previewText = remember(quotedMemo?.content, quotedMemo?.resources) {
        val source = quotedMemo ?: return@remember ""
        val extracted = extractPreviewContent(
            markdownText = source.content,
            maxLength = 180
        ).first.trim()
        if (extracted.isNotEmpty()) {
            extracted
        } else if (source.resources.isNotEmpty()) {
            R.string.quoted_memo_media_only.string
        } else {
            R.string.quoted_memo_empty.string
        }
    }
    val clickableModifier = if (quotedMemo != null && onClick != null) {
        Modifier
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick)
    } else {
        Modifier
    }

    Surface(
        modifier = modifier.then(clickableModifier),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = R.string.quote.string,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (quotedMemo != null) {
                    Text(
                        text = " - " + DateUtils.getRelativeTimeSpanString(
                            quotedMemo.date.toEpochMilli(),
                            System.currentTimeMillis(),
                            DateUtils.SECOND_IN_MILLIS
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }

            if (quotedMemo == null) {
                Text(
                    text = R.string.quoted_memo_unavailable.string,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    text = previewText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
