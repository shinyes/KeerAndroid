package site.lcyk.keer.data.model

import java.time.Instant
import kotlinx.serialization.Serializable

@Serializable
enum class PendingGroupOperationType {
    CREATE,
    JOIN,
    UPDATE,
    DELETE_OR_LEAVE,
    ADD_TAG,
}

@Serializable
data class PendingGroupOperation(
    val operationId: String,
    val type: PendingGroupOperationType,
    val groupId: String,
    val name: String? = null,
    val description: String? = null,
    val tag: String? = null,
    val createdAtEpochMillis: Long = System.currentTimeMillis(),
)

@Serializable
data class CachedMemoItem(
    val remoteId: String,
    val groupId: String? = null,
    val content: String = "",
    val dateEpochMillis: Long = System.currentTimeMillis(),
    val pinned: Boolean = false,
    val visibility: String = MemoVisibility.PROTECTED.name,
    val tags: List<String> = emptyList(),
    val latitude: Double? = null,
    val longitude: Double? = null,
    val creatorId: String? = null,
    val creatorName: String? = null,
    val creatorAvatarUrl: String? = null,
    val resources: List<CachedResourceItem> = emptyList(),
    val archived: Boolean = false,
    val updatedAtEpochMillis: Long? = null,
)

@Serializable
data class CachedResourceItem(
    val remoteId: String,
    val dateEpochMillis: Long,
    val filename: String,
    val mimeType: String? = null,
    val encryptionMetadata: String? = null,
    val uri: String,
    val localUri: String? = null,
    val thumbnailUri: String? = null,
    val thumbnailLocalUri: String? = null,
)

@Serializable
data class CachedGroupTagSet(
    val groupId: String,
    val tags: List<String> = emptyList(),
    val updatedAtEpochMillis: Long = System.currentTimeMillis(),
)

@Serializable
data class GroupIdAlias(
    val localId: String,
    val remoteId: String,
    val updatedAtEpochMillis: Long = System.currentTimeMillis(),
)

fun CachedMemoItem.toMemo(): Memo {
    val resolvedVisibility = MemoVisibility.entries.firstOrNull { it.name == visibility }
        ?: MemoVisibility.PROTECTED
    val creator = creatorId?.let { id ->
        User(
            identifier = id,
            name = creatorName?.takeIf { it.isNotBlank() } ?: id,
            startDate = Instant.ofEpochMilli(dateEpochMillis),
            avatarUrl = creatorAvatarUrl
        )
    }

    return Memo(
        remoteId = remoteId,
        content = content,
        date = Instant.ofEpochMilli(dateEpochMillis),
        pinned = pinned,
        visibility = resolvedVisibility,
        resources = resources.map(CachedResourceItem::toResource),
        tags = tags,
        latitude = latitude,
        longitude = longitude,
        creator = creator,
        archived = archived,
        updatedAt = updatedAtEpochMillis?.let(Instant::ofEpochMilli)
    )
}

fun Memo.toCachedMemoItem(groupId: String? = null): CachedMemoItem {
    return CachedMemoItem(
        remoteId = remoteId,
        groupId = groupId,
        content = content,
        dateEpochMillis = date.toEpochMilli(),
        pinned = pinned,
        visibility = visibility.name,
        tags = tags,
        latitude = latitude,
        longitude = longitude,
        creatorId = creator?.identifier,
        creatorName = creator?.name,
        creatorAvatarUrl = creator?.avatarUrl,
        resources = resources.map(Resource::toCachedResourceItem),
        archived = archived,
        updatedAtEpochMillis = updatedAt?.toEpochMilli()
    )
}

private fun CachedResourceItem.toResource(): Resource {
    return Resource(
        remoteId = remoteId,
        date = Instant.ofEpochMilli(dateEpochMillis),
        filename = filename,
        mimeType = mimeType,
        encryptionMetadata = encryptionMetadata,
        uri = uri,
        localUri = localUri,
        thumbnailUri = thumbnailUri,
        thumbnailLocalUri = thumbnailLocalUri
    )
}

private fun Resource.toCachedResourceItem(): CachedResourceItem {
    return CachedResourceItem(
        remoteId = remoteId,
        dateEpochMillis = date.toEpochMilli(),
        filename = filename,
        mimeType = mimeType,
        encryptionMetadata = encryptionMetadata,
        uri = uri,
        localUri = localUri,
        thumbnailUri = thumbnailUri,
        thumbnailLocalUri = thumbnailLocalUri
    )
}
