package site.lcyk.keer.data.service

import com.skydoves.sandwich.ApiResponse
import site.lcyk.keer.data.repository.UserGeneralSettingsRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProfileSyncRunner @Inject constructor(
    private val accountService: AccountService,
    private val userGeneralSettingsRepository: UserGeneralSettingsRepository,
) {
    suspend fun sync(): ApiResponse<Unit> {
        val avatarSync = accountService.syncPendingAvatarIfNeeded()
        if (avatarSync !is ApiResponse.Success) {
            return avatarSync
        }
        return when (val settingsSync = userGeneralSettingsRepository.refreshCurrentGeneralSettings()) {
            is ApiResponse.Success -> ApiResponse.Success(Unit)
            is ApiResponse.Failure.Error -> settingsSync
            is ApiResponse.Failure.Exception -> settingsSync
        }
    }
}
