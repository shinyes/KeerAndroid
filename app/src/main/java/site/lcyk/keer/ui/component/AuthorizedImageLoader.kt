package site.lcyk.keer.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import coil3.ImageLoader
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import site.lcyk.keer.viewmodel.LocalUserState

@Composable
fun rememberAuthorizedImageLoader(): ImageLoader {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val userStateViewModel = LocalUserState.current
    return remember(appContext, userStateViewModel.okHttpClient) {
        ImageLoader.Builder(appContext)
            .components {
                add(OkHttpNetworkFetcherFactory(callFactory = { userStateViewModel.okHttpClient }))
            }
            .build()
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
        ImageLoader.Builder(appContext)
            .components {
                add(OkHttpNetworkFetcherFactory(callFactory = { userStateViewModel.okHttpClient }))
            }
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizeBytes(memoryCacheBytes)
                    .build()
            }
            .build()
    }
}

private fun resolveMemoMediaMemoryCacheBytes(): Long {
    val maxHeapBytes = Runtime.getRuntime().maxMemory().coerceAtLeast(1L)
    val preferredBytes = (maxHeapBytes * 22L) / 100L
    return preferredBytes.coerceIn(MEMO_MEDIA_MIN_CACHE_BYTES, MEMO_MEDIA_MAX_CACHE_BYTES)
}

private const val MEMO_MEDIA_MIN_CACHE_BYTES = 32L * 1024L * 1024L
private const val MEMO_MEDIA_MAX_CACHE_BYTES = 192L * 1024L * 1024L
