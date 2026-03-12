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
import site.lcyk.keer.data.model.MemoQuotePreview
import site.lcyk.keer.ext.string

@Composable
fun MemoQuoteReferenceCard(
    quotedMemo: MemoQuotePreview?,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    val previewText = remember(quotedMemo?.previewText, quotedMemo?.hasResources) {
        val source = quotedMemo ?: return@remember ""
        if (source.previewText.isNotEmpty()) {
            source.previewText
        } else if (source.hasResources) {
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
                if (quotedMemo?.date != null) {
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
