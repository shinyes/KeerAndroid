package site.lcyk.keer.data.repository

import com.skydoves.sandwich.ApiResponse
import site.lcyk.keer.data.api.KeerV2GroupKeyVersion
import site.lcyk.keer.data.api.KeerV2UserEncryptionSetting
import site.lcyk.keer.data.model.Memo
import site.lcyk.keer.data.model.MemoGroup
import site.lcyk.keer.data.model.MemoVisibility
import site.lcyk.keer.data.model.Resource
import site.lcyk.keer.data.model.StorageCleanupSummary
import site.lcyk.keer.data.model.User
import site.lcyk.keer.data.model.UserGeneralSettings
import okhttp3.MediaType
import java.io.File
import java.time.Instant

data class ResourceUploadThumbnail(
    val filename: String,
    val type: String,
    val content: String
)

data class MemoChanges(
    val memos: List<Memo>,
    val deletedMemoRemoteIds: List<String>,
    val syncAnchor: Instant
)

enum class SyncPullDomain {
    MEMOS,
    USERS,
    FRIENDSHIPS,
    GROUPS,
    GROUP_MESSAGES,
    ATTACHMENTS,
    SETTINGS,
    SETTINGS_ENCRYPTION,
    GROUP_KEYS,
}

enum class SyncStreamMode {
    BOOTSTRAP,
    TAIL,
}

data class SyncPullResult(
    val nextCursor: String,
    val hasMore: Boolean,
    val patches: SyncPullPatches,
)

data class SyncPullPatches(
    val memos: SyncPullMemoPatch = SyncPullMemoPatch(),
    val users: SyncPullUserPatch = SyncPullUserPatch(),
    val friendships: SyncPullFriendshipsPatch = SyncPullFriendshipsPatch(),
    val groups: SyncPullGroupPatch = SyncPullGroupPatch(),
    val groupMessages: SyncPullGroupMessagesPatch = SyncPullGroupMessagesPatch(),
    val attachments: SyncPullAttachmentsPatch = SyncPullAttachmentsPatch(),
    val settings: SyncPullSettingsPatch = SyncPullSettingsPatch(),
    val groupKeys: SyncPullGroupKeysPatch = SyncPullGroupKeysPatch(),
)

data class SyncPullMemoPatch(
    val upserts: List<Memo> = emptyList(),
    val deletes: List<String> = emptyList(),
)

data class SyncPullUserPatch(
    val upserts: List<User> = emptyList(),
)

data class SyncPullFriendshipsPatch(
    val upserts: List<User> = emptyList(),
    val deletes: List<String> = emptyList(),
)

data class SyncPullGroupPatch(
    val upserts: List<MemoGroup> = emptyList(),
    val deletes: List<String> = emptyList(),
)

data class SyncPullGroupMessagesPatch(
    val groups: List<SyncPullGroupMessagesGroupPatch> = emptyList(),
)

data class SyncPullGroupMessagesGroupPatch(
    val groupId: String,
    val hasUnread: Boolean,
    val upserts: List<Memo>,
    val deletes: List<String>,
    val tags: List<String>,
)

data class SyncPullAttachmentsPatch(
    val upserts: List<Resource> = emptyList(),
    val deletes: List<String> = emptyList(),
)

data class SyncPullSettingsPatch(
    val generalSettings: UserGeneralSettings? = null,
    val encryptionSetting: KeerV2UserEncryptionSetting? = null,
)

data class SyncPullGroupKeysPatch(
    val upserts: List<KeerV2GroupKeyVersion> = emptyList(),
    val deletes: List<String> = emptyList(),
)

abstract class RemoteRepository {
    abstract suspend fun streamSyncBootstrap(
        resumeCursor: String,
        domains: Set<SyncPullDomain>,
        groupScopes: List<String> = emptyList(),
        mode: SyncStreamMode = SyncStreamMode.BOOTSTRAP,
        limit: Int = 200,
        onChunk: suspend (SyncPullResult) -> ApiResponse<Unit>,
    ): ApiResponse<String>

    abstract suspend fun listMemos(): ApiResponse<List<Memo>>
    abstract suspend fun listArchivedMemos(): ApiResponse<List<Memo>>
    abstract suspend fun listMemoChanges(since: Instant): ApiResponse<MemoChanges>

    abstract suspend fun createMemo(
        content: String,
        visibility: MemoVisibility,
        resourceRemoteIds: List<String>,
        tags: List<String>? = null,
        createdAt: Instant? = null,
        latitude: Double? = null,
        longitude: Double? = null
    ): ApiResponse<Memo>

    abstract suspend fun updateMemo(
        remoteId: String,
        content: String? = null,
        resourceRemoteIds: List<String>? = null,
        visibility: MemoVisibility? = null,
        tags: List<String>? = null,
        pinned: Boolean? = null,
        archived: Boolean? = null
    ): ApiResponse<Memo>

    abstract suspend fun deleteMemo(remoteId: String): ApiResponse<Unit>
    abstract suspend fun listFriends(): ApiResponse<List<User>>
    abstract suspend fun addFriend(userIdentifier: String): ApiResponse<User>
    abstract suspend fun removeFriend(userIdentifier: String): ApiResponse<Unit>
    abstract suspend fun changePassword(
        currentPassword: String,
        newPassword: String
    ): ApiResponse<Unit>
    abstract suspend fun getCurrentUserGeneralSettings(): ApiResponse<UserGeneralSettings>
    abstract suspend fun updateCurrentUserGeneralSettings(
        settings: UserGeneralSettings
    ): ApiResponse<UserGeneralSettings>
    abstract suspend fun cleanupOrphanFiles(): ApiResponse<StorageCleanupSummary>

    abstract suspend fun listGroups(): ApiResponse<List<MemoGroup>>
    abstract suspend fun createGroup(name: String, description: String): ApiResponse<MemoGroup>
    abstract suspend fun createDirectGroup(userIdentifier: String): ApiResponse<MemoGroup>
    abstract suspend fun addGroupMember(groupId: String, userIdentifier: String): ApiResponse<MemoGroup>
    abstract suspend fun updateGroup(
        groupId: String,
        name: String? = null,
        description: String? = null
    ): ApiResponse<MemoGroup>

    abstract suspend fun deleteOrLeaveGroup(groupId: String): ApiResponse<Unit>
    abstract suspend fun listGroupMessages(
        groupId: String,
        pageSize: Int,
        pageToken: String? = null
    ): ApiResponse<Pair<List<Memo>, String?>>

    abstract suspend fun createGroupMessage(
        groupId: String,
        content: String,
        tags: List<String> = emptyList(),
        resourceRemoteIds: List<String> = emptyList()
    ): ApiResponse<Memo>

    abstract suspend fun updateGroupMessage(
        groupId: String,
        messageRemoteId: String,
        content: String? = null,
        tags: List<String>? = null,
        resourceRemoteIds: List<String>? = null
    ): ApiResponse<Memo>

    abstract suspend fun deleteGroupMessage(
        groupId: String,
        messageRemoteId: String
    ): ApiResponse<Unit>

    abstract suspend fun markGroupRead(
        groupId: String,
        lastReadMessageRemoteId: String? = null
    ): ApiResponse<Unit>

    abstract suspend fun listGroupTags(groupId: String): ApiResponse<List<String>>
    abstract suspend fun addGroupTag(groupId: String, tag: String): ApiResponse<List<String>>

    abstract suspend fun listTags(): ApiResponse<List<String>>
    abstract suspend fun listResources(): ApiResponse<List<Resource>>

    abstract suspend fun createResource(
        filename: String,
        type: MediaType?,
        file: File,
        memoRemoteId: String? = null,
        encryptionScope: ResourceEncryptionScope = ResourceEncryptionScope.Account,
        thumbnail: ResourceUploadThumbnail? = null,
        onProgress: (uploadedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> }
    ): ApiResponse<Resource>

    abstract suspend fun updateResourceThumbnail(
        remoteId: String,
        thumbnailFile: File,
        encryptionMetadata: String?
    ): ApiResponse<Resource>

    abstract suspend fun deleteResource(remoteId: String): ApiResponse<Unit>
    abstract suspend fun getCurrentUser(): ApiResponse<User>

    open suspend fun applySecuritySyncPatch(
        settingsPatch: SyncPullSettingsPatch,
        groupKeysPatch: SyncPullGroupKeysPatch,
    ): ApiResponse<Unit> {
        return ApiResponse.Success(Unit)
    }

    open suspend fun syncKnownUsers(): ApiResponse<Unit> {
        return ApiResponse.Success(Unit)
    }
}
