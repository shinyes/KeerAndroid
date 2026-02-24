package site.lcyk.keer.data.module

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.JavaNetCookieJar
import okhttp3.OkHttpClient
import site.lcyk.keer.data.service.DebugHttpLogInterceptor
import java.net.CookieManager
import java.net.CookiePolicy

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    fun provideOkHttpClient(
        debugHttpLogInterceptor: DebugHttpLogInterceptor
    ): OkHttpClient {
        val cookieManager = CookieManager()
        cookieManager.setCookiePolicy(CookiePolicy.ACCEPT_ALL)
        return OkHttpClient.Builder()
            .cookieJar(JavaNetCookieJar(cookieManager))
            .addInterceptor(debugHttpLogInterceptor)
            .build()
    }
}
