package site.lcyk.keer.data.service

import com.skydoves.sandwich.ApiResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
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
        trigger: SyncTrigger = SyncTrigger.AUTO,
    ): ApiResponse<Unit> {
        return withContext(Dispatchers.IO) {
            // Separate independent domains that can run in parallel
            val profileAndUsers = domains.filter { 
                it == SyncDomain.PROFILE || it == SyncDomain.USERS 
            }
            val groups = domains.filter { it == SyncDomain.GROUPS }
            val memos = domains.filter { it == SyncDomain.MEMOS }

            // Parallel execution for independent domains (PROFILE + USERS)
            if (profileAndUsers.isNotEmpty()) {
                val results = coroutineScope {
                    val profileJob = if (SyncDomain.PROFILE in profileAndUsers) {
                        async { syncProfile(trigger) }
                    } else null
                    
                    val usersJob = if (SyncDomain.USERS in profileAndUsers) {
                        async { syncUsers() }
                    } else null

                    listOfNotNull(profileJob, usersJob).map { it.await() }
                }

                // Check for any failures
                results.firstOrNull { it !is ApiResponse.Success }?.let { error ->
                    return@withContext error
                }
            }

            // Groups sync (independent, but kept sequential for now)
            for (domain in groups) {
                val result = groupsSyncRunner.sync(groupId)
                if (result !is ApiResponse.Success) {
                    return@withContext result
                }
            }

            // Memos sync (must run after users sync due to user references)
            if (SyncDomain.MEMOS in domains) {
                val result = syncMemos()
                if (result !is ApiResponse.Success) {
                    return@withContext result
                }
            }

            return@withContext ApiResponse.Success(Unit)
        }
    }

    private suspend fun syncProfile(trigger: SyncTrigger): ApiResponse<Unit> {
        val forceGeneralSettingsRefresh = trigger == SyncTrigger.AUTH_BOOTSTRAP || trigger == SyncTrigger.MANUAL
        return coroutineScope {
            // Parallel execution of independent operations
            val avatarJob = async { accountService.syncPendingAvatarIfNeeded() }
            val settingsJob = async {
                userGeneralSettingsRepository.refreshCurrentGeneralSettings(
                    forceNetwork = forceGeneralSettingsRefresh,
                    reason = "profile_sync:$trigger",
                )
            }

            val avatarResult = avatarJob.await()
            val settingsResult = settingsJob.await()

            // Check for failures
            when (avatarResult) {
                is ApiResponse.Failure.Error -> return@coroutineScope avatarResult
                is ApiResponse.Failure.Exception -> return@coroutineScope avatarResult
                is ApiResponse.Success -> {} // Continue
            }
            
            when (settingsResult) {
                is ApiResponse.Failure.Error -> return@coroutineScope settingsResult
                is ApiResponse.Failure.Exception -> return@coroutineScope settingsResult
                is ApiResponse.Success -> {} // Continue
            }

            // Successfully completed both, return Unit
            ApiResponse.Success(Unit)
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
