package site.lcyk.keer.data.model

import kotlinx.serialization.Serializable

@Serializable
enum class MemoEditGesture {
    NONE,
    SINGLE,
    DOUBLE,
    LONG,
}

@Serializable
data class UserSettings(
    val groups: List<MemoGroup> = emptyList(),
    val draft: String = "",
    val acceptedUnsupportedSyncVersions: List<String> = emptyList(),
    val editGesture: MemoEditGesture = MemoEditGesture.NONE,
    val avatarUri: String = "",
    val avatarSyncPending: Boolean = false,
    val pinnedGroupMemoKeys: List<String> = emptyList(),
    val pendingGroupMemos: List<PendingGroupMemo> = emptyList(),
    val pendingGroupOperations: List<PendingGroupOperation> = emptyList(),
    val groupIdAliases: List<GroupIdAlias> = emptyList(),
    val cachedGroupMemos: List<CachedMemoItem> = emptyList(),
    val cachedExploreMemos: List<CachedMemoItem> = emptyList(),
    val cachedGroupTags: List<CachedGroupTagSet> = emptyList(),
    val memoSyncAnchor: String = "",
)
