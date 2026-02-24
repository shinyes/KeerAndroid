package site.lcyk.keer.data.repository

import android.net.Uri
import androidx.core.net.toUri
import com.skydoves.sandwich.ApiResponse
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import site.lcyk.keer.data.local.FileStorage
import site.lcyk.keer.data.local.dao.MemoDao
import site.lcyk.keer.data.local.entity.MemoEntity
import site.lcyk.keer.data.local.entity.MemoWithResources
import site.lcyk.keer.data.local.entity.ResourceEntity
import site.lcyk.keer.data.model.Account
import site.lcyk.keer.data.model.MemoVisibility
import site.lcyk.keer.data.model.User
import site.lcyk.keer.util.extractCustomTags
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

    override fun observeMemos(): Flow<List<MemoEntity>> {
        return memoDao.observeAllMemos(accountKey).map { memos ->
            memos.map { it.toMemoEntity() }
        }
    }

    override suspend fun listMemos(): ApiResponse<List<MemoEntity>> {
        return try {
            val memos = memoDao.getAllMemos(accountKey).map { withResources(it) }
            ApiResponse.Success(memos)
        } catch (e: Exception) {
            ApiResponse.Failure.Exception(e)
        }
    }

    override suspend fun listArchivedMemos(): ApiResponse<List<MemoEntity>> {
        return try {
            val memos = memoDao.getArchivedMemos(accountKey).map { withResources(it) }
            ApiResponse.Success(memos)
        } catch (e: Exception) {
            ApiResponse.Failure.Exception(e)
        }
    }

    override suspend fun createMemo(
        content: String,
        visibility: MemoVisibility,
        resources: List<ResourceEntity>,
        tags: List<String>?
    ): ApiResponse<MemoEntity> {
        return try {
            val now = Instant.now()
            val memo = MemoEntity(
                identifier = UUID.randomUUID().toString(),
                accountKey = accountKey,
                content = content,
                date = now,
                visibility = visibility,
                pinned = false,
                archived = false,
                needsSync = false,
                isDeleted = false,
                lastModified = now,
                lastSyncedAt = now
            )
            memoDao.insertMemo(memo)

            resources.forEach { resource ->
                memoDao.insertResource(
                    resource.copy(
                        accountKey = accountKey,
                        memoId = memo.identifier
                    )
                )
            }

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
                ?: return ApiResponse.Failure.Exception(Exception("Memo not found"))

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

            ApiResponse.Success(withResources(updatedMemo))
        } catch (e: Exception) {
            ApiResponse.Failure.Exception(e)
        }
    }

    override suspend fun deleteMemo(identifier: String): ApiResponse<Unit> {
        return try {
            val memo = memoDao.getMemoById(identifier, accountKey)
                ?: return ApiResponse.Failure.Exception(Exception("Memo not found"))
            memoDao.getMemoResources(identifier, accountKey).forEach { resource ->
                deleteLocalFile(resource)
                memoDao.deleteResource(resource)
            }
            memoDao.deleteMemo(memo)
            ApiResponse.Success(Unit)
        } catch (e: Exception) {
            ApiResponse.Failure.Exception(e)
        }
    }

    override suspend fun archiveMemo(identifier: String): ApiResponse<Unit> {
        return try {
            val memo = memoDao.getMemoById(identifier, accountKey)
                ?: return ApiResponse.Failure.Exception(Exception("Memo not found"))
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
                ?: return ApiResponse.Failure.Exception(Exception("Memo not found"))
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
            val tags = memoDao.getAllMemos(accountKey)
                .asSequence()
                .flatMap { extractCustomTags(it.content).asSequence() }
                .filter { it.isNotBlank() }
                .toSet()
                .sorted()
            ApiResponse.Success(tags)
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
                ?: return ApiResponse.Failure.Exception(Exception("Resource not found"))
            val existing = resource.thumbnailLocalUri
                ?.toUri()
                ?.takeIf { it.scheme == "file" }
                ?.path
                ?.let(::File)
                ?.takeIf { it.exists() }
            if (existing != null) {
                return ApiResponse.Success(Unit)
            }

            val canonical = fileStorage.saveThumbnailFromUri(
                accountKey = accountKey,
                sourceUri = downloadedUri,
                filename = "thumb_${resource.identifier}_${resource.filename}"
            ).toString()

            resource.thumbnailLocalUri
                ?.takeIf { it != canonical }
                ?.toUri()
                ?.takeIf { it.scheme == "file" }
                ?.let(fileStorage::deleteFile)

            memoDao.insertResource(
                resource.copy(thumbnailLocalUri = canonical)
            )
            ApiResponse.Success(Unit)
        } catch (e: Exception) {
            ApiResponse.Failure.Exception(e)
        }
    }

    override suspend fun deleteResource(identifier: String): ApiResponse<Unit> {
        return try {
            val resource = memoDao.getResourceById(identifier, accountKey)
                ?: return ApiResponse.Failure.Exception(Exception("Resource not found"))
            deleteLocalFile(resource)
            memoDao.deleteResource(resource)
            ApiResponse.Success(Unit)
        } catch (e: Exception) {
            ApiResponse.Failure.Exception(e)
        }
    }

    override suspend fun getCurrentUser(): ApiResponse<User> {
        return ApiResponse.Success(account.toUser())
    }

    private suspend fun withResources(memo: MemoEntity): MemoEntity {
        val resources = memoDao.getMemoResources(memo.identifier, accountKey)
        return memo.copy().also { it.resources = resources }
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

}

private fun MediaType?.isImageMimeType(): Boolean {
    return this?.type.equals("image", ignoreCase = true)
}

private fun MemoWithResources.toMemoEntity(): MemoEntity {
    return memo.copy().also { it.resources = resources }
}
