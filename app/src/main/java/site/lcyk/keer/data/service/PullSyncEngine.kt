package site.lcyk.keer.data.service

import com.skydoves.sandwich.ApiResponse
import javax.inject.Inject
import javax.inject.Singleton
import site.lcyk.keer.data.model.SyncDomain
import site.lcyk.keer.data.repository.UserGeneralSettingsRepository

@Singleton
class PullSyncEngine @Inject constructor(
    private val accountService: AccountService,
    private val userGeneralSettingsRepository: UserGeneralSettingsRepository,
    private val groupsSyncRunner: GroupsSyncRunner,
) {

    private val executionOrder = listOf(
        SyncDomain.PROFILE,
        SyncDomain.USERS,
        SyncDomain.GROUPS,
        SyncDomain.MEMOS,
    )

    suspend fun run(
        domains: Set<SyncDomain>,
        groupId: String?,
    ): ApiResponse<Unit> {
        for (domain in executionOrder) {
            if (domain !in domains) {
                continue
            }
            val result = when (domain) {
                SyncDomain.PROFILE -> syncProfile()
                SyncDomain.USERS -> syncUsers()
                SyncDomain.GROUPS -> groupsSyncRunner.sync(groupId)
                SyncDomain.MEMOS -> syncMemos()
            }
            if (result !is ApiResponse.Success) {
                return result
            }
        }
        return ApiResponse.Success(Unit)
    }

    private suspend fun syncProfile(): ApiResponse<Unit> {
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

    private suspend fun syncUsers(): ApiResponse<Unit> {
        val remoteRepository = accountService.getRemoteRepository()
            ?: return ApiResponse.Success(Unit)
        return remoteRepository.syncKnownUsers()
    }

    private suspend fun syncMemos(): ApiResponse<Unit> {
        return accountService.getRepository().sync()
    }
}
