package site.lcyk.keer.data.repository

import android.net.Uri
import androidx.core.net.toUri
import com.skydoves.sandwich.ApiResponse
import com.skydoves.sandwich.StatusCode
import com.skydoves.sandwich.getOrNull
import com.skydoves.sandwich.retrofit.statusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import site.lcyk.keer.data.local.FileStorage
import site.lcyk.keer.data.local.dao.MemoDao
import site.lcyk.keer.data.local.entity.MemoEntity
import site.lcyk.keer.data.local.entity.MemoWithResources
import site.lcyk.keer.data.local.entity.ResourceEntity
import site.lcyk.keer.data.constant.KeerException
import site.lcyk.keer.data.model.Account
import site.lcyk.keer.data.model.Memo
import site.lcyk.keer.data.model.MemoVisibility
import site.lcyk.keer.data.model.Resource
import site.lcyk.keer.data.model.SyncStatus
import site.lcyk.keer.data.model.User
import site.lcyk.keer.ext.getErrorMessage
import site.lcyk.keer.util.extractCustomTags
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import java.io.File
import java.time.Instant
import java.util.UUID

class SyncingRepository(
    private val memoDao: MemoDao,
    private val fileStorage: FileStorage,
    private val remoteRepository: RemoteRepository,
    private val account: Account,
    private val onUserSynced: suspend (User) -> Unit = {},
) : AbstractMemoRepository() {
    private data class UploadedResourcesResult(
        val remoteResourceIds: List<String>,
        val failedUploads: Int
    )

    private data class PendingUpload(
        val resource: ResourceEntity,
        val file: File?,
        val sizeBytes: Long
    )

    private val accountKey = account.accountKey()
    private var currentUser: User = account.toUser()
    private val operationMutex = Mutex()
    private val operationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pendingDetailedSyncError: String? = null
    private val _syncStatus = MutableStateFlow(SyncStatus())
    override val syncStatus: StateFlow<SyncStatus> = _syncStatus.asStateFlow()

    init {
        operationScope.launch {
            refreshUnsyncedCount()
        }
    }

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
            val memos = memoDao.getArchivedMemos(accountKey)
                .filterNot { it.isDeleted }
                .map { withResources(it) }
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
            val localMemo = MemoEntity(
                identifier = UUID.randomUUID().toString(),
                remoteId = null,
                accountKey = accountKey,
                content = content,
                date = now,
                visibility = visibility,
                pinned = false,
                archived = false,
                needsSync = true,
                isDeleted = false,
                lastModified = now,
                lastSyncedAt = null
            )
            memoDao.insertMemo(localMemo)

            resources.forEach { resource ->
                memoDao.insertResource(
                    resource.copy(
                        accountKey = accountKey,
                        memoId = localMemo.identifier
                    )
                )
            }

            refreshUnsyncedCount()
            enqueuePushMemo(localMemo.identifier)
            ApiResponse.Success(withResources(localMemo))
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

            val updatedMemo = existingMemo.copy(
                content = content ?: existingMemo.content,
                visibility = visibility ?: existingMemo.visibility,
                pinned = pinned ?: existingMemo.pinned,
                needsSync = true,
                isDeleted = false,
                lastModified = Instant.now()
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

            refreshUnsyncedCount()
            enqueuePushMemo(updatedMemo.identifier)
            ApiResponse.Success(withResources(updatedMemo))
        } catch (e: Exception) {
            ApiResponse.Failure.Exception(e)
        }
    }

    override suspend fun deleteMemo(identifier: String): ApiResponse<Unit> {
        return try {
            val memo = memoDao.getMemoById(identifier, accountKey)
                ?: return ApiResponse.Failure.Exception(Exception("Memo not found"))
            memoDao.insertMemo(
                memo.copy(
                    isDeleted = true,
                    needsSync = true,
                    lastModified = Instant.now()
                )
            )
            refreshUnsyncedCount()
            enqueuePushMemo(identifier)
            ApiResponse.Success(Unit)
        } catch (e: Exception) {
            ApiResponse.Failure.Exception(e)
        }
    }

    override suspend fun archiveMemo(identifier: String): ApiResponse<Unit> {
        return try {
            val memo = memoDao.getMemoById(identifier, accountKey)
                ?: return ApiResponse.Failure.Exception(Exception("Memo not found"))
            memoDao.insertMemo(
                memo.copy(
                    archived = true,
                    needsSync = true,
                    lastModified = Instant.now()
                )
            )
            refreshUnsyncedCount()
            enqueuePushMemo(identifier)
            ApiResponse.Success(Unit)
        } catch (e: Exception) {
            ApiResponse.Failure.Exception(e)
        }
    }

    override suspend fun restoreMemo(identifier: String): ApiResponse<Unit> {
        return try {
            val memo = memoDao.getMemoById(identifier, accountKey)
                ?: return ApiResponse.Failure.Exception(Exception("Memo not found"))
            memoDao.insertMemo(
                memo.copy(
                    archived = false,
                    needsSync = true,
                    lastModified = Instant.now()
                )
            )
            refreshUnsyncedCount()
            enqueuePushMemo(identifier)
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
            val localUri = fileStorage.savePersistentFileFromUri(
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
                uri = localUri.toString(),
                localUri = localUri.toString(),
                mimeType = type?.toString(),
                thumbnailLocalUri = thumbnailLocalUri,
                memoId = memoIdentifier
            )
            memoDao.insertResource(resource)
            refreshUnsyncedCount()
            ApiResponse.Success(resource)
        } catch (e: Exception) {
            ApiResponse.Failure.Exception(e)
        }
    }

    override suspend fun deleteResource(identifier: String): ApiResponse<Unit> {
        return try {
            val resource = memoDao.getResourceById(identifier, accountKey)
                ?: return ApiResponse.Failure.Exception(Exception("Resource not found"))

            val memoId = resource.memoId
            deleteLocalFile(resource)
            memoDao.deleteResource(resource)

            if (!memoId.isNullOrBlank()) {
                memoDao.getMemoById(memoId, accountKey)?.let { memo ->
                    memoDao.insertMemo(
                        memo.copy(
                            needsSync = true,
                            lastModified = Instant.now()
                        )
                    )
                }
            }

            resource.remoteId?.let { remoteId ->
                enqueueDeleteRemoteResource(remoteId)
            }
            if (!memoId.isNullOrBlank()) {
                enqueuePushMemo(memoId)
            }
            refreshUnsyncedCount()
            ApiResponse.Success(Unit)
        } catch (e: Exception) {
            ApiResponse.Failure.Exception(e)
        }
    }

    override suspend fun cacheResourceFile(identifier: String, downloadedUri: Uri): ApiResponse<Unit> {
        return try {
            val resource = memoDao.getResourceById(identifier, accountKey)
                ?: return ApiResponse.Failure.Exception(Exception("Resource not found"))
            val existingLocal = existingLocalUri(resource)
            if (existingLocal != null) {
                return ApiResponse.Success(Unit)
            }

            val canonical = fileStorage.saveFileFromUri(
                accountKey = accountKey,
                sourceUri = downloadedUri,
                filename = "${resource.identifier}_${resource.filename}"
            ).toString()

            resource.localUri?.takeIf { it != canonical }?.let { oldLocal ->
                val oldUri = oldLocal.toUri()
                if (oldUri.scheme == "file") {
                    fileStorage.deleteFile(oldUri)
                }
            }

            val updatedUri = if (resource.remoteId == null && resource.uri.toUri().scheme == "file") {
                canonical
            } else {
                resource.uri
            }
            memoDao.insertResource(
                resource.copy(
                    uri = updatedUri,
                    localUri = canonical
                )
            )
            ApiResponse.Success(Unit)
        } catch (e: Exception) {
            ApiResponse.Failure.Exception(e)
        }
    }

    override suspend fun cacheResourceThumbnail(identifier: String, downloadedUri: Uri): ApiResponse<Unit> {
        return try {
            val resource = memoDao.getResourceById(identifier, accountKey)
                ?: return ApiResponse.Failure.Exception(Exception("Resource not found"))
            val existingLocal = existingThumbnailLocalUri(resource)
            if (existingLocal != null) {
                return ApiResponse.Success(Unit)
            }

            val canonical = fileStorage.saveThumbnailFromUri(
                accountKey = accountKey,
                sourceUri = downloadedUri,
                filename = "thumb_${resource.identifier}_${resource.filename}"
            ).toString()

            resource.thumbnailLocalUri?.takeIf { it != canonical }?.let { oldLocal ->
                val oldUri = oldLocal.toUri()
                if (oldUri.scheme == "file") {
                    fileStorage.deleteFile(oldUri)
                }
            }

            memoDao.insertResource(
                resource.copy(
                    thumbnailLocalUri = canonical
                )
            )
            ApiResponse.Success(Unit)
        } catch (e: Exception) {
            ApiResponse.Failure.Exception(e)
        }
    }

    override suspend fun getCurrentUser(): ApiResponse<User> {
        return ApiResponse.Success(currentUser)
    }

    override suspend fun sync(): ApiResponse<Unit> {
        return operationMutex.withLock {
            setSyncing(true)
            pendingDetailedSyncError = null
            try {
                val result = syncInternal()
                if (result is ApiResponse.Success) {
                    setSyncError(null)
                } else {
                    setSyncError(result.getErrorMessage())
                }
                refreshUnsyncedCount()
                result
            } catch (e: Throwable) {
                val failure = ApiResponse.Failure.Exception(e)
                setSyncError(failure.getErrorMessage())
                refreshUnsyncedCount()
                failure
            } finally {
                setSyncing(false)
            }
        }
    }

    private suspend fun syncInternal(): ApiResponse<Unit> {
        val currentUserSync = refreshCurrentUserFromRemoteStrict()
        if (currentUserSync !is ApiResponse.Success) {
            return currentUserSync
        }

        val remoteNormal = remoteRepository.listMemos()
        if (remoteNormal !is ApiResponse.Success) {
            return remoteNormal.mapFailureToUnit()
        }

        val remoteArchived = remoteRepository.listArchivedMemos()
        if (remoteArchived !is ApiResponse.Success) {
            return remoteArchived.mapFailureToUnit()
        }

        val remoteMemos = remoteNormal.data + remoteArchived.data
        val remoteById = remoteMemos.associateBy { remoteMemoId(it) }

        var hadErrors = false
        var firstErrorMessage: String? = null
        fun recordFailure() {
            hadErrors = true
            if (firstErrorMessage == null) {
                firstErrorMessage = consumeDetailedSyncError()
            }
        }
        val localMemos = memoDao.getAllMemosForSync(accountKey)
        val localByRemoteId = localMemos.mapNotNull { memo ->
            memo.remoteId?.let { it to memo }
        }.toMap()

        for (remoteMemo in remoteMemos) {
            val remoteId = remoteMemoId(remoteMemo)
            val local = localByRemoteId[remoteId]

            if (local == null) {
                applyRemoteMemo(remoteMemo)
                continue
            }

            if (local.isDeleted) {
                if (!local.needsSync) {
                    applyRemoteMemo(remoteMemo, local.identifier)
                    continue
                }

                val remoteChanged = hasRemoteChanged(local, remoteMemo)
                val equivalent = memoEquivalent(local, remoteMemo)
                if (remoteChanged || !equivalent) {
                    applyRemoteMemo(remoteMemo, local.identifier)
                } else {
                    val deleted = remoteRepository.deleteMemo(remoteId)
                    if (deleted is ApiResponse.Success) {
                        permanentlyDeleteMemo(local.identifier)
                    } else {
                        recordFailure()
                    }
                }
                continue
            }

            val equivalent = memoEquivalent(local, remoteMemo)
            if (equivalent) {
                markSynced(local, remoteMemo)
                continue
            }

            val localChanged = local.needsSync
            val remoteChanged = hasRemoteChanged(local, remoteMemo)

            when {
                !localChanged -> applyRemoteMemo(remoteMemo, local.identifier)
                !remoteChanged -> {
                    if (!pushLocalMemo(local.identifier)) {
                        recordFailure()
                    }
                }
                else -> {
                    if (!duplicateConflict(local, remoteMemo)) {
                        recordFailure()
                    }
                }
            }
        }

        val latestLocals = memoDao.getAllMemosForSync(accountKey)
        for (local in latestLocals) {
            if (local.remoteId != null && remoteById.containsKey(local.remoteId)) {
                continue
            }

            if (local.remoteId != null && !remoteById.containsKey(local.remoteId)) {
                if (local.isDeleted) {
                    permanentlyDeleteMemo(local.identifier)
                } else if (local.needsSync) {
                    if (!pushLocalMemo(local.identifier, forceCreate = true)) {
                        recordFailure()
                    }
                } else {
                    permanentlyDeleteMemo(local.identifier)
                }
                continue
            }

            if (local.remoteId == null) {
                if (local.isDeleted) {
                    permanentlyDeleteMemo(local.identifier)
                } else if (local.needsSync) {
                    if (!pushLocalMemo(local.identifier, forceCreate = true)) {
                        recordFailure()
                    }
                }
            }
        }

        return if (hadErrors) {
            ApiResponse.Failure.Exception(
                Exception(firstErrorMessage ?: "Sync finished with partial failures")
            )
        } else {
            ApiResponse.Success(Unit)
        }
    }

    private suspend fun refreshCurrentUserFromRemoteStrict(): ApiResponse<Unit> {
        val remoteUser = try {
            remoteRepository.getCurrentUser()
        } catch (e: Throwable) {
            return ApiResponse.Failure.Exception(e)
        }

        return when (remoteUser) {
            is ApiResponse.Success -> {
                currentUser = remoteUser.data
                try {
                    onUserSynced(remoteUser.data)
                    ApiResponse.Success(Unit)
                } catch (e: Throwable) {
                    ApiResponse.Failure.Exception(e)
                }
            }
            is ApiResponse.Failure.Error -> {
                if (remoteUser.statusCode == StatusCode.Forbidden || remoteUser.statusCode == StatusCode.Unauthorized) {
                    ApiResponse.Failure.Exception(KeerException.accessTokenInvalid)
                } else {
                    remoteUser.mapFailureToUnit()
                }
            }
            is ApiResponse.Failure.Exception -> remoteUser.mapFailureToUnit()
        }
    }

    private suspend fun pushLocalMemo(identifier: String, forceCreate: Boolean = false): Boolean {
        pendingDetailedSyncError = null
        val local = memoDao.getMemoById(identifier, accountKey) ?: return true

        if (local.isDeleted) {
            return if (local.remoteId != null) {
                val deleted = remoteRepository.deleteMemo(local.remoteId)
                if (deleted is ApiResponse.Success) {
                    permanentlyDeleteMemo(local.identifier)
                    true
                } else {
                    false
                }
            } else {
                permanentlyDeleteMemo(local.identifier)
                true
            }
        }

        val uploadedResources = ensureUploadedResources(local)
        if (uploadedResources.failedUploads > 0) {
            pendingDetailedSyncError = ATTACHMENT_UPLOAD_FAILED_MESSAGE
            return false
        }
        val remoteResourceIds = uploadedResources.remoteResourceIds

        return if (!forceCreate && local.remoteId != null) {
            val updated = remoteRepository.updateMemo(
                remoteId = local.remoteId,
                content = local.content,
                resourceRemoteIds = remoteResourceIds,
                visibility = local.visibility,
                pinned = local.pinned,
                archived = local.archived
            )
            if (updated is ApiResponse.Success) {
                reconcileServerCreatedMemo(
                    local.identifier,
                    updated.data.copy(archived = local.archived)
                )
                true
            } else {
                false
            }
        } else {
            val created = remoteRepository.createMemo(
                content = local.content,
                visibility = local.visibility,
                resourceRemoteIds = remoteResourceIds,
                tags = null,
                createdAt = local.date
            )
            if (created !is ApiResponse.Success) {
                return false
            }

            val createdRemoteId = remoteMemoId(created.data)

            reconcileServerCreatedMemo(
                local.identifier,
                created.data.copy(
                    remoteId = createdRemoteId,
                )
            )
            true
        }
    }

    private suspend fun duplicateConflict(local: MemoEntity, remoteMemo: Memo): Boolean {
        val duplicateLocal = local.copy(
            identifier = UUID.randomUUID().toString(),
            remoteId = null,
            needsSync = true,
            isDeleted = false,
            lastSyncedAt = null,
            lastModified = Instant.now()
        )

        memoDao.insertMemo(duplicateLocal)
        memoDao.getMemoResources(local.identifier, accountKey).forEach { resource ->
            memoDao.insertResource(
                resource.copy(
                    identifier = UUID.randomUUID().toString(),
                    memoId = duplicateLocal.identifier
                )
            )
        }

        applyRemoteMemo(remoteMemo, local.identifier)
        return pushLocalMemo(duplicateLocal.identifier, forceCreate = true)
    }

    private suspend fun reconcileServerCreatedMemo(localIdentifier: String, remoteMemo: Memo) {
        applyRemoteMemo(remoteMemo, preferredLocalIdentifier = localIdentifier)
    }

    private suspend fun ensureUploadedResources(localMemo: MemoEntity): UploadedResourcesResult {
        val resources = memoDao.getMemoResources(localMemo.identifier, accountKey)
        val uploaded = arrayListOf<String>()
        var failedUploads = 0
        val pendingUploads = resources
            .filter { it.remoteId == null }
            .map { resource ->
                val file = resolveLocalFile(resource)
                PendingUpload(
                    resource = resource,
                    file = file,
                    sizeBytes = file?.length()?.takeIf { it > 0L } ?: 0L
                )
            }
            .associateBy { it.resource.identifier }

        setUploadProgressTotals(
            totalBytes = pendingUploads.values.sumOf { it.sizeBytes },
            totalFiles = pendingUploads.size
        )

        var committedBytes = 0L
        var completedFiles = 0

        for (resource in resources) {
            if (resource.remoteId != null) {
                uploaded.add(resource.remoteId)
                continue
            }

            val pending = pendingUploads[resource.identifier]
            val fileSize = pending?.sizeBytes ?: 0L
            val ensured = ensureUploadedResource(
                resource = resource,
                memoRemoteId = localMemo.remoteId,
                localFile = pending?.file
            ) { uploadedBytes, _ ->
                val uploadedForCurrentFile = if (fileSize > 0L) {
                    uploadedBytes.coerceIn(0L, fileSize)
                } else {
                    0L
                }
                updateUploadProgress(
                    uploadedBytes = committedBytes + uploadedForCurrentFile,
                    uploadedFiles = completedFiles
                )
            }
            if (ensured?.remoteId != null) {
                uploaded.add(ensured.remoteId)
            } else {
                failedUploads += 1
            }

            committedBytes += fileSize
            completedFiles += 1
            updateUploadProgress(
                uploadedBytes = committedBytes,
                uploadedFiles = completedFiles
            )
        }

        return UploadedResourcesResult(uploaded, failedUploads)
    }

    private suspend fun ensureUploadedResource(
        resource: ResourceEntity,
        memoRemoteId: String?,
        localFile: File? = null,
        onProgress: (uploadedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> }
    ): ResourceEntity? {
        if (resource.remoteId != null) {
            return resource
        }

        val file = localFile ?: resolveLocalFile(resource) ?: return null

        val uploaded = remoteRepository.createResource(
            filename = resource.filename,
            type = resource.mimeType?.toMediaTypeOrNull(),
            file = file,
            memoRemoteId = memoRemoteId,
            onProgress = onProgress
        )

        val remoteResource = uploaded.getOrNull() ?: return null
        val cachedLocalUri = moveLocalFileToCache(resource, file)
        val synced = resource.copy(
            remoteId = remoteResourceId(remoteResource),
            uri = remoteResource.uri,
            localUri = cachedLocalUri ?: resource.localUri ?: resource.uri,
            thumbnailUri = remoteResource.thumbnailUri ?: resource.thumbnailUri
        )
        memoDao.insertResource(synced)
        return synced
    }

    private suspend fun applyRemoteMemo(
        remoteMemo: Memo,
        preferredLocalIdentifier: String? = null
    ) {
        val remoteId = remoteMemoId(remoteMemo)
        val current = memoDao.getMemoByRemoteId(remoteId, accountKey)
            ?: preferredLocalIdentifier?.let { memoDao.getMemoById(it, accountKey) }

        val localIdentifier = current?.identifier ?: UUID.randomUUID().toString()
        val remoteUpdatedAt = remoteMemo.updatedAt ?: remoteMemo.date

        memoDao.insertMemo(
            MemoEntity(
                identifier = localIdentifier,
                remoteId = remoteId,
                accountKey = accountKey,
                content = remoteMemo.content,
                date = remoteMemo.date,
                visibility = remoteMemo.visibility,
                pinned = remoteMemo.pinned,
                archived = remoteMemo.archived,
                needsSync = false,
                isDeleted = false,
                lastModified = remoteUpdatedAt,
                lastSyncedAt = remoteUpdatedAt
            )
        )

        val currentResources = memoDao.getMemoResources(localIdentifier, accountKey)
        val remoteResourceIds = remoteMemo.resources.mapTo(hashSetOf()) { remoteResourceId(it) }
        currentResources.forEach { currentResource ->
            if (currentResource.remoteId !in remoteResourceIds) {
                deleteLocalFile(currentResource)
                memoDao.deleteResource(currentResource)
            }
        }

        remoteMemo.resources.forEach { resource ->
            val remoteResourceId = remoteResourceId(resource)
            val existing = currentResources.firstOrNull { it.remoteId == remoteResourceId }
            val localResourceIdentifier = existing?.identifier ?: UUID.randomUUID().toString()
            val preferredLocalUri = when {
                existing?.localUri != null && File(existing.localUri.toUri().path ?: "").exists() -> existing.localUri
                existing != null && existing.uri.toUri().scheme == "file" && File(existing.uri.toUri().path ?: "").exists() -> existing.uri
                else -> null
            }
            val preferredThumbnailLocalUri = existing?.thumbnailLocalUri
                ?.takeIf { local ->
                    val localUri = local.toUri()
                    localUri.scheme == "file" && File(localUri.path ?: "").exists()
                }
            memoDao.insertResource(
                ResourceEntity(
                    identifier = localResourceIdentifier,
                    remoteId = remoteResourceId,
                    accountKey = accountKey,
                    date = resource.date,
                    filename = resource.filename,
                    uri = resource.uri,
                    localUri = preferredLocalUri,
                    mimeType = resource.mimeType,
                    thumbnailUri = resource.thumbnailUri,
                    thumbnailLocalUri = preferredThumbnailLocalUri,
                    memoId = localIdentifier
                )
            )
        }
    }

    private suspend fun markSynced(local: MemoEntity, remoteMemo: Memo) {
        memoDao.insertMemo(
            local.copy(
                remoteId = remoteMemoId(remoteMemo),
                needsSync = false,
                isDeleted = false,
                archived = remoteMemo.archived,
                lastSyncedAt = remoteMemo.updatedAt ?: remoteMemo.date
            )
        )
    }

    private suspend fun memoEquivalent(local: MemoEntity, remote: Memo): Boolean {
        if (local.content != remote.content) {
            return false
        }
        if (local.pinned != remote.pinned) {
            return false
        }
        if (local.visibility != remote.visibility) {
            return false
        }
        if (local.archived != remote.archived) {
            return false
        }

        val localResources = memoDao.getMemoResources(local.identifier, accountKey)
        return resourceEntitySignature(localResources) == resourceModelSignature(remote.resources)
    }

    private fun hasRemoteChanged(local: MemoEntity, remote: Memo): Boolean {
        val remoteUpdatedAt = remote.updatedAt ?: remote.date
        val lastSyncedAt = local.lastSyncedAt ?: return true
        return remoteUpdatedAt != lastSyncedAt
    }

    private fun resourceEntitySignature(resources: List<ResourceEntity>): List<String> {
        return resources.map { resource ->
            resource.remoteId ?: "local:${resource.localUri ?: resource.uri}"
        }.sorted()
    }

    private fun resourceModelSignature(resources: List<Resource>): List<String> {
        return resources.map { resource ->
            resource.remoteId
        }.sorted()
    }

    private suspend fun pushLocalResource(identifier: String): Boolean {
        pendingDetailedSyncError = null
        val local = memoDao.getResourceById(identifier, accountKey) ?: return true
        val localFile = resolveLocalFile(local)
        val totalBytes = localFile?.length()?.takeIf { it > 0L } ?: 0L
        setUploadProgressTotals(totalBytes = totalBytes, totalFiles = 1)
        val ensured = ensureUploadedResource(
            resource = local,
            memoRemoteId = null,
            localFile = localFile
        ) { uploadedBytes, _ ->
            val bounded = if (totalBytes > 0L) {
                uploadedBytes.coerceIn(0L, totalBytes)
            } else {
                0L
            }
            updateUploadProgress(
                uploadedBytes = bounded,
                uploadedFiles = 0
            )
        }
            ?: run {
                pendingDetailedSyncError = ATTACHMENT_UPLOAD_FAILED_MESSAGE
                updateUploadProgress(
                    uploadedBytes = totalBytes,
                    uploadedFiles = 1
                )
                return false
            }
        updateUploadProgress(
            uploadedBytes = totalBytes,
            uploadedFiles = 1
        )
        return ensured.remoteId != null
    }

    private fun enqueuePushMemo(identifier: String, forceCreate: Boolean = false) {
        enqueueOperation("Failed to sync memo") {
            pushLocalMemo(identifier, forceCreate)
        }
    }

    private fun enqueuePushResource(identifier: String) {
        enqueueOperation("Failed to sync resource") {
            pushLocalResource(identifier)
        }
    }

    private fun enqueueDeleteRemoteResource(remoteId: String) {
        enqueueOperation("Failed to delete resource on server") {
            remoteRepository.deleteResource(remoteId) is ApiResponse.Success
        }
    }

    private fun enqueueOperation(
        defaultErrorMessage: String,
        block: suspend () -> Boolean
    ) {
        operationScope.launch {
            operationMutex.withLock {
                setSyncing(true)
                try {
                    val success = block()
                    if (success) {
                        setSyncError(null)
                    } else {
                        setSyncError(consumeDetailedSyncError() ?: defaultErrorMessage)
                    }
                } catch (e: Throwable) {
                    setSyncError(e.localizedMessage ?: defaultErrorMessage)
                } finally {
                    refreshUnsyncedCount()
                    setSyncing(false)
                }
            }
        }
    }

    private suspend fun refreshUnsyncedCount() {
        val count = memoDao.countUnsyncedMemos(accountKey)
        _syncStatus.update { it.copy(unsyncedCount = count) }
    }

    private fun setSyncing(syncing: Boolean) {
        _syncStatus.update {
            it.copy(
                syncing = syncing,
                uploadedBytes = 0L,
                totalBytes = 0L,
                uploadedFiles = 0,
                totalFiles = 0
            )
        }
    }

    private fun setUploadProgressTotals(totalBytes: Long, totalFiles: Int) {
        _syncStatus.update {
            it.copy(
                uploadedBytes = 0L,
                totalBytes = totalBytes.coerceAtLeast(0L),
                uploadedFiles = 0,
                totalFiles = totalFiles.coerceAtLeast(0)
            )
        }
    }

    private fun updateUploadProgress(uploadedBytes: Long, uploadedFiles: Int) {
        _syncStatus.update { status ->
            val safeUploadedBytes = if (status.totalBytes > 0L) {
                uploadedBytes.coerceIn(0L, status.totalBytes)
            } else {
                0L
            }
            val safeUploadedFiles = if (status.totalFiles > 0) {
                uploadedFiles.coerceIn(0, status.totalFiles)
            } else {
                0
            }
            status.copy(
                uploadedBytes = safeUploadedBytes,
                uploadedFiles = safeUploadedFiles
            )
        }
    }

    private fun setSyncError(message: String?) {
        _syncStatus.update { it.copy(errorMessage = message) }
    }

    private fun consumeDetailedSyncError(): String? {
        val message = pendingDetailedSyncError
        pendingDetailedSyncError = null
        return message
    }

    private suspend fun withResources(memo: MemoEntity): MemoEntity {
        val resources = memoDao.getMemoResources(memo.identifier, accountKey)
        return memo.copy().also { it.resources = resources }
    }

    private suspend fun permanentlyDeleteMemo(identifier: String) {
        memoDao.getMemoById(identifier, accountKey)?.let { memo ->
            memoDao.getMemoResources(identifier, accountKey).forEach { resource ->
                deleteLocalFile(resource)
            }
            memoDao.deleteMemo(memo)
        }
    }

    private fun resolveLocalFile(resource: ResourceEntity): File? {
        val uri = (resource.localUri ?: resource.uri).toUri()
        if (uri.scheme != "file") {
            return null
        }
        val path = uri.path ?: return null
        val file = File(path)
        return if (file.exists()) file else null
    }

    private fun existingLocalUri(resource: ResourceEntity): Uri? {
        val local = resource.localUri ?: return null
        val uri = local.toUri()
        return if (uri.scheme == "file" && File(uri.path ?: "").exists()) uri else null
    }

    private fun existingThumbnailLocalUri(resource: ResourceEntity): Uri? {
        val local = resource.thumbnailLocalUri ?: return null
        val uri = local.toUri()
        return if (uri.scheme == "file" && File(uri.path ?: "").exists()) uri else null
    }

    private fun moveLocalFileToCache(resource: ResourceEntity, sourceFile: File): String? {
        if (!sourceFile.exists()) {
            return null
        }
        return try {
            val localPath = resource.localUri ?: resource.uri
            val localUri = localPath.toUri()
            val cachedUri = fileStorage.copyFileToCache(
                accountKey = accountKey,
                sourceFile = sourceFile,
                filename = "${resource.identifier}_${resource.filename}"
            )
            if (localUri.scheme == "file" && localUri.path != cachedUri.path) {
                fileStorage.deleteFile(localUri)
            }
            cachedUri.toString()
        } catch (_: Exception) {
            null
        }
    }

    private fun deleteLocalFile(resource: ResourceEntity) {
        val uri = resource.localUri?.toUri()
            ?: resource.uri.toUri().takeIf { it.scheme == "file" }
        if (uri != null) {
            fileStorage.deleteFile(uri)
        }
        val thumbnailUri = resource.thumbnailLocalUri?.toUri()
        if (thumbnailUri?.scheme == "file") {
            fileStorage.deleteFile(thumbnailUri)
        }
    }

    private fun remoteMemoId(memo: Memo): String {
        return memo.remoteId.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("RemoteRepository must return memos with non-empty remoteId")
    }

    private fun remoteResourceId(resource: Resource): String {
        return resource.remoteId.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("RemoteRepository must return resources with non-empty remoteId")
    }

    private fun <T> ApiResponse<T>.mapFailureToUnit(): ApiResponse<Unit> {
        return when (this) {
            is ApiResponse.Success -> ApiResponse.Success(Unit)
            is ApiResponse.Failure.Error -> ApiResponse.Failure.Error(this.payload)
            is ApiResponse.Failure.Exception -> ApiResponse.Failure.Exception(this.throwable)
        }
    }

    override fun close() {
        operationScope.cancel()
    }

    companion object {
        private const val ATTACHMENT_UPLOAD_FAILED_MESSAGE =
            "Failed to upload one or more attachments during sync"
    }

}

private fun MediaType?.isImageMimeType(): Boolean {
    return this?.type.equals("image", ignoreCase = true)
}

private fun MemoWithResources.toMemoEntity(): MemoEntity {
    return memo.copy().also { it.resources = resources }
}
