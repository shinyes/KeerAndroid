package site.lcyk.keer.ui.component

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import coil3.ImageLoader
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
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

private fun resolveAuthorizedMemoryCacheBytes(): Long {
    val maxHeapBytes = Runtime.getRuntime().maxMemory().coerceAtLeast(1L)
    val preferredBytes = (maxHeapBytes * 8L) / 100L
    return preferredBytes.coerceIn(AUTHORIZED_MIN_CACHE_BYTES, AUTHORIZED_MAX_CACHE_BYTES)
}

private fun resolveMemoMediaMemoryCacheBytes(): Long {
    val maxHeapBytes = Runtime.getRuntime().maxMemory().coerceAtLeast(1L)
    val preferredBytes = (maxHeapBytes * 16L) / 100L
    return preferredBytes.coerceIn(MEMO_MEDIA_MIN_CACHE_BYTES, MEMO_MEDIA_MAX_CACHE_BYTES)
}

private object SharedImageLoaderRegistry {
    private val lock = Any()
    private var authorizedLoader: ImageLoader? = null
    private var authorizedClient: OkHttpClient? = null
    private var mediaLoader: ImageLoader? = null
    private var mediaClient: OkHttpClient? = null
    private var mediaCacheBytes: Long = -1L

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
}

private fun buildAuthorizedImageLoader(
    appContext: Context,
    okHttpClient: OkHttpClient,
): ImageLoader {
    return ImageLoader.Builder(appContext)
        .components {
            add(OkHttpNetworkFetcherFactory(callFactory = { okHttpClient }))
        }
        .memoryCache {
            MemoryCache.Builder()
                .maxSizeBytes(resolveAuthorizedMemoryCacheBytes())
                .build()
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

private const val AUTHORIZED_MIN_CACHE_BYTES = 16L * 1024L * 1024L
private const val AUTHORIZED_MAX_CACHE_BYTES = 64L * 1024L * 1024L
private const val MEMO_MEDIA_MIN_CACHE_BYTES = 32L * 1024L * 1024L
private const val MEMO_MEDIA_MAX_CACHE_BYTES = 160L * 1024L * 1024L
