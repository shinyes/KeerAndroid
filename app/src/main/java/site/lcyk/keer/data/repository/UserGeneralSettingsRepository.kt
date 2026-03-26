package site.lcyk.keer.data.repository

import com.skydoves.sandwich.ApiResponse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import site.lcyk.keer.R
import site.lcyk.keer.data.model.ExploreDrawerEntryConfig
import site.lcyk.keer.data.model.UserGeneralSettings
import site.lcyk.keer.data.model.withRenamedTagDrawerEntries
import site.lcyk.keer.data.model.withTagDrawerVisibility
import site.lcyk.keer.data.model.withoutTagDrawerEntries
import site.lcyk.keer.data.service.AccountLocalSettingsStore
import site.lcyk.keer.data.service.AccountService
import site.lcyk.keer.ext.string
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UserGeneralSettingsRepository @Inject constructor(
    private val accountService: AccountService,
    private val accountLocalSettingsStore: AccountLocalSettingsStore,
) {
    private val refreshMutex = Mutex()
    private val updateMutex = Mutex()
    private var refreshInFlight: CompletableDeferred<ApiResponse<UserGeneralSettings>>? = null

    @Volatile
    private var lastSuccessfulRefreshAtMillis: Long = 0L

    fun observeCurrentGeneralSettings(): Flow<UserGeneralSettings> {
        return accountLocalSettingsStore.observeCurrentGeneralSettings()
    }

    suspend fun refreshCurrentGeneralSettings(
        forceNetwork: Boolean = false,
        reason: String = "auto",
    ): ApiResponse<UserGeneralSettings> {
        val nowMillis = System.currentTimeMillis()
        if (!forceNetwork && nowMillis - lastSuccessfulRefreshAtMillis < GENERAL_SETTINGS_REFRESH_MIN_INTERVAL_MILLIS) {
            Timber.tag(GENERAL_SETTINGS_SYNC_TAG).d("throttled reason=%s", reason)
            return ApiResponse.Success(readCurrentCachedGeneralSettings())
        }

        val ownerDeferred: CompletableDeferred<ApiResponse<UserGeneralSettings>>
        val isOwner = refreshMutex.withLock {
            refreshInFlight?.let { inFlight ->
                ownerDeferred = inFlight
                return@withLock false
            }
            ownerDeferred = CompletableDeferred()
            refreshInFlight = ownerDeferred
            true
        }
        if (!isOwner) {
            Timber.tag(GENERAL_SETTINGS_SYNC_TAG).d("coalesced reason=%s", reason)
            return ownerDeferred.await()
        }

        if (forceNetwork) {
            Timber.tag(GENERAL_SETTINGS_SYNC_TAG).d("forced reason=%s", reason)
        }

        var result: ApiResponse<UserGeneralSettings>? = null
        try {
            result = runCatching { fetchRemoteGeneralSettings() }
                .getOrElse(ApiResponse.Companion::exception)
            if (result is ApiResponse.Success) {
                lastSuccessfulRefreshAtMillis = System.currentTimeMillis()
            }
            return result
        } finally {
            val completion = result ?: ApiResponse.Success(readCurrentCachedGeneralSettings())
            refreshMutex.withLock {
                if (refreshInFlight === ownerDeferred) {
                    if (!ownerDeferred.isCompleted) {
                        ownerDeferred.complete(completion)
                    }
                    refreshInFlight = null
                }
            }
        }
    }

    suspend fun updateMemoEditGesture(
        gesture: site.lcyk.keer.data.model.MemoEditGesture
    ): ApiResponse<UserGeneralSettings> {
        val current = readCurrentCachedGeneralSettings()
        return updateCurrentUserGeneralSettings(
            current.copy(memoEditGesture = gesture)
        )
    }

    suspend fun updateMemoColumns(
        columns: List<site.lcyk.keer.data.model.MemoColumnConfig>
    ): ApiResponse<UserGeneralSettings> {
        val current = readCurrentCachedGeneralSettings()
        return updateCurrentUserGeneralSettings(
            current.copy(memoColumns = columns)
        )
    }

    suspend fun updateExploreDrawerEntries(
        entries: List<ExploreDrawerEntryConfig>
    ): ApiResponse<UserGeneralSettings> {
        val current = readCurrentCachedGeneralSettings()
        return updateCurrentUserGeneralSettings(
            current.copy(exploreDrawerEntries = entries)
        )
    }

    suspend fun updateTagDrawerVisibility(
        tag: String,
        visibleInDrawer: Boolean,
    ): ApiResponse<UserGeneralSettings> {
        val current = readCurrentCachedGeneralSettings()
        return updateCurrentUserGeneralSettings(
            current.withTagDrawerVisibility(
                tag = tag,
                visibleInDrawer = visibleInDrawer,
            )
        )
    }

    suspend fun renameTagDrawerEntries(
        oldTag: String,
        newTag: String,
    ): ApiResponse<UserGeneralSettings> {
        val current = readCurrentCachedGeneralSettings()
        return updateCurrentUserGeneralSettings(
            current.withRenamedTagDrawerEntries(
                oldTag = oldTag,
                newTag = newTag,
            )
        )
    }

    suspend fun removeTagDrawerEntries(tag: String): ApiResponse<UserGeneralSettings> {
        val current = readCurrentCachedGeneralSettings()
        return updateCurrentUserGeneralSettings(
            current.withoutTagDrawerEntries(tag)
        )
    }

    suspend fun updateCurrentUserGeneralSettings(
        settings: UserGeneralSettings
    ): ApiResponse<UserGeneralSettings> {
        val remoteRepository = accountService.getRemoteRepository()
            ?: return ApiResponse.exception(
                IllegalStateException(R.string.current_account_no_settings_sync.string)
            )
        return updateMutex.withLock {
            val previousSettings = readCurrentCachedGeneralSettings()
            updateCurrentCachedGeneralSettings(settings)

            when (val response = remoteRepository.updateCurrentUserGeneralSettings(settings)) {
                is ApiResponse.Success -> {
                    updateCurrentCachedGeneralSettings(response.data)
                    ApiResponse.Success(response.data)
                }
                is ApiResponse.Failure.Error -> {
                    updateCurrentCachedGeneralSettings(previousSettings)
                    response
                }
                is ApiResponse.Failure.Exception -> {
                    updateCurrentCachedGeneralSettings(previousSettings)
                    response
                }
            }
        }
    }

    suspend fun applySyncedGeneralSettings(
        settings: UserGeneralSettings,
        reason: String = "stream",
    ): ApiResponse<UserGeneralSettings> {
        return try {
            updateCurrentCachedGeneralSettings(settings)
            lastSuccessfulRefreshAtMillis = System.currentTimeMillis()
            Timber.tag(GENERAL_SETTINGS_SYNC_TAG).d("applied_from_%s", reason)
            ApiResponse.Success(settings)
        } catch (e: Exception) {
            ApiResponse.Failure.Exception(e)
        }
    }

    private suspend fun readCurrentCachedGeneralSettings(): UserGeneralSettings {
        return accountLocalSettingsStore.currentGeneralSettings()
    }

    private suspend fun updateCurrentCachedGeneralSettings(value: UserGeneralSettings) {
        accountLocalSettingsStore.updateCurrentGeneralSettings(value)
    }

    private suspend fun fetchRemoteGeneralSettings(): ApiResponse<UserGeneralSettings> {
        val remoteRepository = accountService.getRemoteRepository()
            ?: return ApiResponse.Success(readCurrentCachedGeneralSettings())
        return when (val response = remoteRepository.getCurrentUserGeneralSettings()) {
            is ApiResponse.Success -> {
                updateCurrentCachedGeneralSettings(response.data)
                ApiResponse.Success(response.data)
            }
            is ApiResponse.Failure.Error -> response
            is ApiResponse.Failure.Exception -> response
        }
    }

    private companion object {
        private const val GENERAL_SETTINGS_REFRESH_MIN_INTERVAL_MILLIS = 60_000L
        private const val GENERAL_SETTINGS_SYNC_TAG = "GeneralSettingsSync"
    }
}
