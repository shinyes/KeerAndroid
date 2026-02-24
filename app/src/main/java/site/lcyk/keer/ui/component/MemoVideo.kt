package site.lcyk.keer.ui.component

import android.net.Uri
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.net.toUri
import androidx.compose.runtime.collectAsState
import site.lcyk.keer.data.model.Account
import site.lcyk.keer.data.model.ResourceRepresentable
import site.lcyk.keer.viewmodel.LocalUserState

@Composable
fun MemoVideo(
    resource: ResourceRepresentable,
    modifier: Modifier = Modifier
) {
    var showPlayerDialog by remember(resource.remoteId, resource.uri, resource.localUri) {
        mutableStateOf(false)
    }

    Box(
        modifier = modifier
            .aspectRatio(1f)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable { showPlayerDialog = true },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Filled.PlayArrow,
            contentDescription = null,
            modifier = Modifier.size(40.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    if (showPlayerDialog) {
        MemoVideoPlayerDialog(
            resource = resource,
            onDismiss = { showPlayerDialog = false }
        )
    }
}

@Composable
private fun MemoVideoPlayerDialog(
    resource: ResourceRepresentable,
    onDismiss: () -> Unit
) {
    val userStateViewModel = LocalUserState.current
    val currentAccount by userStateViewModel.currentAccount.collectAsState(initial = null)
    val sourceUri = remember(resource.localUri, resource.uri) {
        (resource.localUri ?: resource.uri).toUri()
    }
    val isRemoteSource = remember(sourceUri) {
        sourceUri.scheme.equals("http", ignoreCase = true) ||
            sourceUri.scheme.equals("https", ignoreCase = true)
    }
    val authHeaderValue = remember(currentAccount) {
        val accessToken = when (val account = currentAccount) {
            is Account.MemosV0 -> account.info.accessToken
            is Account.MemosV1 -> account.info.accessToken
            else -> ""
        }
        if (accessToken.isBlank()) null else "Bearer $accessToken"
    }
    var playbackError by remember(sourceUri) { mutableStateOf(false) }
    var videoViewRef by remember { mutableStateOf<VideoView?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            videoViewRef?.stopPlayback()
            videoViewRef = null
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            AndroidView(
                factory = { playerContext ->
                    VideoView(playerContext).apply {
                        val mediaController = MediaController(playerContext)
                        mediaController.setAnchorView(this)
                        setMediaController(mediaController)
                        setOnPreparedListener { start() }
                        setOnErrorListener { _, _, _ ->
                            playbackError = true
                            true
                        }
                        openVideo(sourceUri, isRemoteSource, authHeaderValue)
                        videoViewRef = this
                    }
                },
                update = { view ->
                    if (videoViewRef !== view) {
                        videoViewRef = view
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            if (playbackError) {
                Text(
                    text = "视频播放失败",
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(horizontal = 24.dp)
                )
            }

            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = null,
                    tint = Color.White
                )
            }
        }
    }
}

private fun VideoView.openVideo(
    sourceUri: Uri,
    isRemoteSource: Boolean,
    authHeaderValue: String?
) {
    if (isRemoteSource && !authHeaderValue.isNullOrBlank()) {
        setVideoURI(sourceUri, mapOf("Authorization" to authHeaderValue))
    } else {
        setVideoURI(sourceUri)
    }
}
