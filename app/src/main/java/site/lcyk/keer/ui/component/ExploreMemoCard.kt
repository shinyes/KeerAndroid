package site.lcyk.keer.ui.component

import android.text.TextUtils
import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import site.lcyk.keer.data.model.Memo
import site.lcyk.keer.util.normalizeTagList

@Composable
fun ExploreMemoCard(
    memo: Memo
) {
    val displayTags = remember(memo.tags) { normalizeTagList(memo.tags) }

    Card(
        modifier = Modifier
            .padding(horizontal = 15.dp, vertical = 10.dp)
            .fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(bottom = 10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 15.dp, top = 15.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    DateUtils.getRelativeTimeSpanString(memo.date.toEpochMilli(), System.currentTimeMillis(), DateUtils.SECOND_IN_MILLIS).toString(),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.outline
                )
                if (displayTags.isNotEmpty()) {
                    LazyRow(
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        items(displayTags, key = { it }) { tag ->
                            KeerTagChip(tag = tag)
                        }
                    }
                }

                if (memo.creator != null && !TextUtils.isEmpty(memo.creator.name)) {
                    Text(
                        "@${memo.creator.name}",
                        modifier = Modifier.padding(start = 10.dp, end = 15.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }

            MemoContent(memo, previewMode = false)
        }
    }
}
