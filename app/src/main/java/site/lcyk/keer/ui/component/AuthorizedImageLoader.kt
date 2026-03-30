package site.lcyk.keer.ui.component

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import coil3.ImageLoader
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import site.lcyk.keer.viewmodel.LocalUserState

@Composable
fun rememberAuthorizedImageLoader(): ImageLoader {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val userStateViewModel = LocalUserState.current
    return remember(appContext, userStateViewModel.okHttpClient) {
        SharedImageLoaderRegistry.getOrCreateAuthorizedLoader(
            appContext = appContext,
            okHttpClient = userStateViewModel.okHttpClient,
        )
    }
}

@Composable
fun rememberMemoMediaImageLoader(): ImageLoader {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val userStateViewModel = LocalUserState.current
    val memoryCacheBytes = remember(appContext) {
        resolveMemoMediaMemoryCacheBytes()
    }
    return remember(appContext, userStateViewModel.okHttpClient, memoryCacheBytes) {
        SharedImageLoaderRegistry.getOrCreateMemoMediaLoader(
            appContext = appContext,
            okHttpClient = userStateViewModel.okHttpClient,
            memoryCacheBytes = memoryCacheBytes,
        )
    }
}

@Composable
fun rememberThumbnailListImageLoader(): ImageLoader {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val userStateViewModel = LocalUserState.current
    val memoryCacheBytes = remember(appContext) {
        resolveThumbnailListMemoryCacheBytes()
    }
    return remember(appContext, userStateViewModel.okHttpClient, memoryCacheBytes) {
        SharedImageLoaderRegistry.getOrCreateThumbnailListLoader(
            appContext = appContext,
            okHttpClient = userStateViewModel.okHttpClient,
            memoryCacheBytes = memoryCacheBytes,
        )
    }
}

private fun resolveMemoMediaMemoryCacheBytes(): Long {
    val maxHeapBytes = Runtime.getRuntime().maxMemory().coerceAtLeast(1L)
    val preferredBytes = (maxHeapBytes * 24L) / 100L
    return preferredBytes.coerceIn(MEMO_MEDIA_MIN_CACHE_BYTES, MEMO_MEDIA_MAX_CACHE_BYTES)
}

private fun resolveThumbnailListMemoryCacheBytes(): Long {
    val maxHeapBytes = Runtime.getRuntime().maxMemory().coerceAtLeast(1L)
    val preferredBytes = (maxHeapBytes * 8L) / 100L
    return preferredBytes.coerceIn(THUMBNAIL_LIST_MIN_CACHE_BYTES, THUMBNAIL_LIST_MAX_CACHE_BYTES)
}

private object SharedImageLoaderRegistry {
    private val lock = Any()
    private var authorizedLoader: ImageLoader? = null
    private var authorizedClient: OkHttpClient? = null
    private var mediaLoader: ImageLoader? = null
    private var mediaClient: OkHttpClient? = null
    private var mediaCacheBytes: Long = -1L
    private var thumbnailListLoader: ImageLoader? = null
    private var thumbnailListClient: OkHttpClient? = null
    private var thumbnailListCacheBytes: Long = -1L

    fun getOrCreateAuthorizedLoader(
        appContext: Context,
        okHttpClient: OkHttpClient,
    ): ImageLoader {
        synchronized(lock) {
            val existing = authorizedLoader
            if (existing != null && authorizedClient === okHttpClient) {
                return existing
            }
            val created = buildAuthorizedImageLoader(
                appContext = appContext,
                okHttpClient = okHttpClient,
            )
            authorizedLoader = created
            authorizedClient = okHttpClient
            return created
        }
    }

    fun getOrCreateMemoMediaLoader(
        appContext: Context,
        okHttpClient: OkHttpClient,
        memoryCacheBytes: Long,
    ): ImageLoader {
        synchronized(lock) {
            val existing = mediaLoader
            if (existing != null && mediaClient === okHttpClient && mediaCacheBytes == memoryCacheBytes) {
                return existing
            }
            val created = buildMemoMediaImageLoader(
                appContext = appContext,
                okHttpClient = okHttpClient,
                memoryCacheBytes = memoryCacheBytes,
            )
            mediaLoader = created
            mediaClient = okHttpClient
            this.mediaCacheBytes = memoryCacheBytes
            return created
        }
    }

    fun getOrCreateThumbnailListLoader(
        appContext: Context,
        okHttpClient: OkHttpClient,
        memoryCacheBytes: Long,
    ): ImageLoader {
        synchronized(lock) {
            val existing = thumbnailListLoader
            if (existing != null &&
                thumbnailListClient === okHttpClient &&
                thumbnailListCacheBytes == memoryCacheBytes
            ) {
                return existing
            }
            val created = buildThumbnailListImageLoader(
                appContext = appContext,
                okHttpClient = okHttpClient,
                memoryCacheBytes = memoryCacheBytes,
            )
            thumbnailListLoader = created
            thumbnailListClient = okHttpClient
            thumbnailListCacheBytes = memoryCacheBytes
            return created
        }
    }
}

private fun buildAuthorizedImageLoader(
    appContext: Context,
    okHttpClient: OkHttpClient,
): ImageLoader {
    return ImageLoader.Builder(appContext)
        .components {
            add(OkHttpNetworkFetcherFactory(callFactory = { okHttpClient }))
        }
        .build()
}

private fun buildMemoMediaImageLoader(
    appContext: Context,
    okHttpClient: OkHttpClient,
    memoryCacheBytes: Long,
): ImageLoader {
    return ImageLoader.Builder(appContext)
        .components {
            add(OkHttpNetworkFetcherFactory(callFactory = { okHttpClient }))
        }
        .memoryCache {
            MemoryCache.Builder()
                .maxSizeBytes(memoryCacheBytes)
                .build()
        }
        .build()
}

private fun buildThumbnailListImageLoader(
    appContext: Context,
    okHttpClient: OkHttpClient,
    memoryCacheBytes: Long,
): ImageLoader {
    val throttledClient = okHttpClient.newBuilder()
        .dispatcher(
            Dispatcher().apply {
                maxRequests = THUMBNAIL_LIST_MAX_REQUESTS
                maxRequestsPerHost = THUMBNAIL_LIST_MAX_REQUESTS_PER_HOST
            }
        )
        .build()
    return ImageLoader.Builder(appContext)
        .components {
            add(OkHttpNetworkFetcherFactory(callFactory = { throttledClient }))
        }
        .memoryCache {
            MemoryCache.Builder()
                .maxSizeBytes(memoryCacheBytes)
                .build()
        }
        .build()
}

private const val THUMBNAIL_LIST_MIN_CACHE_BYTES = 24L * 1024L * 1024L
private const val THUMBNAIL_LIST_MAX_CACHE_BYTES = 96L * 1024L * 1024L
private const val THUMBNAIL_LIST_MAX_REQUESTS = 6
private const val THUMBNAIL_LIST_MAX_REQUESTS_PER_HOST = 4
private const val MEMO_MEDIA_MIN_CACHE_BYTES = 48L * 1024L * 1024L
private const val MEMO_MEDIA_MAX_CACHE_BYTES = 224L * 1024L * 1024L
