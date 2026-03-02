package site.lcyk.keer.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import coil3.ImageLoader
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
