package site.lcyk.keer.data.service

import com.skydoves.sandwich.ApiResponse
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test
import site.lcyk.keer.data.model.Account
import site.lcyk.keer.data.model.PendingGroupOperation
import site.lcyk.keer.data.model.PendingGroupOperationType
import site.lcyk.keer.data.repository.RemoteRepository

class GroupsSyncRunnerTest {

    private val accountService = mockk<AccountService>()
    private val accountLocalSettingsStore = mockk<AccountLocalSettingsStore>()
    private val offlineGroupStore = mockk<OfflineGroupStore>(relaxed = true)
    private val remoteRepository = mockk<RemoteRepository>()

    private val runner = GroupsSyncRunner(
        accountService = accountService,
        accountLocalSettingsStore = accountLocalSettingsStore,
        offlineGroupStore = offlineGroupStore,
    )

    @Test
    fun `sync discards stale local delete operation without hitting remote`() = runTest {
        everyCurrentAccount()
        coEvery { accountService.getRemoteRepository() } returns remoteRepository
        coEvery { offlineGroupStore.getPendingGroupOperations("local") } returnsMany listOf(
            listOf(
                PendingGroupOperation(
                    operationId = "delete-local",
                    type = PendingGroupOperationType.DELETE_OR_LEAVE,
                    groupId = "local-group:123",
                )
            ),
            emptyList(),
        )
        coEvery { offlineGroupStore.getPendingGroupMemos("local", null) } returns emptyList()
        coEvery { accountLocalSettingsStore.readGroupSyncCursor("local") } returns null
        coEvery {
            remoteRepository.streamSyncBootstrap(any(), any(), any(), any(), any(), any())
        } returns ApiResponse.Success("0")

        val result = runner.sync()

        assertTrue(result is ApiResponse.Success)
        coVerify(exactly = 0) { remoteRepository.deleteOrLeaveGroup(any()) }
        coVerify(exactly = 1) { offlineGroupStore.removeGroupReferences("local", "local-group:123") }
        coVerify(exactly = 1) { offlineGroupStore.removePendingGroupOperation("local", "delete-local") }
    }

    @Test
    fun `sync pushes pending group tag additions to remote`() = runTest {
        everyCurrentAccount()
        coEvery { accountService.getRemoteRepository() } returns remoteRepository
        coEvery { offlineGroupStore.getPendingGroupOperations("local") } returnsMany listOf(
            listOf(
                PendingGroupOperation(
                    operationId = "tag-op",
                    type = PendingGroupOperationType.ADD_TAG,
                    groupId = "group-1",
                    tag = "new-tag",
                )
            ),
            emptyList(),
        )
        coEvery { offlineGroupStore.getPendingGroupMemos("local", null) } returns emptyList()
        coEvery { accountLocalSettingsStore.readGroupSyncCursor("local") } returns null
        coEvery { remoteRepository.addGroupTag("group-1", "new-tag") } returns ApiResponse.Success(
            listOf("existing", "new-tag")
        )
        coEvery {
            remoteRepository.streamSyncBootstrap(any(), any(), any(), any(), any(), any())
        } returns ApiResponse.Success("0")

        val result = runner.sync()

        assertTrue(result is ApiResponse.Success)
        coVerify(exactly = 1) { remoteRepository.addGroupTag("group-1", "new-tag") }
        coVerify(exactly = 1) {
            offlineGroupStore.upsertCachedGroupTags("local", "group-1", listOf("existing", "new-tag"))
        }
        coVerify(exactly = 1) { offlineGroupStore.removePendingGroupOperation("local", "tag-op") }
    }

    private fun everyCurrentAccount() {
        every { accountService.currentAccount } returns MutableStateFlow(Account.Local())
    }
}
