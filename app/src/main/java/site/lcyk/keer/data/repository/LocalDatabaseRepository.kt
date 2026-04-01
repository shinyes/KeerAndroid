package site.lcyk.keer.data.repository

import android.net.Uri
import androidx.core.net.toUri
import com.skydoves.sandwich.ApiResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import site.lcyk.keer.R
import site.lcyk.keer.data.local.FileStorage
import site.lcyk.keer.data.local.dao.MemoDao
import site.lcyk.keer.data.local.entity.MemoEntity
import site.lcyk.keer.data.local.entity.ResourceEntity
import site.lcyk.keer.data.model.Account
import site.lcyk.keer.data.model.MemoVisibility
import site.lcyk.keer.data.model.User
import site.lcyk.keer.ext.string
import site.lcyk.keer.util.isValidTagName
import site.lcyk.keer.util.normalizeTagName
import okhttp3.MediaType
import java.io.File
import java.time.Instant
import java.util.UUID

class LocalDatabaseRepository(
    private val memoDao: MemoDao,
    private val fileStorage: FileStorage,
    private val account: Account.Local = Account.Local(),
) : AbstractMemoRepository() {
    private val accountKey = account.accountKey()
    private val recentTagUsageSince: Instant = Instant.now().minusSeconds(30L * 24L * 60L * 60L)

    override fun observeMemos(): Flow<List<MemoEntity>> {
        return flow {
            val projector = MemoListProjector()
            memoDao.observeAllMemos(accountKey).collect { memoItems ->
                emit(projector.project(memoItems))
            }
        }.flowOn(Dispatchers.Default)
    }

    override fun observeResources(): Flow<List<ResourceEntity>> {
        return memoDao.observeAllResources(accountKey)
    }

    override fun observeTags(): Flow<List<String>> {
        return memoDao.observeTagsByRecentUsage(accountKey, recentTagUsageSince)
    }

    override fun observeResource(identifier: String): Flow<ResourceEntity?> {
        return memoDao.observeResourceByIdentifier(identifier, accountKey)
    }

    override suspend fun listMemos(): ApiResponse<List<MemoEntity>> {
        return try {
            val memos = memoDao.getAllMemoItems(accountKey).map(::projectMemoWithRelations)
            ApiResponse.Success(memos)
        } catch (e: Exception) {
            ApiResponse.Failure.Exception(e)
        }
    }

    override suspend fun listArchivedMemos(): ApiResponse<List<MemoEntity>> {
        return try {
            val memos = memoDao.getArchivedMemoItems(accountKey).map(::projectMemoWithRelations)
            ApiResponse.Success(memos)
        } catch (e: Exception) {
            ApiResponse.Failure.Exception(e)
        }
    }

    override suspend fun createMemo(
        content: String,
        visibility: MemoVisibility,
        resources: List<ResourceEntity>,
        tags: List<String>?,
        createdAt: Instant?,
        latitude: Double?,
        longitude: Double?
    ): ApiResponse<MemoEntity> {
        return try {
            val now = Instant.now()
            val memoDate = createdAt ?: now
            val memo = MemoEntity(
                identifier = UUID.randomUUID().toString(),
                accountKey = accountKey,
                content = content,
                date = memoDate,
                visibility = visibility,
                pinned = false,
                archived = false,
                latitude = latitude,
                longitude = longitude,
                needsSync = false,
                isDeleted = false,
                lastModified = now,
                lastSyncedAt = now
            )
            memoDao.insertMemo(memo)
            memoDao.replaceMemoTags(memo.identifier, accountKey, tags.orEmpty())

            resources.forEach { resource ->
                memoDao.insertResource(
                    resource.copy(
                        accountKey = accountKey,
                        memoId = memo.identifier
                    )
                )
            }
            notifyMemoRelationsChanged(memo)

            ApiResponse.Success(withResources(memo))
        } catch (e: Exception) {
            ApiResponse.Failure.Exception(e)
        }
    }

    override suspend fun updateMemo(
        identifier: String,
        content: String?,
        resources: List<ResourceEntity>?,
        visibility: MemoVisibility?,
        tags: List<String>?,
        pinned: Boolean?
    ): ApiResponse<MemoEntity> {
        return try {
            val existingMemo = memoDao.getMemoById(identifier, accountKey)
                ?: return ApiResponse.Failure.Exception(Exception(R.string.memo_not_found.string))

            val updatedAt = Instant.now()
            val updatedMemo = existingMemo.copy(
                content = content ?: existingMemo.content,
                visibility = visibility ?: existingMemo.visibility,
                pinned = pinned ?: existingMemo.pinned,
                lastModified = updatedAt,
                lastSyncedAt = updatedAt,
                needsSync = false,
                isDeleted = false
            )
            memoDao.insertMemo(updatedMemo)
            if (tags != null) {
                memoDao.replaceMemoTags(identifier, accountKey, tags)
            }

            if (resources != null) {
                val existingResources = memoDao.getMemoResources(identifier, accountKey)
                val incomingIds = resources.mapTo(hashSetOf()) { it.identifier }
                existingResources.forEach { existing ->
                    if (existing.identifier !in incomingIds) {
                        deleteLocalFile(existing)
                        memoDao.deleteResource(existing)
                    }
                }
                resources.forEach { resource ->
                    memoDao.insertResource(
                        resource.copy(
                            accountKey = accountKey,
                            memoId = identifier
                        )
                    )
                }
            }
            notifyMemoRelationsChanged(updatedMemo)

            ApiResponse.Success(withResources(updatedMemo))
        } catch (e: Exception) {
            ApiResponse.Failure.Exception(e)
        }
    }

    override suspend fun deleteMemo(identifier: String): ApiResponse<Unit> {
        return try {
            val memo = memoDao.getMemoById(identifier, accountKey)
                ?: return ApiResponse.Failure.Exception(Exception(R.string.memo_not_found.string))
            memoDao.getMemoResources(identifier, accountKey).forEach { resource ->
                deleteLocalFile(resource)
                memoDao.deleteResource(resource)
            }
            memoDao.deleteMemo(memo)
            memoDao.pruneUnusedTags(accountKey)
            ApiResponse.Success(Unit)
        } catch (e: Exception) {
            ApiResponse.Failure.Exception(e)
        }
    }

    override suspend fun archiveMemo(identifier: String): ApiResponse<Unit> {
        return try {
            val memo = memoDao.getMemoById(identifier, accountKey)
                ?: return ApiResponse.Failure.Exception(Exception(R.string.memo_not_found.string))
            val now = Instant.now()
            memoDao.insertMemo(
                memo.copy(
                    archived = true,
                    needsSync = false,
                    lastModified = now,
                    lastSyncedAt = now
                )
            )
            ApiResponse.Success(Unit)
        } catch (e: Exception) {
            ApiResponse.Failure.Exception(e)
        }
    }

    override suspend fun restoreMemo(identifier: String): ApiResponse<Unit> {
        return try {
            val memo = memoDao.getMemoById(identifier, accountKey)
                ?: return ApiResponse.Failure.Exception(Exception(R.string.memo_not_found.string))
            val now = Instant.now()
            memoDao.insertMemo(
                memo.copy(
                    archived = false,
                    needsSync = false,
                    lastModified = now,
                    lastSyncedAt = now
                )
            )
            ApiResponse.Success(Unit)
        } catch (e: Exception) {
            ApiResponse.Failure.Exception(e)
        }
    }

    override suspend fun listTags(): ApiResponse<List<String>> {
        return try {
            val tags = memoDao.listTagsByRecentUsage(
                accountKey = accountKey,
                since = recentTagUsageSince
            )
            ApiResponse.Success(tags)
        } catch (e: Exception) {
            ApiResponse.Failure.Exception(e)
        }
    }

    override suspend fun renameTag(oldTag: String, newTag: String): ApiResponse<Unit> {
        return try {
            val normalizedOldTag = normalizeTagName(oldTag)
            val normalizedNewTag = normalizeTagName(newTag)
            if (normalizedOldTag.isEmpty() || normalizedNewTag.isEmpty()) {
                return ApiResponse.Failure.Exception(IllegalArgumentException(R.string.tag_name_empty.string))
            }
            if (!isValidTagName(normalizedNewTag)) {
                return ApiResponse.Failure.Exception(IllegalArgumentException(R.string.invalid_tag_name.string))
            }
            if (normalizedOldTag == normalizedNewTag) {
                return ApiResponse.Success(Unit)
            }

            val now = Instant.now()
            val memoIds = memoDao.listMemoIdsByTagPrefix(
                accountKey = accountKey,
                tag = normalizedOldTag,
                tagPrefix = "$normalizedOldTag/%"
            )

            memoIds.forEach { memoId ->
                val memo = memoDao.getMemoById(memoId, accountKey) ?: return@forEach
                val updatedTags = memoDao.getMemoTags(memoId, accountKey)
                    .map { renameTagWithPrefix(it, normalizedOldTag, normalizedNewTag) }
                    .distinct()
                memoDao.replaceMemoTags(memoId, accountKey, updatedTags)
                memoDao.insertMemo(
                    memo.copy(
                        lastModified = now,
                        lastSyncedAt = now,
                        needsSync = false,
                        isDeleted = false
                    )
                )
            }
            memoDao.pruneUnusedTags(accountKey)
            ApiResponse.Success(Unit)
        } catch (e: Exception) {
            ApiResponse.Failure.Exception(e)
        }
    }

    override suspend fun deleteTag(tag: String, deleteAssociatedMemos: Boolean): ApiResponse<Unit> {
        return try {
            val normalizedTag = normalizeTagName(tag)
            if (normalizedTag.isEmpty()) {
                return ApiResponse.Failure.Exception(IllegalArgumentException(R.string.tag_name_empty.string))
            }

            val memoIds = memoDao.listMemoIdsByTagPrefix(
                accountKey = accountKey,
                tag = normalizedTag,
                tagPrefix = "$normalizedTag/%"
            )

            if (deleteAssociatedMemos) {
                memoIds.forEach { memoId ->
                    val deleteResult = deleteMemo(memoId)
                    if (deleteResult !is ApiResponse.Success) {
                        return deleteResult
                    }
                }
                memoDao.pruneUnusedTags(accountKey)
                return ApiResponse.Success(Unit)
            }

            val now = Instant.now()
            memoIds.forEach { memoId ->
                val memo = memoDao.getMemoById(memoId, accountKey) ?: return@forEach
                val updatedTags = memoDao.getMemoTags(memoId, accountKey)
                    .filterNot { matchesTagOrDescendant(it, normalizedTag) }
                memoDao.replaceMemoTags(memoId, accountKey, updatedTags)
                memoDao.insertMemo(
                    memo.copy(
                        lastModified = now,
                        lastSyncedAt = now,
                        needsSync = false,
                        isDeleted = false
                    )
                )
            }
            memoDao.pruneUnusedTags(accountKey)
            ApiResponse.Success(Unit)
        } catch (e: Exception) {
            ApiResponse.Failure.Exception(e)
        }
    }

    override suspend fun listResources(): ApiResponse<List<ResourceEntity>> {
        return try {
            val resources = memoDao.getAllResources(accountKey)
            ApiResponse.Success(resources)
        } catch (e: Exception) {
            ApiResponse.Failure.Exception(e)
        }
    }

    override suspend fun createResource(
        filename: String,
        type: MediaType?,
        sourceUri: Uri,
        memoIdentifier: String?,
        onProgress: (uploadedBytes: Long, totalBytes: Long) -> Unit
    ): ApiResponse<ResourceEntity> {
        return try {
            val uri = fileStorage.savePersistentFileFromUri(
                accountKey = accountKey,
                sourceUri = sourceUri,
                filename = UUID.randomUUID().toString() + "_" + filename,
                onProgress = onProgress
            )
            val thumbnailLocalUri = if (type.isImageMimeType()) {
                fileStorage.saveImageThumbnailFromUri(
                    accountKey = accountKey,
                    sourceUri = sourceUri,
                    filename = "thumb_${UUID.randomUUID()}.jpg"
                )?.toString()
            } else if (type.isVideoMimeType()) {
                fileStorage.saveVideoThumbnailFromUri(
                    accountKey = accountKey,
                    sourceUri = sourceUri,
                    filename = "video_thumb_${UUID.randomUUID()}.jpg"
                )?.toString()
            } else {
                null
            }
            val resource = ResourceEntity(
                identifier = UUID.randomUUID().toString(),
                remoteId = null,
                accountKey = accountKey,
                date = Instant.now(),
                filename = filename,
                uri = uri.toString(),
                localUri = uri.toString(),
                mimeType = type?.toString(),
                thumbnailLocalUri = thumbnailLocalUri,
                memoId = memoIdentifier
            )
            memoDao.insertResource(resource)
            ApiResponse.Success(resource)
        } catch (e: Exception) {
            ApiResponse.Failure.Exception(e)
        }
    }

    override suspend fun cacheResourceThumbnail(identifier: String, downloadedUri: Uri): ApiResponse<Unit> {
        return try {
            val resource = memoDao.getResourceById(identifier, accountKey)
                ?: return ApiResponse.Failure.Exception(Exception(R.string.resource_not_found.string))
            val existing = existingThumbnailLocalFile(resource)
            if (existing != null) {
                return ApiResponse.Success(Unit)
            }

            val canonical = fileStorage.saveThumbnailFromUri(
                accountKey = accountKey,
                sourceUri = downloadedUri,
                filename = buildCachedThumbnailFilename(resource)
            ).toString()

            resource.thumbnailLocalUri
                ?.takeIf { it != canonical }
                ?.toUri()
                ?.takeIf { it.scheme == "file" }
                ?.let(fileStorage::deleteFile)

            memoDao.insertResource(
                resource.copy(thumbnailLocalUri = canonical)
            )
            notifyResourceRelationsChanged(resource)
            ApiResponse.Success(Unit)
        } catch (e: Exception) {
            ApiResponse.Failure.Exception(e)
        }
    }

    override suspend fun getResourceById(identifier: String): ResourceEntity? {
        return memoDao.getResourceById(identifier, accountKey)
            ?: memoDao.getResourceByRemoteId(identifier, accountKey)
    }

        override suspend fun updateResourceThumbnail(identifier: String, thumbnailUri: String): ApiResponse<Unit> {
            return try {
                val resource = memoDao.getResourceById(identifier, accountKey)
                    ?: return ApiResponse.Failure.Exception(Exception(R.string.resource_not_found.string))

                // Delete old thumbnail if exists
                resource.thumbnailLocalUri
                    ?.toUri()
                    ?.takeIf { it.scheme == "file" }
                    ?.let(fileStorage::deleteFile)

                // Update with new thumbnail URI
                memoDao.insertResource(
                    resource.copy(thumbnailLocalUri = thumbnailUri)
                )
                notifyResourceRelationsChanged(resource)
                ApiResponse.Success(Unit)
            } catch (e: Exception) {
                ApiResponse.Failure.Exception(e)
            }
        }

    override suspend fun deleteResource(identifier: String): ApiResponse<Unit> {
        return try {
            val resource = memoDao.getResourceById(identifier, accountKey)
                ?: return ApiResponse.Failure.Exception(Exception(R.string.resource_not_found.string))
            deleteLocalFile(resource)
            memoDao.deleteResource(resource)
            ApiResponse.Success(Unit)
        } catch (e: Exception) {
            ApiResponse.Failure.Exception(e)
        }
    }

    override suspend fun resolveRemoteResourceIds(
        resourceIdentifiers: List<String>,
        encryptionScope: ResourceEncryptionScope
    ): ApiResponse<List<String>> {
        return try {
            val resolved = resourceIdentifiers
                .mapNotNull { identifier ->
                    val resource = memoDao.getResourceById(identifier, accountKey)
                        ?: memoDao.getResourceByRemoteId(identifier, accountKey)
                    resource?.remoteId ?: identifier.trim().takeIf { it.isNotEmpty() }
                }
                .distinct()
            ApiResponse.Success(resolved)
        } catch (e: Exception) {
            ApiResponse.Failure.Exception(e)
        }
    }

    override suspend fun getCurrentUser(): ApiResponse<User> {
        return ApiResponse.Success(account.toUser())
    }

    private suspend fun withResources(memo: MemoEntity): MemoEntity {
        val resources = memoDao.getMemoResources(memo.identifier, accountKey)
        val tags = memoDao.getMemoTags(memo.identifier, accountKey)
        return memo.copy().also {
            it.resources = resources
            it.tags = tags
        }
    }

    private fun deleteLocalFile(resource: ResourceEntity) {
        val local = resource.localUri ?: resource.uri
        val localUri = local.toUri()
        if (localUri.scheme == "file") {
            fileStorage.deleteFile(localUri)
        }
        val thumbnailLocalUri = resource.thumbnailLocalUri?.toUri()
        if (thumbnailLocalUri?.scheme == "file") {
            fileStorage.deleteFile(thumbnailLocalUri)
        }
    }

    private fun existingThumbnailLocalFile(resource: ResourceEntity): File? {
        val local = resource.thumbnailLocalUri ?: return null
        val uri = local.toUri()
        if (uri.scheme != "file") {
            return null
        }
        val file = uri.path?.let(::File)?.takeIf { it.exists() } ?: return null
        return file.takeUnless(::isLegacyVideoNamedThumbnail)
    }

    private fun buildCachedThumbnailFilename(resource: ResourceEntity): String {
        val sanitizedIdentifier = resource.identifier.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return "thumb_${sanitizedIdentifier}.jpg"
    }

    private fun isLegacyVideoNamedThumbnail(file: File): Boolean {
        return file.extension.lowercase() in setOf("mp4", "mov", "m4v", "webm", "mkv", "avi", "3gp", "mpeg", "mpg")
    }

    private suspend fun notifyMemoRelationsChanged(memo: MemoEntity) {
        memoDao.insertMemo(memo.copy())
    }

    private suspend fun notifyResourceRelationsChanged(resource: ResourceEntity) {
        val memoId = resource.memoId ?: return
        val memo = memoDao.getMemoById(memoId, accountKey) ?: return
        notifyMemoRelationsChanged(memo)
    }

}

private fun MediaType?.isImageMimeType(): Boolean {
    return this?.type.equals("image", ignoreCase = true)
}

private fun MediaType?.isVideoMimeType(): Boolean {
    return this?.type.equals("video", ignoreCase = true)
}

private fun renameTagWithPrefix(tag: String, oldPrefix: String, newPrefix: String): String {
    return when {
        tag == oldPrefix -> newPrefix
        tag.startsWith("$oldPrefix/") -> "$newPrefix/${tag.removePrefix("$oldPrefix/")}"
        else -> tag
    }
}

private fun matchesTagOrDescendant(tag: String, rootTag: String): Boolean {
    return tag == rootTag || tag.startsWith("$rootTag/")
}
