package site.lcyk.keer.data.api

import android.net.Uri
import androidx.core.net.toUri
import com.skydoves.sandwich.ApiResponse
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import java.time.Instant

interface KeerV2Api {
    @GET("api/v1/auth/me")
    suspend fun getCurrentUser(): ApiResponse<GetCurrentUserResponse>

    @GET("api/v1/users/{id}/settings/GENERAL")
    suspend fun getUserSetting(@Path("id") userId: String): ApiResponse<KeerV2UserSetting>

    @GET("api/v1/memos")
    suspend fun listMemos(
        @Query("pageSize") pageSize: Int,
        @Query("pageToken") pageToken: String? = null,
        @Query("state") state: KeerV2State? = null,
        @Query("filter") filter: String? = null,
    ): ApiResponse<ListMemosResponse>

    @GET("api/v1/memos/changes")
    suspend fun listMemoChanges(
        @Query("since") since: String,
        @Query("state") state: KeerV2State? = null,
        @Query("filter") filter: String? = null,
    ): ApiResponse<ListMemoChangesResponse>

    @POST("api/v1/memos")
    suspend fun createMemo(@Body body: KeerV2CreateMemoRequest): ApiResponse<KeerV2Memo>

    @PATCH("api/v1/memos/{id}")
    suspend fun updateMemo(@Path("id") memoId: String, @Body body: UpdateMemoRequest): ApiResponse<KeerV2Memo>

    @DELETE("api/v1/memos/{id}")
    suspend fun deleteMemo(@Path("id") memoId: String): ApiResponse<Unit>

    @GET("api/v1/groups")
    suspend fun listGroups(): ApiResponse<ListGroupsResponse>

    @POST("api/v1/groups")
    suspend fun createGroup(@Body body: CreateGroupRequest): ApiResponse<KeerV2Group>

    @POST("api/v1/groups/{id}/join")
    suspend fun joinGroup(@Path("id") groupId: String): ApiResponse<KeerV2Group>

    @PATCH("api/v1/groups/{id}")
    suspend fun updateGroup(@Path("id") groupId: String, @Body body: UpdateGroupRequest): ApiResponse<KeerV2Group>

    @DELETE("api/v1/groups/{id}")
    suspend fun deleteOrLeaveGroup(@Path("id") groupId: String): ApiResponse<Unit>

    @GET("api/v1/groups/{id}/messages")
    suspend fun listGroupMessages(
        @Path("id") groupId: String,
        @Query("pageSize") pageSize: Int,
        @Query("pageToken") pageToken: String? = null
    ): ApiResponse<ListGroupMessagesResponse>

    @POST("api/v1/groups/{id}/messages")
    suspend fun createGroupMessage(
        @Path("id") groupId: String,
        @Body body: CreateGroupMessageRequest
    ): ApiResponse<KeerV2GroupMessage>

    @GET("api/v1/groups/{id}/tags")
    suspend fun listGroupTags(@Path("id") groupId: String): ApiResponse<ListGroupTagsResponse>

    @POST("api/v1/groups/{id}/tags")
    suspend fun addGroupTag(
        @Path("id") groupId: String,
        @Body body: AddGroupTagRequest
    ): ApiResponse<ListGroupTagsResponse>

    @GET("api/v1/attachments")
    suspend fun listResources(): ApiResponse<ListResourceResponse>

    @POST("api/v1/attachments")
    suspend fun createResource(@Body body: CreateResourceRequest): ApiResponse<KeerV2Resource>

    @DELETE("api/v1/attachments/{id}")
    suspend fun deleteResource(@Path("id") resourceId: String): ApiResponse<Unit>

    @GET("api/v1/instance/profile")
    suspend fun getProfile(): ApiResponse<MemosProfile>

    @GET("api/v1/users/{id}")
    suspend fun getUser(@Path("id") userId: String): ApiResponse<KeerV2User>

    @PATCH("api/v1/users/{id}")
    suspend fun updateUser(@Path("id") userId: String, @Body body: UpdateUserRequest): ApiResponse<KeerV2User>

    @GET("api/v1/users/{id}:getStats")
    suspend fun getUserStats(@Path("id") userId: String): ApiResponse<KeerV2Stats>
}

@Serializable
data class KeerV2User(
    val name: String,
    val role: MemosRole = MemosRole.ROLE_UNSPECIFIED,
    val username: String,
    val email: String? = null,
    val displayName: String? = null,
    val avatarUrl: String? = null,
    val description: String? = null,
    val state: KeerV2State = KeerV2State.STATE_UNSPECIFIED,
    @Serializable(with = Rfc3339InstantSerializer::class)
    val createTime: Instant? = null,
    @Serializable(with = Rfc3339InstantSerializer::class)
    val updateTime: Instant? = null
)

@Serializable
data class GetCurrentUserResponse(
    val user: KeerV2User?
)

@Serializable
data class KeerV2CreateMemoRequest(
    val content: String,
    val visibility: MemosVisibility?,
    val attachments: List<KeerV2Resource>?,
    val tags: List<String>? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    @Serializable(with = Rfc3339InstantSerializer::class)
    val createTime: Instant? = null
)

@Serializable
data class UpdateUserRequest(
    val user: UpdateUserBody
)

@Serializable
data class UpdateUserBody(
    val avatarUrl: String? = null,
    val avatar: UpdateUserAvatarUpload? = null
)

@Serializable
data class UpdateUserAvatarUpload(
    val content: String,
    val type: String? = null
)

@Serializable
data class ListMemosResponse(
    val memos: List<KeerV2Memo>,
    val nextPageToken: String?
)

@Serializable
data class ListMemoChangesResponse(
    val memos: List<KeerV2Memo>,
    val deletedMemoNames: List<String> = emptyList(),
    @Serializable(with = Rfc3339InstantSerializer::class)
    val syncAnchor: Instant? = null
)

@Serializable
data class ListGroupsResponse(
    val groups: List<KeerV2Group>
)

@Serializable
data class CreateGroupRequest(
    val name: String,
    val description: String = ""
)

@Serializable
data class UpdateGroupRequest(
    val name: String? = null,
    val description: String? = null
)

@Serializable
data class KeerV2GroupMember(
    val name: String,
    val username: String,
    val displayName: String? = null
)

@Serializable
data class KeerV2Group(
    val name: String,
    val creator: String,
    @Serializable(with = Rfc3339InstantSerializer::class)
    val createTime: Instant? = null,
    @Serializable(with = Rfc3339InstantSerializer::class)
    val updateTime: Instant? = null,
    val groupName: String,
    val description: String? = null,
    val members: List<KeerV2GroupMember> = emptyList()
)

@Serializable
data class ListGroupMessagesResponse(
    val messages: List<KeerV2GroupMessage>,
    val nextPageToken: String? = null
)

@Serializable
data class CreateGroupMessageRequest(
    val content: String,
    val tags: List<String>? = null
)

@Serializable
data class KeerV2GroupMessage(
    val name: String,
    val group: String,
    val creator: String,
    @Serializable(with = Rfc3339InstantSerializer::class)
    val createTime: Instant? = null,
    @Serializable(with = Rfc3339InstantSerializer::class)
    val updateTime: Instant? = null,
    val content: String? = null,
    val tags: List<String>? = null
)

@Serializable
data class ListGroupTagsResponse(
    val tags: List<String> = emptyList()
)

@Serializable
data class AddGroupTagRequest(
    val tag: String
)

@Serializable
data class UpdateMemoRequest(
    val content: String? = null,
    val visibility: MemosVisibility? = null,
    val state: KeerV2State? = null,
    val pinned: Boolean? = null,
    val tags: List<String>? = null,
    @Serializable(with = Rfc3339InstantSerializer::class)
    val updateTime: Instant? = null,
    val attachments: List<KeerV2Resource>? = null
)

@Serializable
data class ListResourceResponse(
    val attachments: List<KeerV2Resource>
)

@Serializable
data class CreateResourceRequest(
    val filename: String,
    val type: String,
    val content: String,
    val memo: String?
)

@Serializable
data class KeerV2Memo(
    val name: String,
    val state: KeerV2State? = null,
    val creator: String? = null,
    @Serializable(with = Rfc3339InstantSerializer::class)
    val createTime: Instant? = null,
    @Serializable(with = Rfc3339InstantSerializer::class)
    val updateTime: Instant? = null,
    val content: String? = null,
    val visibility: MemosVisibility? = null,
    val pinned: Boolean? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val attachments: List<KeerV2Resource>? = null,
    val tags: List<String>? = null
)

@Serializable
data class KeerV2Resource(
    val name: String? = null,
    @Serializable(with = Rfc3339InstantSerializer::class)
    val createTime: Instant? = null,
    val filename: String? = null,
    val externalLink: String? = null,
    val type: String? = null,
    val size: String? = null,
    val thumbnailName: String? = null,
    val thumbnailExternalLink: String? = null,
    val thumbnailFilename: String? = null,
    val thumbnailType: String? = null,
    val memo: String? = null
) {
    fun uri(host: String): Uri {
        if (!externalLink.isNullOrEmpty()) {
            return externalLink.toUri()
        }
        return host.toUri()
            .buildUpon().appendPath("file").appendEncodedPath(name ?: "").appendPath(filename ?: "").build()
    }

    fun thumbnailUri(host: String): Uri? {
        val directLink = thumbnailExternalLink?.trim().orEmpty()
        if (directLink.isNotEmpty()) {
            return directLink.toUri()
        }
        val resolvedName = thumbnailName?.trim().orEmpty()
        val resolvedFilename = thumbnailFilename?.trim().orEmpty()
        if (resolvedName.isEmpty() || resolvedFilename.isEmpty()) {
            return null
        }
        return host.toUri()
            .buildUpon()
            .appendPath("file")
            .appendEncodedPath(resolvedName)
            .appendPath(resolvedFilename)
            .build()
    }
}

@Serializable
data class KeerV2UserSettingGeneralSetting(
    val locale: String? = null,
    val memoVisibility: MemosVisibility? = null,
    val theme: String? = null
)

@Serializable
data class KeerV2UserSetting(
    val generalSetting: KeerV2UserSettingGeneralSetting?
)

@Serializable
enum class KeerV2State {
    @SerialName("STATE_UNSPECIFIED")
    STATE_UNSPECIFIED,
    @SerialName("NORMAL")
    NORMAL,
    @SerialName("ARCHIVED")
    ARCHIVED,
}

@Serializable
data class KeerV2Stats(
    val tagCount: Map<String, Int>,
)
