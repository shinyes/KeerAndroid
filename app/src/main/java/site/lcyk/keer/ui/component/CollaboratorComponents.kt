package site.lcyk.keer.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import site.lcyk.keer.R
import site.lcyk.keer.data.model.CollaboratorProfile
import site.lcyk.keer.ext.string
import site.lcyk.keer.viewmodel.LocalUserState

@Composable
fun CollaboratorAvatarStack(
    collaboratorIds: List<String>,
    collaboratorProfiles: Map<String, CollaboratorProfile>,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    if (collaboratorIds.isEmpty()) {
        return
    }

    val imageLoader = rememberCollaboratorImageLoader()
    val maxVisibleAvatars = 4
    val visibleIds = collaboratorIds.take(maxVisibleAvatars)
    val hiddenCount = (collaboratorIds.size - visibleIds.size).coerceAtLeast(0)
    val totalSlots = visibleIds.size + if (hiddenCount > 0) 1 else 0

    val avatarSize = 20.dp
    val overlap = 7.dp
    val step = avatarSize - overlap
    val stackWidth = if (totalSlots <= 0) {
        0.dp
    } else {
        avatarSize + step * (totalSlots - 1)
    }

    val clickModifier = if (onClick != null) {
        Modifier.clickable(onClick = onClick)
    } else {
        Modifier
    }

    Box(
        modifier = modifier
            .padding(start = 8.dp, end = 6.dp)
            .width(stackWidth)
            .height(avatarSize)
            .then(clickModifier)
    ) {
        visibleIds.forEachIndexed { index, collaboratorId ->
            val profile = collaboratorProfiles[collaboratorId]
            val offsetX = step * index
            AvatarCircle(
                modifier = Modifier
                    .zIndex(index.toFloat())
                    .offset(x = offsetX),
                label = collaboratorLabel(profile, collaboratorId),
                avatarUrl = profile?.avatarUrl,
                imageLoader = imageLoader,
                size = avatarSize
            )
        }
        if (hiddenCount > 0) {
            val index = visibleIds.size
            val offsetX = step * index
            AvatarCircle(
                modifier = Modifier
                    .zIndex(index.toFloat())
                    .offset(x = offsetX),
                label = "+$hiddenCount",
                avatarUrl = null,
                imageLoader = imageLoader,
                size = avatarSize
            )
        }
    }
}

@Composable
fun CollaboratorListDialog(
    collaboratorIds: List<String>,
    collaboratorProfiles: Map<String, CollaboratorProfile>,
    onDismiss: () -> Unit
) {
    if (collaboratorIds.isEmpty()) {
        return
    }

    val imageLoader = rememberCollaboratorImageLoader()
    val collaborators = collaboratorIds.map { id ->
        collaboratorProfiles[id] ?: CollaboratorProfile(
            id = id,
            name = id,
            avatarUrl = null
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(R.string.collaborators.string) },
        text = {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(collaborators, key = { it.id }) { collaborator ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AvatarCircle(
                            modifier = Modifier.size(28.dp),
                            label = collaboratorLabel(collaborator, collaborator.id),
                            avatarUrl = collaborator.avatarUrl,
                            imageLoader = imageLoader,
                            size = 28.dp
                        )
                        Column(
                            modifier = Modifier.padding(start = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                text = collaborator.name,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = collaborator.id,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(R.string.close.string)
            }
        }
    )
}

@Composable
private fun rememberCollaboratorImageLoader(): ImageLoader {
    val context = LocalContext.current
    val userStateViewModel = LocalUserState.current
    return remember(userStateViewModel.okHttpClient) {
        ImageLoader.Builder(context)
            .components {
                add(OkHttpNetworkFetcherFactory(callFactory = { userStateViewModel.okHttpClient }))
            }
            .build()
    }
}

@Composable
private fun AvatarCircle(
    modifier: Modifier,
    label: String,
    avatarUrl: String?,
    imageLoader: ImageLoader,
    size: Dp
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, MaterialTheme.colorScheme.surface, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        if (!avatarUrl.isNullOrBlank()) {
            AsyncImage(
                model = avatarUrl,
                imageLoader = imageLoader,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
            )
        } else {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Clip,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun collaboratorLabel(profile: CollaboratorProfile?, fallbackId: String): String {
    val source = profile?.name?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: fallbackId
    return source.take(2).uppercase()
}
