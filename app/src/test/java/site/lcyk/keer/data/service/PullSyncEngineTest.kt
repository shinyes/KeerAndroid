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
import site.lcyk.keer.data.service.AccountLocalSettingsStore
import site.lcyk.keer.data.repository.UserGeneralSettingsRepository

@OptIn(ExperimentalCoroutinesApi::class)
class PullSyncEngineTest {

    private val accountService = mockk<AccountService>()
    private val accountLocalSettingsStore = mockk<AccountLocalSettingsStore>()
    private val userGeneralSettingsRepository = mockk<UserGeneralSettingsRepository>()
    private val groupsSyncRunner = mockk<GroupsSyncRunner>()

    private val engine = PullSyncEngine(
        accountService = accountService,
        accountLocalSettingsStore = accountLocalSettingsStore,
        userGeneralSettingsRepository = userGeneralSettingsRepository,
        groupsSyncRunner = groupsSyncRunner,
    )

    @Test
    fun run_profileDomain_manual_runsAvatarSyncBeforeStreamSession() = runTest {
        coEvery { accountService.syncPendingAvatarIfNeeded() } returns ApiResponse.Success(Unit)
        coEvery { accountService.getRemoteRepository() } returns null

        val result = engine.run(
            domains = setOf(SyncDomain.PROFILE),
            groupId = null,
            trigger = SyncTrigger.MANUAL,
        )

        assertTrue(result is ApiResponse.Success)
        coVerify(exactly = 1) { accountService.syncPendingAvatarIfNeeded() }
    }

    @Test
    fun run_profileDomain_foreground_runsAvatarSyncBeforeStreamSession() = runTest {
        coEvery { accountService.syncPendingAvatarIfNeeded() } returns ApiResponse.Success(Unit)
        coEvery { accountService.getRemoteRepository() } returns null

        val result = engine.run(
            domains = setOf(SyncDomain.PROFILE),
            groupId = null,
            trigger = SyncTrigger.APP_FOREGROUND,
        )

        assertTrue(result is ApiResponse.Success)
        coVerify(exactly = 1) { accountService.syncPendingAvatarIfNeeded() }
    }
}
