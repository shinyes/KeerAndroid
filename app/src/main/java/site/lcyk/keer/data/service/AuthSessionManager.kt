package site.lcyk.keer.data.service

import android.content.Context
import com.skydoves.sandwich.retrofit.adapters.ApiResponseCallAdapterFactory
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import site.lcyk.keer.data.api.AuthSessionResponse
import site.lcyk.keer.data.api.KeerV2Api
import site.lcyk.keer.data.api.RefreshSessionRequest
import site.lcyk.keer.data.model.Account
import site.lcyk.keer.data.model.UserData
import site.lcyk.keer.ext.settingsDataStore
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

data class KeerV2ClientBundle(
    val httpClient: OkHttpClient,
    val api: KeerV2Api,
)

@Singleton
class AuthSessionManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient,
    private val secureTokenStorage: SecureTokenStorage,
) {
    private val networkJson = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
    }

    private val authStateVersion = MutableStateFlow(0)
    private val tokenRefreshLocks = ConcurrentHashMap<String, Any>()

    val accounts = combine(context.settingsDataStore.data, authStateVersion) { settings, _ ->
        settings.usersList.mapNotNull(::parseAccountWithStoredTokens)
    }

    val currentAccount = combine(context.settingsDataStore.data, authStateVersion) { settings, _ ->
        settings.usersList.firstOrNull { it.accountKey == settings.currentUser }
            ?.let(::parseAccountWithStoredTokens)
    }

    fun createKeerV2Client(host: String, accountKey: String? = null): KeerV2ClientBundle {
        val client = okHttpClient.newBuilder().apply {
            if (!accountKey.isNullOrBlank()) {
                addInterceptor { chain ->
                    var request = chain.request()
                    if (shouldAttachAccessToken(request.url, host)) {
                        val accessToken = getStoredTokens(accountKey)?.accessToken.orEmpty()
                        if (accessToken.isNotBlank()) {
                            request = request.newBuilder()
                                .header("Authorization", "Bearer $accessToken")
                                .build()
                        }
                    }
                    chain.proceed(request)
                }
                authenticator { _, response ->
                    refreshRequestIfNeeded(host, accountKey, response)
                }
            }
        }.build()

        return KeerV2ClientBundle(
            httpClient = client,
            api = buildKeerV2Api(host, client),
        )
    }

    fun createKeerV2ClientWithAccessToken(host: String, accessToken: String): KeerV2ClientBundle {
        val client = okHttpClient.newBuilder()
            .addInterceptor { chain ->
                var request = chain.request()
                if (shouldAttachAccessToken(request.url, host) && accessToken.isNotBlank()) {
                    request = request.newBuilder()
                        .header("Authorization", "Bearer $accessToken")
                        .build()
                }
                chain.proceed(request)
            }
            .build()
        return KeerV2ClientBundle(
            httpClient = client,
            api = buildKeerV2Api(host, client),
        )
    }

    fun parseAccountWithStoredTokens(userData: UserData): Account? {
        val account = Account.parseUserData(userData) ?: return null
        val tokens = getStoredTokens(userData.accountKey)
        return when (account) {
            is Account.KeerV2 -> Account.KeerV2(
                account.info.copy(
                    accessToken = tokens?.accessToken.orEmpty(),
                    refreshToken = tokens?.refreshToken.orEmpty(),
                )
            )

            is Account.Local -> account
        }
    }

    fun persistTokens(account: Account) {
        when (account) {
            is Account.KeerV2 -> saveTokens(
                accountKey = account.accountKey(),
                accessToken = account.info.accessToken,
                refreshToken = account.info.refreshToken,
            )

            is Account.Local -> Unit
        }
    }

    fun getStoredTokens(accountKey: String): SecureTokenStorage.StoredTokens? {
        return secureTokenStorage.getTokens(accountKey)
    }

    fun saveTokens(accountKey: String, accessToken: String, refreshToken: String) {
        secureTokenStorage.saveTokens(accountKey, accessToken, refreshToken)
        notifyAuthStateChanged()
    }

    fun removeTokens(accountKey: String) {
        secureTokenStorage.removeToken(accountKey)
        tokenRefreshLocks.remove(accountKey)
        notifyAuthStateChanged()
    }

    private fun buildKeerV2Api(host: String, client: OkHttpClient): KeerV2Api {
        return Retrofit.Builder()
            .baseUrl(host)
            .client(client)
            .addConverterFactory(networkJson.asConverterFactory("application/json".toMediaType()))
            .addCallAdapterFactory(ApiResponseCallAdapterFactory.create())
            .build()
            .create(KeerV2Api::class.java)
    }

    private fun refreshRequestIfNeeded(host: String, accountKey: String, response: Response): Request? {
        if (!shouldAttachAccessToken(response.request.url, host)) {
            return null
        }
        if (responseCount(response) >= 2) {
            return null
        }
        if (response.request.url.encodedPath.endsWith("/api/v1/auth/refresh")) {
            return null
        }

        val lock = tokenRefreshLocks.getOrPut(accountKey) { Any() }
        synchronized(lock) {
            val latestTokens = getStoredTokens(accountKey) ?: return null
            if (latestTokens.refreshToken.isBlank()) {
                return null
            }

            val requestAccessToken = response.request.header("Authorization")
                ?.removePrefix("Bearer ")
                ?.trim()
                .orEmpty()
            if (
                requestAccessToken.isNotBlank() &&
                latestTokens.accessToken.isNotBlank() &&
                latestTokens.accessToken != requestAccessToken
            ) {
                return response.request.newBuilder()
                    .header("Authorization", "Bearer ${latestTokens.accessToken}")
                    .build()
            }

            val refreshedSession = refreshSessionBlocking(host, latestTokens.refreshToken) ?: return null
            saveTokens(
                accountKey = accountKey,
                accessToken = refreshedSession.accessToken,
                refreshToken = refreshedSession.refreshToken,
            )
            return response.request.newBuilder()
                .header("Authorization", "Bearer ${refreshedSession.accessToken}")
                .build()
        }
    }

    private fun refreshSessionBlocking(host: String, refreshToken: String): AuthSessionResponse? {
        val body = networkJson.encodeToString(
            RefreshSessionRequest.serializer(),
            RefreshSessionRequest(refreshToken = refreshToken),
        )
        val request = Request.Builder()
            .url(resolveApiUrl(host, "api/v1/auth/refresh"))
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        val response = runCatching {
            okHttpClient.newCall(request).execute()
        }.getOrNull() ?: return null

        response.use { httpResponse ->
            if (!httpResponse.isSuccessful) {
                return null
            }
            val responseBody = httpResponse.body.string()
            if (responseBody.isBlank()) {
                return null
            }
            return runCatching {
                networkJson.decodeFromString(AuthSessionResponse.serializer(), responseBody)
            }.getOrNull()
        }
    }

    private fun resolveApiUrl(host: String, path: String): HttpUrl {
        val baseUrl = host.toHttpUrlOrNull()
            ?: throw IllegalStateException("Invalid host: $host")
        return baseUrl.newBuilder()
            .encodedPath("/")
            .addEncodedPathSegments(path)
            .build()
    }

    private fun shouldAttachAccessToken(requestUrl: HttpUrl, host: String): Boolean {
        val baseUrl = host.toHttpUrlOrNull() ?: return false
        return requestUrl.scheme == baseUrl.scheme &&
            requestUrl.host == baseUrl.host &&
            requestUrl.port == baseUrl.port
    }

    private fun responseCount(response: Response): Int {
        var current: Response? = response
        var count = 1
        while (current?.priorResponse != null) {
            count += 1
            current = current.priorResponse
        }
        return count
    }

    private fun notifyAuthStateChanged() {
        authStateVersion.value = authStateVersion.value + 1
    }
}
