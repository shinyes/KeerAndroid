package site.lcyk.keer.data.service

import com.skydoves.sandwich.ApiResponse
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test
import site.lcyk.keer.data.model.SyncDomain
import site.lcyk.keer.data.model.UserGeneralSettings
import site.lcyk.keer.data.repository.UserGeneralSettingsRepository

@OptIn(ExperimentalCoroutinesApi::class)
class PullSyncEngineTest {

    private val accountService = mockk<AccountService>()
    private val userGeneralSettingsRepository = mockk<UserGeneralSettingsRepository>()
    private val groupsSyncRunner = mockk<GroupsSyncRunner>()

    private val engine = PullSyncEngine(
        accountService = accountService,
        userGeneralSettingsRepository = userGeneralSettingsRepository,
        groupsSyncRunner = groupsSyncRunner,
    )

    @Test
    fun run_profileDomain_forceRefreshesSettingsOnAuthBootstrap() = runTest {
        coEvery { accountService.syncPendingAvatarIfNeeded() } returns ApiResponse.Success(Unit)
        coEvery {
            userGeneralSettingsRepository.refreshCurrentGeneralSettings(any(), any())
        } returns ApiResponse.Success(UserGeneralSettings())

        val result = engine.run(
            domains = setOf(SyncDomain.PROFILE),
            groupId = null,
            trigger = SyncTrigger.AUTH_BOOTSTRAP,
        )

        assertTrue(result is ApiResponse.Success)
        coVerify {
            userGeneralSettingsRepository.refreshCurrentGeneralSettings(
                forceNetwork = true,
                reason = "profile_sync:${SyncTrigger.AUTH_BOOTSTRAP}",
            )
        }
    }

    @Test
    fun run_profileDomain_usesThrottledSettingsRefreshOnForegroundTrigger() = runTest {
        coEvery { accountService.syncPendingAvatarIfNeeded() } returns ApiResponse.Success(Unit)
        coEvery {
            userGeneralSettingsRepository.refreshCurrentGeneralSettings(any(), any())
        } returns ApiResponse.Success(UserGeneralSettings())

        val result = engine.run(
            domains = setOf(SyncDomain.PROFILE),
            groupId = null,
            trigger = SyncTrigger.APP_FOREGROUND,
        )

        assertTrue(result is ApiResponse.Success)
        coVerify {
            userGeneralSettingsRepository.refreshCurrentGeneralSettings(
                forceNetwork = false,
                reason = "profile_sync:${SyncTrigger.APP_FOREGROUND}",
            )
        }
    }
}

