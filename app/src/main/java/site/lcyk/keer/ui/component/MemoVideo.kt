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
import coil3.ImageLoader
import coil3.annotation.ExperimentalCoilApi
import coil3.compose.AsyncImage
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import com.skydoves.sandwich.ApiResponse
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import site.lcyk.keer.data.model.Account
import site.lcyk.keer.data.local.entity.ResourceEntity
import site.lcyk.keer.data.model.ResourceRepresentable
import site.lcyk.keer.data.service.VideoPlayerCache
import site.lcyk.keer.viewmodel.LocalMemos
import site.lcyk.keer.viewmodel.LocalUserState
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.File

@OptIn(ExperimentalCoilApi::class)
@Composable
fun MemoVideo(
    resource: ResourceRepresentable,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val userStateViewModel = LocalUserState.current
    val memosViewModel = LocalMemos.current
    var showPlayerDialog by remember(resource.remoteId, resource.uri, resource.localUri) {
        mutableStateOf(false)
    }
    val imageLoader = remember(context, userStateViewModel.okHttpClient) {
        ImageLoader.Builder(context)
            .components {
                add(
                    OkHttpNetworkFetcherFactory(
                        callFactory = { userStateViewModel.okHttpClient }
                    )
                )
            }
            .build()
    }
    val previewModel = remember(
        resource.thumbnailLocalUri,
        resource.thumbnailUri,
        resource.localUri,
        resource.uri
    ) {
        resolveMemoVideoPreviewUri(resource)
    }

    LaunchedEffect(resource.remoteId, resource.thumbnailUri, resource.thumbnailLocalUri) {
        val resourceEntity = resource as? ResourceEntity ?: return@LaunchedEffect
        val localThumbnail = resourceEntity.thumbnailLocalUri?.trim().orEmpty()
        if (localThumbnail.isNotEmpty()) {
            return@LaunchedEffect
        }

        val remoteThumbnail = resourceEntity.thumbnailUri?.trim().orEmpty()
        if (remoteThumbnail.isEmpty()) {
            return@LaunchedEffect
        }
        val thumbnailUri = remoteThumbnail.toUri()
        if (thumbnailUri.scheme != "http" && thumbnailUri.scheme != "https") {
            return@LaunchedEffect
        }

        val downloaded = downloadMemoVideoThumbnailToTemp(
            context = context,
            okHttpClient = userStateViewModel.okHttpClient,
            url = remoteThumbnail,
            filename = resourceEntity.filename
        ) ?: return@LaunchedEffect

        try {
            val result = memosViewModel.cacheResourceThumbnail(resourceEntity.identifier, downloaded.toUri())
            if (result !is ApiResponse.Success) {
                Timber.d("Cache video thumbnail failed: %s", result)
            }
        } catch (e: Throwable) {
            Timber.d(e)
        } finally {
            downloaded.delete()
        }
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
    val canStartPlayback = !isRemoteSource || !authHeaderValue.isNullOrBlank()
    var playbackError by remember(sourceUri) { mutableStateOf(false) }

    val player = remember(sourceUri, authHeaderValue, canStartPlayback) {
        if (!canStartPlayback) {
            null
        } else {
            buildVideoPlayer(context, sourceUri, authHeaderValue)
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
    val localThumbnail = resource.thumbnailLocalUri?.trim().orEmpty()
    if (localThumbnail.isNotEmpty()) {
        return localThumbnail
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

private suspend fun downloadMemoVideoThumbnailToTemp(
    context: Context,
    okHttpClient: OkHttpClient,
    url: String,
    filename: String
): File? = withContext(Dispatchers.IO) {
    val request = Request.Builder().url(url).get().build()
    okHttpClient.newCall(request).execute().use { response ->
        if (!response.isSuccessful) {
            return@withContext null
        }
        val body = response.body
        val dir = File(context.cacheDir, "thumbnail_cache").also { it.mkdirs() }
        val suffix = "_${sanitizeMemoVideoThumbnailFilename(filename.ifBlank { "thumbnail" })}"
        val target = File.createTempFile("video_thumb_", suffix, dir)
        body.byteStream().use { input ->
            target.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        target
    }
}

private fun sanitizeMemoVideoThumbnailFilename(filename: String): String {
    return filename.replace(Regex("[^A-Za-z0-9._-]"), "_")
}

private fun buildVideoPlayer(
    context: Context,
    sourceUri: Uri,
    authHeaderValue: String?
): ExoPlayer {
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
    val cacheDataSourceFactory = CacheDataSource.Factory()
        .setCache(VideoPlayerCache.get(context))
        .setUpstreamDataSourceFactory(upstreamFactory)
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    val mediaSourceFactory = DefaultMediaSourceFactory(cacheDataSourceFactory)
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
