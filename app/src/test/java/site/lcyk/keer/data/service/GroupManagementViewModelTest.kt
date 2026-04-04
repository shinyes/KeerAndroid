package site.lcyk.keer.data.service

import android.content.Context
import com.skydoves.sandwich.ApiResponse
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test
import site.lcyk.keer.data.model.PendingGroupOperation
import site.lcyk.keer.data.model.PendingGroupOperationType
import site.lcyk.keer.data.model.SyncDomain
import site.lcyk.keer.viewmodel.GroupManagementViewModel

class GroupManagementViewModelTest {

    private val context = mockk<Context>(relaxed = true)
    private val accountService = mockk<AccountService>()
    private val accountLocalSettingsStore = mockk<AccountLocalSettingsStore>()
    private val offlineGroupStore = mockk<OfflineGroupStore>(relaxed = true)
    private val memoService = mockk<MemoService>()

    @Test
    fun `deleteOrLeaveGroup removes local draft group without enqueuing remote delete`() = runTest {
        coEvery { offlineGroupStore.getGroups("account") } returns emptyList()
        every { accountLocalSettingsStore.observeCurrentAccountKey() } returns flowOf("account")

        val viewModel = GroupManagementViewModel(
            context = context,
            accountService = accountService,
            accountLocalSettingsStore = accountLocalSettingsStore,
            offlineGroupStore = offlineGroupStore,
            memoService = memoService,
        )

        val deleted = viewModel.deleteOrLeaveGroup("local-group:draft")

        assertTrue(deleted)
        coVerify(exactly = 1) { offlineGroupStore.removeGroupReferences("account", "local-group:draft") }
        coVerify(exactly = 0) { offlineGroupStore.enqueuePendingGroupOperation(any(), any()) }
        coVerify(exactly = 0) { memoService.sync(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `deleteOrLeaveGroup keeps remote group data until sync confirms removal`() = runTest {
        coEvery { offlineGroupStore.getGroups("account") } returns emptyList()
        coEvery { offlineGroupStore.getPendingGroupOperations("account") } returns emptyList()
        every { accountLocalSettingsStore.observeCurrentAccountKey() } returns flowOf("account")
        coEvery { memoService.sync(any(), any(), any(), any(), any()) } returns ApiResponse.Success(Unit)

        val viewModel = GroupManagementViewModel(
            context = context,
            accountService = accountService,
            accountLocalSettingsStore = accountLocalSettingsStore,
            offlineGroupStore = offlineGroupStore,
            memoService = memoService,
        )

        val deleted = viewModel.deleteOrLeaveGroup("group-1")

        assertTrue(deleted)
        coVerify(exactly = 0) { offlineGroupStore.removeGroupReferences("account", "group-1") }
        coVerify(exactly = 1) {
            offlineGroupStore.enqueuePendingGroupOperation(
                "account",
                match<PendingGroupOperation> { operation ->
                    operation.type == PendingGroupOperationType.DELETE_OR_LEAVE &&
                        operation.groupId == "group-1"
                },
            )
        }
        coVerify(exactly = 1) {
            memoService.sync(
                force = true,
                trigger = any(),
                domains = setOf(SyncDomain.GROUPS),
                groupId = null,
                bypassCoalesce = false,
            )
        }
    }
}
