package site.lcyk.keer.data.repository

import com.skydoves.sandwich.ApiResponse
import kotlinx.coroutines.flow.Flow
import site.lcyk.keer.R
import site.lcyk.keer.data.model.ExploreDrawerEntryConfig
import site.lcyk.keer.data.model.UserGeneralSettings
import site.lcyk.keer.data.model.withRenamedTagDrawerEntries
import site.lcyk.keer.data.model.withTagDrawerVisibility
import site.lcyk.keer.data.model.withoutTagDrawerEntries
import site.lcyk.keer.data.service.AccountLocalSettingsStore
import site.lcyk.keer.data.service.AccountService
import site.lcyk.keer.ext.string
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UserGeneralSettingsRepository @Inject constructor(
    private val accountService: AccountService,
    private val accountLocalSettingsStore: AccountLocalSettingsStore,
) {
    fun observeCurrentGeneralSettings(): Flow<UserGeneralSettings> {
        return accountLocalSettingsStore.observeCurrentGeneralSettings()
    }

    suspend fun refreshCurrentGeneralSettings(): ApiResponse<UserGeneralSettings> {
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
        return when (val response = remoteRepository.updateCurrentUserGeneralSettings(settings)) {
            is ApiResponse.Success -> {
                updateCurrentCachedGeneralSettings(response.data)
                ApiResponse.Success(response.data)
            }
            is ApiResponse.Failure.Error -> response
            is ApiResponse.Failure.Exception -> response
        }
    }

    private suspend fun readCurrentCachedGeneralSettings(): UserGeneralSettings {
        return accountLocalSettingsStore.currentGeneralSettings()
    }

    private suspend fun updateCurrentCachedGeneralSettings(value: UserGeneralSettings) {
        accountLocalSettingsStore.updateCurrentGeneralSettings(value)
    }
}
