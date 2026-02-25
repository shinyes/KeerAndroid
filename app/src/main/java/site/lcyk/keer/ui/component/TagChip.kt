package site.lcyk.keer.ui.component

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import site.lcyk.keer.R
import site.lcyk.keer.util.normalizeTagName

private val TagChipShape = RoundedCornerShape(18.dp)
private val TagChipHeight = 32.dp

@Composable
fun KeerTagChip(
    tag: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {}
) {
    val normalizedTag = normalizeTagName(tag)
    if (normalizedTag.isEmpty()) {
        return
    }

    AssistChip(
        modifier = modifier.height(TagChipHeight),
        onClick = onClick,
        label = {
            Text(
                text = normalizedTag,
                style = MaterialTheme.typography.labelMedium
            )
        },
        shape = TagChipShape,
        border = null,
        colors = AssistChipDefaults.assistChipColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
            labelColor = MaterialTheme.colorScheme.onSurface
        )
    )
}

@Composable
fun KeerRemovableTagChip(
    tag: String,
    modifier: Modifier = Modifier,
    onRemove: () -> Unit
) {
    val normalizedTag = normalizeTagName(tag)
    if (normalizedTag.isEmpty()) {
        return
    }

    AssistChip(
        modifier = modifier.height(TagChipHeight),
        onClick = onRemove,
        label = {
            Text(
                text = normalizedTag,
                style = MaterialTheme.typography.labelMedium
            )
        },
        trailingIcon = {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = stringResource(R.string.remove),
                modifier = Modifier.size(16.dp)
            )
        },
        shape = TagChipShape,
        border = null,
        colors = AssistChipDefaults.assistChipColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
            labelColor = MaterialTheme.colorScheme.onSurface,
            trailingIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant
        )
    )
}
