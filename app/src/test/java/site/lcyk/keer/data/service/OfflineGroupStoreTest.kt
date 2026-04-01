package site.lcyk.keer.data.service

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import site.lcyk.keer.data.local.KeerDatabase
import site.lcyk.keer.data.local.entity.OfflineGroupAliasEntity
import site.lcyk.keer.data.model.CachedMemoItem
import site.lcyk.keer.data.model.GroupMember
import site.lcyk.keer.data.model.MemoGroup
import site.lcyk.keer.data.model.PendingGroupMemo
import site.lcyk.keer.data.model.PendingGroupOperation
import site.lcyk.keer.data.model.PendingGroupOperationType

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class OfflineGroupStoreTest {

    private lateinit var context: Context
    private lateinit var database: KeerDatabase
    private lateinit var store: OfflineGroupStore

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, KeerDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        store = OfflineGroupStore(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `replaceLocalGroupId rewires only impacted records`() = runTest {
        val accountKey = "account-a"
        val localGroupId = "local-group"
        val remoteGroupId = "remote-group"
        val otherGroupId = "other-group"

        store.upsertGroup(accountKey, buildGroup(localGroupId, "Local draft"))
        store.upsertGroup(accountKey, buildGroup(remoteGroupId, "Remote stale"))
        store.upsertGroup(accountKey, buildGroup(otherGroupId, "Other group"))

        store.enqueuePendingGroupOperation(
            accountKey,
            PendingGroupOperation(
                operationId = "op-local",
                type = PendingGroupOperationType.UPDATE,
                groupId = localGroupId,
                name = "Rename local",
            )
        )
        store.enqueuePendingGroupOperation(
            accountKey,
            PendingGroupOperation(
                operationId = "op-other",
                type = PendingGroupOperationType.UPDATE,
                groupId = otherGroupId,
                name = "Keep other",
            )
        )
        store.upsertPendingGroupMemo(
            accountKey,
            buildPendingMemo(localGroupId, "memo-local", "local content")
        )
        store.upsertPendingGroupMemo(
            accountKey,
            buildPendingMemo(otherGroupId, "memo-other", "other content")
        )
        store.setPinnedGroupMemo(accountKey, localGroupId, "pin-local", pinned = true)
        store.setPinnedGroupMemo(accountKey, otherGroupId, "pin-other", pinned = true)
        store.replaceCachedGroupMemos(
            accountKey,
            localGroupId,
            listOf(buildCachedMemo("cached-local"))
        )
        store.replaceCachedGroupMemos(
            accountKey,
            otherGroupId,
            listOf(buildCachedMemo("cached-other"))
        )
        store.upsertCachedGroupTags(accountKey, localGroupId, listOf("draft", "local"))
        store.upsertCachedGroupTags(accountKey, otherGroupId, listOf("other"))
        database.offlineGroupDao().upsertGroupAliases(
            listOf(
                OfflineGroupAliasEntity(
                    accountKey = accountKey,
                    localId = "stale-local",
                    remoteId = remoteGroupId,
                    updatedAtEpochMillis = 1L,
                ),
                OfflineGroupAliasEntity(
                    accountKey = accountKey,
                    localId = "keep-local",
                    remoteId = "keep-remote",
                    updatedAtEpochMillis = 2L,
                ),
            )
        )

        val remoteGroup = buildGroup(
            groupId = remoteGroupId,
            name = "Remote canonical",
            members = listOf(
                GroupMember(userId = "user-1", userName = "User 1"),
                GroupMember(userId = "user-2", userName = "User 2"),
            ),
        )
        store.replaceLocalGroupId(accountKey, localGroupId, remoteGroup)

        val groups = store.getGroups(accountKey).associateBy { group -> group.id }
        assertEquals(setOf(remoteGroupId, otherGroupId), groups.keys)
        assertEquals("Remote canonical", groups.getValue(remoteGroupId).name)
        assertEquals(listOf("user-1", "user-2"), groups.getValue(remoteGroupId).members.map { member -> member.userId })

        val pendingOperations = store.getPendingGroupOperations(accountKey).associateBy { operation -> operation.operationId }
        assertEquals(remoteGroupId, pendingOperations.getValue("op-local").groupId)
        assertEquals(otherGroupId, pendingOperations.getValue("op-other").groupId)

        val pendingMemos = store.getPendingGroupMemos(accountKey).associateBy { memo -> memo.localId }
        assertEquals(remoteGroupId, pendingMemos.getValue("memo-local").groupId)
        assertEquals(otherGroupId, pendingMemos.getValue("memo-other").groupId)

        val pinnedKeys = store.getPinnedGroupMemoKeys(accountKey)
        assertTrue("$remoteGroupId|pin-local" in pinnedKeys)
        assertTrue("$otherGroupId|pin-other" in pinnedKeys)
        assertFalse("$localGroupId|pin-local" in pinnedKeys)

        val remoteCachedMemoIds = store.getCachedGroupMemos(accountKey, remoteGroupId).map { memo -> memo.remoteId }.toSet()
        val otherCachedMemoIds = store.getCachedGroupMemos(accountKey, otherGroupId).map { memo -> memo.remoteId }.toSet()
        assertEquals(setOf("cached-local"), remoteCachedMemoIds)
        assertEquals(setOf("cached-other"), otherCachedMemoIds)

        assertEquals(listOf("draft", "local"), store.getCachedGroupTags(accountKey, remoteGroupId))
        assertEquals(listOf("other"), store.getCachedGroupTags(accountKey, otherGroupId))

        val aliases = store.getGroupAliases(accountKey).associateBy { alias -> alias.localId }
        assertEquals(remoteGroupId, aliases.getValue(localGroupId).remoteId)
        assertEquals("keep-remote", aliases.getValue("keep-local").remoteId)
        assertFalse(aliases.containsKey("stale-local"))
    }

    @Test
    fun `removeGroupReferences deletes linked records without touching unrelated groups`() = runTest {
        val accountKey = "account-b"
        val localGroupId = "local-group"
        val remoteGroupId = "remote-group"
        val otherGroupId = "other-group"

        store.upsertGroup(accountKey, buildGroup(localGroupId, "Local"))
        store.upsertGroup(accountKey, buildGroup(remoteGroupId, "Remote"))
        store.upsertGroup(accountKey, buildGroup(otherGroupId, "Other"))
        database.offlineGroupDao().upsertGroupAliases(
            listOf(
                OfflineGroupAliasEntity(
                    accountKey = accountKey,
                    localId = localGroupId,
                    remoteId = remoteGroupId,
                    updatedAtEpochMillis = 1L,
                ),
                OfflineGroupAliasEntity(
                    accountKey = accountKey,
                    localId = "keep-local",
                    remoteId = "keep-remote",
                    updatedAtEpochMillis = 2L,
                ),
            )
        )

        listOf(localGroupId, remoteGroupId, otherGroupId).forEachIndexed { index, groupId ->
            store.enqueuePendingGroupOperation(
                accountKey,
                PendingGroupOperation(
                    operationId = "op-$groupId",
                    type = PendingGroupOperationType.UPDATE,
                    groupId = groupId,
                    name = "group-$index",
                )
            )
            store.upsertPendingGroupMemo(
                accountKey,
                buildPendingMemo(groupId, "memo-$groupId", "content-$groupId")
            )
            store.setPinnedGroupMemo(accountKey, groupId, "pin-$groupId", pinned = true)
            store.replaceCachedGroupMemos(
                accountKey,
                groupId,
                listOf(buildCachedMemo("cached-$groupId"))
            )
            store.upsertCachedGroupTags(accountKey, groupId, listOf("tag-$groupId"))
        }

        store.removeGroupReferences(accountKey, localGroupId)

        assertEquals(setOf(otherGroupId), store.getGroups(accountKey).map { group -> group.id }.toSet())
        assertEquals(setOf(otherGroupId), store.getPendingGroupOperations(accountKey).map { operation -> operation.groupId }.toSet())
        assertEquals(setOf(otherGroupId), store.getPendingGroupMemos(accountKey).map { memo -> memo.groupId }.toSet())
        assertEquals(setOf("$otherGroupId|pin-$otherGroupId"), store.getPinnedGroupMemoKeys(accountKey))
        assertEquals(setOf("cached-$otherGroupId"), store.getCachedGroupMemos(accountKey, otherGroupId).map { memo -> memo.remoteId }.toSet())
        assertEquals(listOf("tag-$otherGroupId"), store.getCachedGroupTags(accountKey, otherGroupId))

        val aliases = store.getGroupAliases(accountKey).associateBy { alias -> alias.localId }
        assertEquals(setOf("keep-local"), aliases.keys)
        assertEquals("keep-remote", aliases.getValue("keep-local").remoteId)
    }

    private fun buildGroup(
        groupId: String,
        name: String,
        members: List<GroupMember> = listOf(GroupMember(userId = "owner", userName = "Owner")),
    ): MemoGroup {
        return MemoGroup(
            id = groupId,
            name = name,
            description = "$name description",
            creatorId = "owner",
            creatorName = "Owner",
            members = members,
            createdAtEpochMillis = 100L,
            updatedAtEpochMillis = 200L,
        )
    }

    private fun buildPendingMemo(groupId: String, localId: String, content: String): PendingGroupMemo {
        return PendingGroupMemo(
            localId = localId,
            groupId = groupId,
            content = content,
            creatorId = "owner",
            creatorName = "Owner",
            createdAtEpochMillis = 123L,
        )
    }

    private fun buildCachedMemo(remoteId: String): CachedMemoItem {
        return CachedMemoItem(
            remoteId = remoteId,
            content = "cached-$remoteId",
            dateEpochMillis = 111L,
            updatedAtEpochMillis = 222L,
        )
    }
}
