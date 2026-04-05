package site.lcyk.keer.data.service

import com.skydoves.sandwich.ApiResponse
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import site.lcyk.keer.data.model.Account
import site.lcyk.keer.data.model.SyncDomain
import site.lcyk.keer.data.model.SyncStatus
import site.lcyk.keer.data.repository.SyncingRepository

@OptIn(ExperimentalCoroutinesApi::class)
class SyncCoordinatorTest {

    private lateinit var accountService: AccountService
    private lateinit var pullSyncEngine: PullSyncEngine
    private lateinit var pendingSyncWorkInspector: PendingSyncWorkInspector
    private lateinit var coordinator: SyncCoordinator

    @Before
    fun setup() {
        accountService = mockk()
        pullSyncEngine = mockk()
        pendingSyncWorkInspector = mockk()
        coEvery { pendingSyncWorkInspector.hasPendingWork(any()) } returns false

        val mockRepository = mockk<SyncingRepository>()
        every { accountService.currentAccount } returns MutableStateFlow(mockk<Account>())
        coEvery { accountService.getRepository() } returns mockRepository
        every { mockRepository.syncStatus } returns MutableStateFlow(SyncStatus())
        coEvery { pullSyncEngine.run(any(), any(), any()) } returns ApiResponse.Success(Unit)

        coordinator = SyncCoordinator(
            accountService = accountService,
            pullSyncEngine = pullSyncEngine,
            pendingSyncWorkInspector = pendingSyncWorkInspector,
        )
    }

    @Test
    fun `sync with empty domains returns success immediately`() = runTest {
        val result = coordinator.sync(force = false, domains = emptySet())
        assertTrue(result is ApiResponse.Success)
        coVerify(exactly = 0) { pullSyncEngine.run(any(), any(), any()) }
    }

    @Test
    fun `sync skips second immediate auto attempt`() = runTest {
        val first = coordinator.sync(force = false, domains = setOf(SyncDomain.MEMOS))
        val second = coordinator.sync(force = false, domains = setOf(SyncDomain.MEMOS))
        assertTrue(first is ApiResponse.Success)
        assertTrue(second is ApiResponse.Success)
        coVerify(exactly = 1) { pullSyncEngine.run(any(), any(), any()) }
    }

    @Test
    fun `requestSync accepts auto trigger`() = runTest {
        coordinator.requestSync(
            trigger = SyncTrigger.AUTO,
            force = false,
            domains = setOf(SyncDomain.MEMOS),
        )
        advanceUntilIdle()
        coVerify(exactly = 1) {
            pullSyncEngine.run(setOf(SyncDomain.MEMOS), null, SyncTrigger.AUTO)
        }
    }

    @Test
    fun `requestSync accepts manual trigger`() = runTest {
        coordinator.requestSync(
            trigger = SyncTrigger.MANUAL,
            force = false,
            domains = setOf(SyncDomain.MEMOS),
        )
        advanceUntilIdle()
        coVerify(exactly = 1) { pullSyncEngine.run(any(), any(), any()) }
    }

    @Test
    fun `requestSync merges pending requests by priority and domains`() = runTest {
        coordinator.requestSync(
            trigger = SyncTrigger.AUTO,
            force = false,
            domains = setOf(SyncDomain.MEMOS),
        )
        coordinator.requestSync(
            trigger = SyncTrigger.MANUAL,
            force = true,
            domains = setOf(SyncDomain.GROUPS),
            groupId = "group-1",
            bypassCoalesce = true,
        )

        advanceUntilIdle()

        coVerify(timeout = 2_000, atLeast = 1) {
            pullSyncEngine.run(
                withArg { domains -> assertTrue(SyncDomain.GROUPS in domains) },
                "group-1",
                SyncTrigger.MANUAL,
            )
        }
    }

    @Test
    fun `foreground sync preempts tail session`() = runTest {
        val tailStarted = CompletableDeferred<Unit>()
        coEvery { pullSyncEngine.runUnifiedTailSession() } coAnswers {
            tailStarted.complete(Unit)
            awaitCancellation()
        }

        val tailJob = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            coordinator.runTailSessionLoop()
        }

        tailStarted.await()
        coordinator.sync(
            force = true,
            trigger = SyncTrigger.MANUAL,
            domains = setOf(SyncDomain.MEMOS),
        )
        advanceUntilIdle()

        coVerify(atLeast = 1) { pullSyncEngine.runUnifiedTailSession() }
        coVerify(atLeast = 1) {
            pullSyncEngine.run(setOf(SyncDomain.MEMOS), null, SyncTrigger.MANUAL)
        }

        tailJob.cancel()
    }

    @Test
    fun `sync updates status correctly`() = runTest {
        coordinator.sync(force = true, domains = setOf(SyncDomain.MEMOS))
        advanceUntilIdle()
        assertFalse(coordinator.syncStatus.value.syncing)
    }

    @Test
    fun `consecutive failures enter backoff window`() = runTest {
        val error = ApiResponse.Failure.Exception(RuntimeException("Network error"))
        coEvery { pullSyncEngine.run(any(), any(), any()) } returns error

        repeat(3) {
            coordinator.sync(force = false, domains = setOf(SyncDomain.MEMOS))
        }

        coVerify(exactly = 1) { pullSyncEngine.run(any(), any(), any()) }
    }
}
