package site.lcyk.keer.ui.component

import android.content.Context
import android.net.Uri
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.net.toUri
import coil3.annotation.ExperimentalCoilApi
import coil3.compose.AsyncImage
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import site.lcyk.keer.data.model.Account
import site.lcyk.keer.data.local.entity.ResourceEntity
import site.lcyk.keer.data.model.ResourceRepresentable
import site.lcyk.keer.data.security.AttachmentEncryptionManager
import site.lcyk.keer.data.security.EncryptedBlobVariant
import site.lcyk.keer.data.service.VideoPlayerCache
import site.lcyk.keer.viewmodel.LocalMemos
import site.lcyk.keer.viewmodel.LocalUserState
import okhttp3.OkHttpClient
import timber.log.Timber

@OptIn(ExperimentalCoilApi::class)
@UnstableApi
@Composable
fun MemoVideo(
    resource: ResourceRepresentable,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val userStateViewModel = LocalUserState.current
    val currentAccount by userStateViewModel.currentAccount.collectAsState(initial = null)
    val memosViewModel = LocalMemos.current
    val observedResource = rememberObservedMemoResource(resource)
    val liveResource = observedResource.resource
    var showPlayerDialog by remember(resource.remoteId, resource.uri, resource.localUri) {
        mutableStateOf(false)
    }
    val imageLoader = rememberAuthorizedImageLoader()
    val previewModel = remember(
        liveResource.thumbnailLocalUri,
        liveResource.thumbnailUri,
        liveResource.localUri,
        liveResource.uri
    ) {
        resolveMemoVideoPreviewUri(liveResource)
    }

    LaunchedEffect(
        observedResource.tracked,
        (liveResource as? ResourceEntity)?.identifier,
        liveResource.thumbnailUri,
        liveResource.thumbnailLocalUri,
        currentAccount?.accountKey()
    ) {
        if (!observedResource.tracked) {
            return@LaunchedEffect
        }
        val resourceEntity = liveResource as? ResourceEntity ?: return@LaunchedEffect
        ensureMemoVideoCardPreview(
            context = context,
            okHttpClient = userStateViewModel.okHttpClient,
            resource = resourceEntity,
            currentAccountKey = currentAccount?.accountKey(),
            cacheResourceThumbnail = { identifier, downloadedUri ->
                memosViewModel.cacheResourceThumbnail(identifier, downloadedUri)
            }
        )
    }

    Box(
        modifier = modifier
            .aspectRatio(1f)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable { showPlayerDialog = true },
        contentAlignment = Alignment.Center
    ) {
        AsyncImage(
            model = previewModel,
            imageLoader = imageLoader,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            onError = {
                Timber.d("Failed to load memo video preview: %s", previewModel)
            }
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.22f))
        )
        Icon(
            imageVector = Icons.Filled.PlayArrow,
            contentDescription = null,
            modifier = Modifier.size(40.dp),
            tint = Color.White
        )
    }

    if (showPlayerDialog) {
        MemoVideoPlayerDialog(
            resource = liveResource,
            onDismiss = { showPlayerDialog = false }
        )
    }
}

@UnstableApi
@Composable
private fun MemoVideoPlayerDialog(
    resource: ResourceRepresentable,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
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
            is Account.KeerV2 -> account.info.accessToken
            else -> ""
        }
        if (accessToken.isBlank()) null else "Bearer $accessToken"
    }
    val currentAccountKey = remember(currentAccount) { currentAccount?.accountKey() }
    val canStartPlayback = !isRemoteSource || !authHeaderValue.isNullOrBlank()
    var playbackError by remember(sourceUri) { mutableStateOf(false) }

    val player = remember(sourceUri, authHeaderValue, canStartPlayback, currentAccountKey) {
        if (!canStartPlayback) {
            null
        } else {
            buildVideoPlayer(
                context = context,
                sourceUri = sourceUri,
                authHeaderValue = authHeaderValue,
                currentAccountKey = currentAccountKey,
                resource = resource,
                okHttpClient = userStateViewModel.okHttpClient,
            )
        }
    }

    DisposableEffect(player) {
        val safePlayer = player ?: return@DisposableEffect onDispose {}
        playbackError = false
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                playbackError = true
            }
        }
        safePlayer.addListener(listener)
        safePlayer.prepare()
        safePlayer.playWhenReady = true
        onDispose {
            safePlayer.removeListener(listener)
            safePlayer.release()
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
            if (player != null) {
                AndroidView(
                    factory = { playerContext ->
                        PlayerView(playerContext).apply {
                            useController = true
                            keepScreenOn = true
                            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                            this.player = player
                        }
                    },
                    update = { view ->
                        if (view.player !== player) {
                            view.player = player
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Text(
                    text = "正在准备播放...",
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(horizontal = 24.dp)
                )
            }

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

private fun resolveMemoVideoPreviewUri(resource: ResourceRepresentable): String {
    val localThumbnail = resolveUsableThumbnailLocalUri(resource.thumbnailLocalUri)
    if (!localThumbnail.isNullOrBlank()) {
        return localThumbnail
    }
    if (!resource.encryptionMetadata.isNullOrBlank()) {
        val local = resource.localUri?.trim().orEmpty()
        if (local.isNotEmpty()) {
            return local
        }
        return ""
    }
    val thumbnail = resource.thumbnailUri?.trim().orEmpty()
    if (thumbnail.isNotEmpty()) {
        return thumbnail
    }
    val local = resource.localUri?.trim().orEmpty()
    if (local.isNotEmpty()) {
        return local
    }
    return resource.uri
}

@UnstableApi
private fun buildVideoPlayer(
    context: Context,
    sourceUri: Uri,
    authHeaderValue: String?,
    currentAccountKey: String?,
    resource: ResourceRepresentable,
    okHttpClient: OkHttpClient,
): ExoPlayer {
    val isRemoteHttpSource = sourceUri.scheme.equals("http", ignoreCase = true) ||
        sourceUri.scheme.equals("https", ignoreCase = true)
    val encryptedFactory = resource.encryptionMetadata
        ?.takeIf { it.isNotBlank() }
        ?.takeIf { isRemoteHttpSource }
        ?.let {
            AttachmentEncryptionManager(context.applicationContext).createStreamingDataSourceFactory(
                accountKey = resolveResourceAccountKey(resource, currentAccountKey),
                okHttpClient = okHttpClient,
                sourceUrl = sourceUri.toString(),
                rawMetadata = it,
                variant = EncryptedBlobVariant.MAIN,
            )
        }

    val httpDataSourceFactory = DefaultHttpDataSource.Factory()
        .setAllowCrossProtocolRedirects(true)
        .setConnectTimeoutMs(15_000)
        .setReadTimeoutMs(30_000)
        .setUserAgent("keer-android")
    if (!authHeaderValue.isNullOrBlank()) {
        httpDataSourceFactory.setDefaultRequestProperties(
            mapOf("Authorization" to authHeaderValue)
        )
    }

    val upstreamFactory = DefaultDataSource.Factory(context, httpDataSourceFactory)
    val mediaSourceFactory = if (encryptedFactory != null) {
        DefaultMediaSourceFactory(encryptedFactory)
    } else {
        val cacheDataSourceFactory = CacheDataSource.Factory()
            .setCache(VideoPlayerCache.get(context))
            .setUpstreamDataSourceFactory(upstreamFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        DefaultMediaSourceFactory(cacheDataSourceFactory)
    }
    val loadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMs(
            20_000,
            120_000,
            1_000,
            2_000
        )
        .build()

    return ExoPlayer.Builder(context)
        .setLoadControl(loadControl)
        .setMediaSourceFactory(mediaSourceFactory)
        .build()
        .apply {
            setMediaItem(MediaItem.fromUri(sourceUri))
        }
}
