package site.lcyk.keer.viewmodel

import java.time.Instant
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import site.lcyk.keer.data.local.dao.UiSurfaceSnapshotDao
import site.lcyk.keer.data.local.entity.MemoEntity
import site.lcyk.keer.data.local.entity.ResourceEntity
import site.lcyk.keer.data.local.entity.UiSurfaceSnapshotEntity
import site.lcyk.keer.data.model.CachedMemoItem
import site.lcyk.keer.data.model.DailyUsageStat
import site.lcyk.keer.data.model.GroupIdAlias
import site.lcyk.keer.data.model.MemoColumnConfig
import site.lcyk.keer.data.model.MemoGroup
import site.lcyk.keer.data.model.MemoQuotePreview
import site.lcyk.keer.data.model.MemoVisibility
import site.lcyk.keer.data.model.buildHeatmapTimeline
import site.lcyk.keer.data.model.toCachedMemoItem
import site.lcyk.keer.data.model.toMemo
import site.lcyk.keer.util.extractCollaboratorIds
import site.lcyk.keer.util.isCollaboratorTag
import site.lcyk.keer.util.isQuoteTag
import site.lcyk.keer.util.normalizeTagList
import site.lcyk.keer.util.ResolvedMemoQuote
import timber.log.Timber

data class UiWarmSnapshot<T>(
    val state: T,
    val updatedAtEpochMillis: Long,
)

data class UiHydrationState(
    val snapshotAgeMillis: Long? = null,
    val isHydrating: Boolean = true,
    val isStale: Boolean = false,
    val hasWarmSnapshot: Boolean = false,
)

@Singleton
class UiProjectionEngine @Inject constructor(
    private val snapshotDao: UiSurfaceSnapshotDao,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    suspend fun readDrawerSnapshot(accountKey: String): UiWarmSnapshot<DrawerUiState>? {
        return readSnapshot(accountKey, DRAWER_SURFACE_KEY) { payload ->
            json.decodeFromString(DrawerUiStateSnapshot.serializer(), payload).toUiState()
        }
    }

    suspend fun saveDrawerSnapshot(accountKey: String, state: DrawerUiState) {
        saveSnapshot(
            accountKey = accountKey,
            surfaceKey = DRAWER_SURFACE_KEY,
            payloadJson = json.encodeToString(
                DrawerUiStateSnapshot.serializer(),
                DrawerUiStateSnapshot.from(state),
            ),
        )
    }

    suspend fun readFeedSnapshot(accountKey: String): UiWarmSnapshot<FeedUiState>? {
        return readSnapshot(accountKey, FEED_SURFACE_KEY) { payload ->
            json.decodeFromString(FeedUiStateSnapshot.serializer(), payload).toUiState()
        }
    }

    suspend fun saveFeedSnapshot(accountKey: String, state: FeedUiState) {
        saveSnapshot(
            accountKey = accountKey,
            surfaceKey = FEED_SURFACE_KEY,
            payloadJson = json.encodeToString(
                FeedUiStateSnapshot.serializer(),
                FeedUiStateSnapshot.from(state),
            ),
        )
    }

    suspend fun readExploreSnapshot(accountKey: String): UiWarmSnapshot<ExploreUiState>? {
        return readSnapshot(accountKey, EXPLORE_SURFACE_KEY) { payload ->
            json.decodeFromString(ExploreUiStateSnapshot.serializer(), payload).toUiState()
        }
    }

    suspend fun saveExploreSnapshot(accountKey: String, state: ExploreUiState) {
        saveSnapshot(
            accountKey = accountKey,
            surfaceKey = EXPLORE_SURFACE_KEY,
            payloadJson = json.encodeToString(
                ExploreUiStateSnapshot.serializer(),
                ExploreUiStateSnapshot.from(state),
            ),
        )
    }

    suspend fun readGroupChatSnapshot(
        accountKey: String,
        groupId: String,
    ): UiWarmSnapshot<GroupChatUiState>? {
        return readSnapshot(accountKey, groupSurfaceKey(groupId)) { payload ->
            json.decodeFromString(GroupChatUiStateSnapshot.serializer(), payload).toUiState()
        }
    }

    suspend fun saveGroupChatSnapshot(
        accountKey: String,
        groupId: String,
        state: GroupChatUiState,
    ) {
        saveSnapshot(
            accountKey = accountKey,
            surfaceKey = groupSurfaceKey(groupId),
            payloadJson = json.encodeToString(
                GroupChatUiStateSnapshot.serializer(),
                GroupChatUiStateSnapshot.from(state),
            ),
        )
    }

    suspend fun readResourceListSnapshot(accountKey: String): UiWarmSnapshot<ResourceListUiState>? {
        return readSnapshot(accountKey, RESOURCE_LIST_SURFACE_KEY) { payload ->
            json.decodeFromString(ResourceListSnapshot.serializer(), payload).toUiState()
        }
    }

    suspend fun saveResourceListSnapshot(accountKey: String, state: ResourceListUiState) {
        saveSnapshot(
            accountKey = accountKey,
            surfaceKey = RESOURCE_LIST_SURFACE_KEY,
            payloadJson = json.encodeToString(
                ResourceListSnapshot.serializer(),
                ResourceListSnapshot.from(state),
            ),
        )
    }

    private suspend fun <T> readSnapshot(
        accountKey: String,
        surfaceKey: String,
        decoder: (String) -> T,
    ): UiWarmSnapshot<T>? {
        if (accountKey.isBlank()) {
            return null
        }
        val stored = try {
            snapshotDao.getSnapshot(accountKey, surfaceKey)
        } catch (error: Exception) {
            if (error is CancellationException) {
                throw error
            }
            Timber.tag(SNAPSHOT_LOG_TAG).w(
                error,
                "Failed to read snapshot account=%s surface=%s; deleting corrupt snapshot",
                accountKey,
                surfaceKey,
            )
            deleteSnapshotQuietly(accountKey, surfaceKey)
            return null
        } ?: return null
        val decoded = try {
            decoder(stored.payloadJson)
        } catch (error: Exception) {
            if (error is CancellationException) {
                throw error
            }
            Timber.tag(SNAPSHOT_LOG_TAG).w(
                error,
                "Failed to decode snapshot account=%s surface=%s; deleting corrupt snapshot",
                accountKey,
                surfaceKey,
            )
            deleteSnapshotQuietly(accountKey, surfaceKey)
            return null
        }
        return UiWarmSnapshot(
            state = decoded,
            updatedAtEpochMillis = stored.updatedAtEpochMillis,
        )
    }

    private suspend fun saveSnapshot(
        accountKey: String,
        surfaceKey: String,
        payloadJson: String,
    ) {
        if (accountKey.isBlank()) {
            return
        }
        val payloadSizeBytes = payloadJson.toByteArray(Charsets.UTF_8).size
        if (payloadSizeBytes > MAX_SNAPSHOT_PAYLOAD_BYTES) {
            Timber.tag(SNAPSHOT_LOG_TAG).w(
                "Skipping oversized snapshot account=%s surface=%s sizeBytes=%d",
                accountKey,
                surfaceKey,
                payloadSizeBytes,
            )
            deleteSnapshotQuietly(accountKey, surfaceKey)
            return
        }
        snapshotDao.upsertSnapshot(
            UiSurfaceSnapshotEntity(
                accountKey = accountKey,
                surfaceKey = surfaceKey,
                payloadJson = payloadJson,
                updatedAtEpochMillis = System.currentTimeMillis(),
            )
        )
    }

    private suspend fun deleteSnapshotQuietly(
        accountKey: String,
        surfaceKey: String,
    ) {
        runCatching {
            snapshotDao.deleteSnapshot(accountKey, surfaceKey)
        }.onFailure { error ->
            if (error is CancellationException) {
                throw error
            }
            Timber.tag(SNAPSHOT_LOG_TAG).w(
                error,
                "Failed to delete snapshot account=%s surface=%s",
                accountKey,
                surfaceKey,
            )
        }
    }

    companion object {
        private const val DRAWER_SURFACE_KEY = "drawer"
        private const val FEED_SURFACE_KEY = "feed"
        private const val EXPLORE_SURFACE_KEY = "explore"
        private const val RESOURCE_LIST_SURFACE_KEY = "resource_list"
        private const val MAX_SNAPSHOT_PAYLOAD_BYTES = 512 * 1024
        private const val SNAPSHOT_LOG_TAG = "UiWarmSnapshot"

        fun groupSurfaceKey(groupId: String): String = "group:$groupId"
    }
}

@Serializable
private data class DailyUsageStatSnapshot(
    val epochDay: Long,
    val count: Int,
) {
    fun toModel(): DailyUsageStat {
        return DailyUsageStat(
            date = LocalDate.ofEpochDay(epochDay),
            count = count,
        )
    }

    companion object {
        fun from(stat: DailyUsageStat): DailyUsageStatSnapshot {
            return DailyUsageStatSnapshot(
                epochDay = stat.date.toEpochDay(),
                count = stat.count,
            )
        }
    }
}

@Serializable
private data class ResourceEntitySnapshot(
    val identifier: String,
    val remoteId: String? = null,
    val accountKey: String,
    val dateEpochMillis: Long,
    val filename: String,
    val uri: String,
    val localUri: String? = null,
    val mimeType: String? = null,
    val encryptionMetadata: String? = null,
    val thumbnailUri: String? = null,
    val thumbnailLocalUri: String? = null,
    val memoId: String? = null,
) {
    fun toEntity(): ResourceEntity {
        return ResourceEntity(
            identifier = identifier,
            remoteId = remoteId,
            accountKey = accountKey,
            date = Instant.ofEpochMilli(dateEpochMillis),
            filename = filename,
            uri = uri,
            localUri = localUri,
            mimeType = mimeType,
            encryptionMetadata = encryptionMetadata,
            thumbnailUri = thumbnailUri,
            thumbnailLocalUri = thumbnailLocalUri,
            memoId = memoId,
        )
    }

    companion object {
        fun from(entity: ResourceEntity): ResourceEntitySnapshot {
            return ResourceEntitySnapshot(
                identifier = entity.identifier,
                remoteId = entity.remoteId,
                accountKey = entity.accountKey,
                dateEpochMillis = entity.date.toEpochMilli(),
                filename = entity.filename,
                uri = entity.uri,
                localUri = entity.localUri,
                mimeType = entity.mimeType,
                encryptionMetadata = entity.encryptionMetadata,
                thumbnailUri = entity.thumbnailUri,
                thumbnailLocalUri = entity.thumbnailLocalUri,
                memoId = entity.memoId,
            )
        }
    }
}

@Serializable
private data class MemoEntitySnapshot(
    val identifier: String,
    val remoteId: String? = null,
    val accountKey: String,
    val content: String,
    val dateEpochMillis: Long,
    val visibility: String,
    val pinned: Boolean,
    val archived: Boolean = false,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val quoteSourceKind: String? = null,
    val quoteSource: String? = null,
    val quoteStatus: String? = null,
    val quoteContentPreview: String? = null,
    val quoteDateEpochMillis: Long? = null,
    val quoteHasAttachments: Boolean = false,
    val needsSync: Boolean = true,
    val isDeleted: Boolean = false,
    val lastModifiedEpochMillis: Long,
    val lastSyncedAtEpochMillis: Long? = null,
    val resources: List<ResourceEntitySnapshot> = emptyList(),
    val tags: List<String> = emptyList(),
) {
    fun toEntity(): MemoEntity {
        val entity = MemoEntity(
            identifier = identifier,
            remoteId = remoteId,
            accountKey = accountKey,
            content = content,
            date = Instant.ofEpochMilli(dateEpochMillis),
            visibility = MemoVisibility.entries.firstOrNull { it.name == visibility }
                ?: MemoVisibility.PRIVATE,
            pinned = pinned,
            archived = archived,
            latitude = latitude,
            longitude = longitude,
            quoteSourceKind = quoteSourceKind,
            quoteSource = quoteSource,
            quoteStatus = quoteStatus,
            quoteContentPreview = quoteContentPreview,
            quoteDate = quoteDateEpochMillis?.let(Instant::ofEpochMilli),
            quoteHasAttachments = quoteHasAttachments,
            needsSync = needsSync,
            isDeleted = isDeleted,
            lastModified = Instant.ofEpochMilli(lastModifiedEpochMillis),
            lastSyncedAt = lastSyncedAtEpochMillis?.let(Instant::ofEpochMilli),
        )
        entity.resources = resources.map(ResourceEntitySnapshot::toEntity)
        entity.tags = tags
        return entity
    }

    companion object {
        fun from(entity: MemoEntity): MemoEntitySnapshot {
            return MemoEntitySnapshot(
                identifier = entity.identifier,
                remoteId = entity.remoteId,
                accountKey = entity.accountKey,
                content = entity.content,
                dateEpochMillis = entity.date.toEpochMilli(),
                visibility = entity.visibility.name,
                pinned = entity.pinned,
                archived = entity.archived,
                latitude = entity.latitude,
                longitude = entity.longitude,
                quoteSourceKind = entity.quoteSourceKind,
                quoteSource = entity.quoteSource,
                quoteStatus = entity.quoteStatus,
                quoteContentPreview = entity.quoteContentPreview,
                quoteDateEpochMillis = entity.quoteDate?.toEpochMilli(),
                quoteHasAttachments = entity.quoteHasAttachments,
                needsSync = entity.needsSync,
                isDeleted = entity.isDeleted,
                lastModifiedEpochMillis = entity.lastModified.toEpochMilli(),
                lastSyncedAtEpochMillis = entity.lastSyncedAt?.toEpochMilli(),
                resources = entity.resources.map(ResourceEntitySnapshot::from),
                tags = entity.tags,
            )
        }
    }
}

@Serializable
private data class MemoQuotePreviewSnapshot(
    val previewText: String,
    val dateEpochMillis: Long? = null,
    val hasResources: Boolean = false,
) {
    fun toModel(): MemoQuotePreview {
        return MemoQuotePreview(
            previewText = previewText,
            date = dateEpochMillis?.let(Instant::ofEpochMilli),
            hasResources = hasResources,
        )
    }

    companion object {
        fun from(preview: MemoQuotePreview): MemoQuotePreviewSnapshot {
            return MemoQuotePreviewSnapshot(
                previewText = preview.previewText,
                dateEpochMillis = preview.date?.toEpochMilli(),
                hasResources = preview.hasResources,
            )
        }
    }
}

@Serializable
private data class MemoCardUiModelSnapshot(
    val memo: MemoEntitySnapshot,
    val resolvedQuotePreview: MemoQuotePreviewSnapshot? = null,
    val displayTags: List<String> = emptyList(),
    val collaboratorIds: List<String> = emptyList(),
    val authorName: String? = null,
    val authorAvatarUrl: String? = null,
) {
    fun toModel(): MemoCardUiModel {
        val memoEntity = memo.toEntity()
        return MemoCardUiModel(
            memo = memoEntity,
            resolvedQuote = resolvedQuotePreview?.toModel()?.let { preview ->
                ResolvedMemoQuote(
                    sourceMemo = null,
                    preview = preview,
                )
            },
            displayTags = displayTags.ifEmpty {
                normalizeTagList(
                    memoEntity.tags
                        .filterNot(::isCollaboratorTag)
                        .filterNot(::isQuoteTag)
                )
            },
            collaboratorIds = collaboratorIds.ifEmpty {
                extractCollaboratorIds(memoEntity.tags)
            },
            authorName = authorName,
            authorAvatarUrl = authorAvatarUrl,
        )
    }

    companion object {
        fun from(model: MemoCardUiModel): MemoCardUiModelSnapshot {
            return MemoCardUiModelSnapshot(
                memo = MemoEntitySnapshot.from(model.memo),
                resolvedQuotePreview = model.resolvedQuote?.preview?.let(MemoQuotePreviewSnapshot::from),
                displayTags = model.displayTags,
                collaboratorIds = model.collaboratorIds,
                authorName = model.authorName,
                authorAvatarUrl = model.authorAvatarUrl,
            )
        }
    }
}

@Serializable
private data class ExploreCardUiModelSnapshot(
    val source: ExploreMemoItemSnapshot,
    val memo: MemoEntitySnapshot,
    val canManage: Boolean,
    val resolvedQuotePreview: MemoQuotePreviewSnapshot? = null,
    val displayTags: List<String> = emptyList(),
    val collaboratorIds: List<String> = emptyList(),
    val authorName: String? = null,
    val authorAvatarUrl: String? = null,
) {
    fun toModel(): ExploreCardUiModel {
        val memoEntity = memo.toEntity()
        val sourceModel = source.toModel()
        return ExploreCardUiModel(
            source = sourceModel,
            memo = memoEntity,
            canManage = canManage,
            resolvedQuote = resolvedQuotePreview?.toModel()?.let { preview ->
                ResolvedMemoQuote(
                    sourceMemo = null,
                    preview = preview,
                )
            },
            displayTags = displayTags.ifEmpty {
                buildMemoCardDisplayTags(memoEntity.tags)
            },
            collaboratorIds = collaboratorIds.ifEmpty {
                buildMemoCardCollaboratorIds(memoEntity.tags)
            },
            authorName = authorName ?: sourceModel.memo.creator?.name,
            authorAvatarUrl = authorAvatarUrl ?: sourceModel.memo.creator?.avatarUrl,
        )
    }

    companion object {
        fun from(model: ExploreCardUiModel): ExploreCardUiModelSnapshot {
            return ExploreCardUiModelSnapshot(
                source = ExploreMemoItemSnapshot.from(model.source),
                memo = MemoEntitySnapshot.from(model.memo),
                canManage = model.canManage,
                resolvedQuotePreview = model.resolvedQuote?.preview?.let(MemoQuotePreviewSnapshot::from),
                displayTags = model.displayTags,
                collaboratorIds = model.collaboratorIds,
                authorName = model.authorName,
                authorAvatarUrl = model.authorAvatarUrl,
            )
        }
    }
}

@Serializable
private data class GroupChatCardUiModelSnapshot(
    val source: CachedMemoItem,
    val memo: MemoEntitySnapshot,
    val canManage: Boolean,
    val resolvedQuotePreview: MemoQuotePreviewSnapshot? = null,
    val displayTags: List<String> = emptyList(),
    val collaboratorIds: List<String> = emptyList(),
    val authorName: String? = null,
    val authorAvatarUrl: String? = null,
) {
    fun toModel(): GroupChatCardUiModel {
        val memoEntity = memo.toEntity()
        val sourceMemo = source.toMemo()
        return GroupChatCardUiModel(
            source = sourceMemo,
            memo = memoEntity,
            canManage = canManage,
            resolvedQuote = resolvedQuotePreview?.toModel()?.let { preview ->
                ResolvedMemoQuote(
                    sourceMemo = null,
                    preview = preview,
                )
            },
            displayTags = displayTags.ifEmpty {
                buildMemoCardDisplayTags(memoEntity.tags)
            },
            collaboratorIds = collaboratorIds.ifEmpty {
                buildMemoCardCollaboratorIds(memoEntity.tags)
            },
            authorName = authorName ?: sourceMemo.creator?.name,
            authorAvatarUrl = authorAvatarUrl ?: sourceMemo.creator?.avatarUrl,
        )
    }

    companion object {
        fun from(model: GroupChatCardUiModel): GroupChatCardUiModelSnapshot {
            return GroupChatCardUiModelSnapshot(
                source = model.source.toCachedMemoItem(),
                memo = MemoEntitySnapshot.from(model.memo),
                canManage = model.canManage,
                resolvedQuotePreview = model.resolvedQuote?.preview?.let(MemoQuotePreviewSnapshot::from),
                displayTags = model.displayTags,
                collaboratorIds = model.collaboratorIds,
                authorName = model.authorName,
                authorAvatarUrl = model.authorAvatarUrl,
            )
        }
    }
}

@Serializable
private data class HomeMemoItemSnapshot(
    val memo: MemoEntitySnapshot,
    val groupId: String? = null,
    val authorName: String? = null,
    val authorAvatarUrl: String? = null,
) {
    fun toModel(): HomeMemoItem {
        return HomeMemoItem(
            memo = memo.toEntity(),
            groupId = groupId,
            authorName = authorName,
            authorAvatarUrl = authorAvatarUrl,
        )
    }

    companion object {
        fun from(item: HomeMemoItem): HomeMemoItemSnapshot {
            return HomeMemoItemSnapshot(
                memo = MemoEntitySnapshot.from(item.memo),
                groupId = item.groupId,
                authorName = item.authorName,
                authorAvatarUrl = item.authorAvatarUrl,
            )
        }
    }
}

@Serializable
private data class HomeMemoCardUiModelSnapshot(
    val card: MemoCardUiModelSnapshot,
    val groupId: String? = null,
) {
    fun toModel(): HomeMemoCardUiModel {
        return HomeMemoCardUiModel(
            card = card.toModel(),
            groupId = groupId,
        )
    }

    companion object {
        fun from(model: HomeMemoCardUiModel): HomeMemoCardUiModelSnapshot {
            return HomeMemoCardUiModelSnapshot(
                card = MemoCardUiModelSnapshot.from(model.card),
                groupId = model.groupId,
            )
        }
    }
}

@Serializable
private data class DrawerUiStateSnapshot(
    val tags: List<String> = emptyList(),
    val visibleOrderedTags: List<String> = emptyList(),
    val tagTree: List<DrawerTagTreeNodeSnapshot> = emptyList(),
    val tagEntries: List<DrawerTagEntryUiModelSnapshot> = emptyList(),
    val matrix: List<DailyUsageStatSnapshot> = emptyList(),
    val activeDayCount: Long = 0L,
    val stats: DrawerStatsUiModelSnapshot? = null,
    val drawerGroups: List<MemoGroup> = emptyList(),
    val groupItems: List<DrawerGroupUiModelSnapshot> = emptyList(),
    val visibleColumns: List<MemoColumnConfig> = emptyList(),
    val columnItems: List<DrawerColumnUiModelSnapshot> = emptyList(),
    val groupIdAliases: List<GroupIdAlias> = emptyList(),
) {
    fun toUiState(): DrawerUiState {
        val resolvedMatrix = matrix.map(DailyUsageStatSnapshot::toModel)
        val resolvedStats = stats?.toModel() ?: buildDrawerStatsUiModel(
            matrix = resolvedMatrix,
            tags = tags,
        )
        return DrawerUiState(
            tags = tags,
            visibleOrderedTags = visibleOrderedTags,
            tagTree = if (tagTree.isNotEmpty()) {
                tagTree.map(DrawerTagTreeNodeSnapshot::toModel)
            } else {
                buildDrawerTagTree(visibleOrderedTags)
            },
            tagEntries = if (tagEntries.isNotEmpty()) {
                tagEntries.map(DrawerTagEntryUiModelSnapshot::toModel)
            } else {
                buildDrawerTagEntries(
                    if (tagTree.isNotEmpty()) {
                        tagTree.map(DrawerTagTreeNodeSnapshot::toModel)
                    } else {
                        buildDrawerTagTree(visibleOrderedTags)
                    }
                )
            },
            matrix = resolvedMatrix,
            activeDayCount = if (activeDayCount != 0L) activeDayCount else resolvedStats.activeDayCount,
            stats = resolvedStats,
            heatmapTimeline = buildHeatmapTimeline(resolvedMatrix),
            drawerGroups = drawerGroups,
            groupItems = if (groupItems.isNotEmpty()) {
                groupItems.map(DrawerGroupUiModelSnapshot::toModel)
            } else {
                buildDrawerGroupUiModels(drawerGroups)
            },
            visibleColumns = visibleColumns,
            columnItems = if (columnItems.isNotEmpty()) {
                columnItems.map(DrawerColumnUiModelSnapshot::toModel)
            } else {
                buildDrawerColumnUiModels(visibleColumns)
            },
            groupIdAliases = groupIdAliases,
        )
    }

    companion object {
        fun from(state: DrawerUiState): DrawerUiStateSnapshot {
            return DrawerUiStateSnapshot(
                tags = state.tags,
                visibleOrderedTags = state.visibleOrderedTags,
                tagTree = state.tagTree.map(DrawerTagTreeNodeSnapshot::from),
                tagEntries = state.tagEntries.map(DrawerTagEntryUiModelSnapshot::from),
                matrix = state.matrix.map(DailyUsageStatSnapshot::from),
                activeDayCount = state.activeDayCount,
                stats = DrawerStatsUiModelSnapshot.from(state.stats),
                drawerGroups = state.drawerGroups,
                groupItems = state.groupItems.map(DrawerGroupUiModelSnapshot::from),
                visibleColumns = state.visibleColumns,
                columnItems = state.columnItems.map(DrawerColumnUiModelSnapshot::from),
                groupIdAliases = state.groupIdAliases,
            )
        }
    }
}

@Serializable
private data class DrawerTagEntryUiModelSnapshot(
    val fullPath: String,
    val displayName: String,
    val depth: Int,
    val selectable: Boolean,
    val expandable: Boolean,
    val ancestorPaths: List<String> = emptyList(),
) {
    fun toModel(): DrawerTagEntryUiModel = DrawerTagEntryUiModel(
        fullPath = fullPath,
        displayName = displayName,
        depth = depth,
        selectable = selectable,
        expandable = expandable,
        ancestorPaths = ancestorPaths,
    )

    companion object {
        fun from(model: DrawerTagEntryUiModel): DrawerTagEntryUiModelSnapshot {
            return DrawerTagEntryUiModelSnapshot(
                fullPath = model.fullPath,
                displayName = model.displayName,
                depth = model.depth,
                selectable = model.selectable,
                expandable = model.expandable,
                ancestorPaths = model.ancestorPaths,
            )
        }
    }
}

@Serializable
private data class DrawerStatsUiModelSnapshot(
    val memoCount: Int,
    val tagCount: Int,
    val activeDayCount: Long,
) {
    fun toModel(): DrawerStatsUiModel = DrawerStatsUiModel(
        memoCount = memoCount,
        tagCount = tagCount,
        activeDayCount = activeDayCount,
    )

    companion object {
        fun from(model: DrawerStatsUiModel): DrawerStatsUiModelSnapshot {
            return DrawerStatsUiModelSnapshot(
                memoCount = model.memoCount,
                tagCount = model.tagCount,
                activeDayCount = model.activeDayCount,
            )
        }
    }
}

@Serializable
private data class DrawerGroupUiModelSnapshot(
    val id: String,
    val name: String,
    val type: String,
    val hasUnreadMessages: Boolean,
) {
    fun toModel(): DrawerGroupUiModel = DrawerGroupUiModel(
        id = id,
        name = name,
        type = site.lcyk.keer.data.model.MemoGroupType.entries
            .firstOrNull { candidate -> candidate.name == type }
            ?: site.lcyk.keer.data.model.MemoGroupType.GROUP,
        hasUnreadMessages = hasUnreadMessages,
    )

    companion object {
        fun from(model: DrawerGroupUiModel): DrawerGroupUiModelSnapshot {
            return DrawerGroupUiModelSnapshot(
                id = model.id,
                name = model.name,
                type = model.type.name,
                hasUnreadMessages = model.hasUnreadMessages,
            )
        }
    }
}

@Serializable
private data class DrawerColumnUiModelSnapshot(
    val id: String,
    val name: String,
) {
    fun toModel(): DrawerColumnUiModel = DrawerColumnUiModel(
        id = id,
        name = name,
    )

    companion object {
        fun from(model: DrawerColumnUiModel): DrawerColumnUiModelSnapshot {
            return DrawerColumnUiModelSnapshot(
                id = model.id,
                name = model.name,
            )
        }
    }
}

@Serializable
private data class DrawerTagTreeNodeSnapshot(
    val segment: String,
    val fullPath: String,
    val isRealTag: Boolean = false,
    val children: List<DrawerTagTreeNodeSnapshot> = emptyList(),
) {
    fun toModel(): DrawerTagTreeNode = DrawerTagTreeNode(
        segment = segment,
        fullPath = fullPath,
        isRealTag = isRealTag,
        children = children.map(DrawerTagTreeNodeSnapshot::toModel),
    )

    companion object {
        fun from(node: DrawerTagTreeNode): DrawerTagTreeNodeSnapshot {
            return DrawerTagTreeNodeSnapshot(
                segment = node.segment,
                fullPath = node.fullPath,
                isRealTag = node.isRealTag,
                children = node.children.map(DrawerTagTreeNodeSnapshot::from),
            )
        }
    }
}

@Serializable
private data class FeedUiStateSnapshot(
    val memos: List<MemoEntitySnapshot> = emptyList(),
    val tags: List<String> = emptyList(),
    val matrix: List<DailyUsageStatSnapshot> = emptyList(),
    val homeMemos: List<HomeMemoItemSnapshot> = emptyList(),
    val homeMemoCards: List<HomeMemoCardUiModelSnapshot> = emptyList(),
    val resolvedQuotePreviews: Map<String, MemoQuotePreviewSnapshot> = emptyMap(),
    val memoCards: List<MemoCardUiModelSnapshot> = emptyList(),
) {
    fun toUiState(): FeedUiState {
        val legacyResolvedQuotes = resolvedQuotePreviews.mapValues { (_, preview) ->
            ResolvedMemoQuote(
                sourceMemo = null,
                preview = preview.toModel(),
            )
        }
        val resolvedMemoCards = if (memoCards.isNotEmpty()) {
            memoCards.map(MemoCardUiModelSnapshot::toModel)
        } else {
            buildMemoCardUiModels(
                memos = memos.map(MemoEntitySnapshot::toEntity),
                resolvedQuoteByMemoId = legacyResolvedQuotes,
            )
        }
        val resolvedHomeMemoCards = if (homeMemoCards.isNotEmpty()) {
            homeMemoCards.map(HomeMemoCardUiModelSnapshot::toModel)
        } else {
            buildHomeMemoCardUiModels(
                items = homeMemos.map(HomeMemoItemSnapshot::toModel),
                resolvedQuoteByMemoId = legacyResolvedQuotes,
            )
        }
        val resolvedQuotes = if (legacyResolvedQuotes.isNotEmpty()) {
            legacyResolvedQuotes
        } else {
            buildResolvedQuoteMapFromMemoCards(resolvedMemoCards) +
                buildResolvedQuoteMapFromHomeMemoCards(resolvedHomeMemoCards)
        }
        return FeedUiState(
            memos = if (memos.isNotEmpty()) {
                memos.map(MemoEntitySnapshot::toEntity)
            } else {
                resolvedMemoCards.map(MemoCardUiModel::memo)
            },
            tags = tags,
            matrix = matrix.map(DailyUsageStatSnapshot::toModel),
            homeMemos = if (homeMemos.isNotEmpty()) {
                homeMemos.map(HomeMemoItemSnapshot::toModel)
            } else {
                buildHomeMemoItemsFromCards(resolvedHomeMemoCards)
            },
            homeMemoCards = resolvedHomeMemoCards,
            resolvedQuoteByMemoId = resolvedQuotes,
            memoCards = resolvedMemoCards,
        )
    }

    companion object {
        fun from(state: FeedUiState): FeedUiStateSnapshot {
            return FeedUiStateSnapshot(
                memos = emptyList(),
                tags = state.tags,
                matrix = state.matrix.map(DailyUsageStatSnapshot::from),
                homeMemos = emptyList(),
                homeMemoCards = state.homeMemoCards.map(HomeMemoCardUiModelSnapshot::from),
                resolvedQuotePreviews = emptyMap(),
                memoCards = state.memoCards.map(MemoCardUiModelSnapshot::from),
            )
        }
    }
}

@Serializable
private data class ExploreMemoItemSnapshot(
    val memo: CachedMemoItem,
    val groupId: String? = null,
) {
    fun toModel(): ExploreMemoItem {
        return ExploreMemoItem(
            memo = memo.toMemo(),
            groupId = groupId,
        )
    }

    companion object {
        fun from(item: ExploreMemoItem): ExploreMemoItemSnapshot {
            return ExploreMemoItemSnapshot(
                memo = item.memo.toCachedMemoItem(groupId = item.groupId),
                groupId = item.groupId,
            )
        }
    }
}

@Serializable
private data class ExploreUiStateSnapshot(
    val items: List<ExploreMemoItemSnapshot> = emptyList(),
    val resolvedQuotePreviews: Map<String, MemoQuotePreviewSnapshot> = emptyMap(),
    val cardItems: List<ExploreCardUiModelSnapshot> = emptyList(),
) {
    fun toUiState(): ExploreUiState {
        val resolvedCardItems = cardItems.map(ExploreCardUiModelSnapshot::toModel)
        return ExploreUiState(
            items = if (resolvedCardItems.isNotEmpty()) {
                resolvedCardItems.map(ExploreCardUiModel::source)
            } else {
                items.map(ExploreMemoItemSnapshot::toModel)
            },
            resolvedQuoteByMemoId = if (resolvedQuotePreviews.isNotEmpty()) {
                resolvedQuotePreviews.mapValues { (_, preview) ->
                    ResolvedMemoQuote(sourceMemo = null, preview = preview.toModel())
                }
            } else {
                resolvedCardItems.mapNotNull { card ->
                    card.resolvedQuote?.let { quote -> card.memo.identifier to quote }
                }.toMap()
            },
            cardItems = resolvedCardItems,
        )
    }

    companion object {
        fun from(state: ExploreUiState): ExploreUiStateSnapshot {
            return ExploreUiStateSnapshot(
                items = emptyList(),
                resolvedQuotePreviews = emptyMap(),
                cardItems = state.cardItems.map(ExploreCardUiModelSnapshot::from),
            )
        }
    }
}

@Serializable
private data class GroupChatUiStateSnapshot(
    val memos: List<CachedMemoItem> = emptyList(),
    val resolvedQuotePreviews: Map<String, MemoQuotePreviewSnapshot> = emptyMap(),
    val cardItems: List<GroupChatCardUiModelSnapshot> = emptyList(),
) {
    fun toUiState(): GroupChatUiState {
        val resolvedCardItems = cardItems.map(GroupChatCardUiModelSnapshot::toModel)
        return GroupChatUiState(
            memos = if (resolvedCardItems.isNotEmpty()) {
                resolvedCardItems.map(GroupChatCardUiModel::source)
            } else {
                memos.map(CachedMemoItem::toMemo)
            },
            resolvedQuoteByMemoId = if (resolvedQuotePreviews.isNotEmpty()) {
                resolvedQuotePreviews.mapValues { (_, preview) ->
                    ResolvedMemoQuote(sourceMemo = null, preview = preview.toModel())
                }
            } else {
                resolvedCardItems.mapNotNull { card ->
                    card.resolvedQuote?.let { quote -> card.memo.identifier to quote }
                }.toMap()
            },
            cardItems = resolvedCardItems,
        )
    }

    companion object {
        fun from(state: GroupChatUiState): GroupChatUiStateSnapshot {
            return GroupChatUiStateSnapshot(
                memos = emptyList(),
                resolvedQuotePreviews = emptyMap(),
                cardItems = state.cardItems.map(GroupChatCardUiModelSnapshot::from),
            )
        }
    }
}

@Serializable
private data class ResourceListSnapshot(
    val resources: List<ResourceEntitySnapshot> = emptyList(),
) {
    fun toUiState(): ResourceListUiState {
        val resolvedResources = resources.map(ResourceEntitySnapshot::toEntity)
        return buildResourceListUiState(resolvedResources)
    }

    companion object {
        fun from(state: ResourceListUiState): ResourceListSnapshot {
            return ResourceListSnapshot(
                resources = state.resources.map(ResourceEntitySnapshot::from),
            )
        }
    }
}
