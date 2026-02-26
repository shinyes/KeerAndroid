package site.lcyk.keer.data.module

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.JavaNetCookieJar
import okhttp3.OkHttpClient
import site.lcyk.keer.data.service.ApiRequestMetadataInterceptor
import site.lcyk.keer.data.service.ApiRetryInterceptor
import site.lcyk.keer.data.service.DebugHttpLogInterceptor
import java.net.CookieManager
import java.net.CookiePolicy
import java.util.concurrent.TimeUnit

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    fun provideOkHttpClient(
        apiRequestMetadataInterceptor: ApiRequestMetadataInterceptor,
        apiRetryInterceptor: ApiRetryInterceptor,
        debugHttpLogInterceptor: DebugHttpLogInterceptor
    ): OkHttpClient {
        val cookieManager = CookieManager()
        cookieManager.setCookiePolicy(CookiePolicy.ACCEPT_ALL)
        return OkHttpClient.Builder()
            .cookieJar(JavaNetCookieJar(cookieManager))
            .retryOnConnectionFailure(true)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(90, TimeUnit.SECONDS)
            .callTimeout(120, TimeUnit.SECONDS)
            .addInterceptor(apiRequestMetadataInterceptor)
            .addInterceptor(apiRetryInterceptor)
            .addInterceptor(debugHttpLogInterceptor)
            .build()
    }
}
