package site.lcyk.keer.data.service

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import com.skydoves.sandwich.ApiResponse
import dagger.hilt.android.qualifiers.ApplicationContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import site.lcyk.keer.R
import site.lcyk.keer.data.local.entity.ResourceEntity
import site.lcyk.keer.data.model.Account
import site.lcyk.keer.data.model.Memo
import site.lcyk.keer.data.model.Resource
import site.lcyk.keer.data.repository.AbstractMemoRepository
import site.lcyk.keer.data.repository.RemoteRepository
import site.lcyk.keer.ext.getErrorMessage
import site.lcyk.keer.ext.string
import site.lcyk.keer.util.normalizeTagList
import java.io.BufferedOutputStream
import java.io.File
import java.math.BigDecimal
import java.security.MessageDigest
import java.time.Instant
import java.util.LinkedHashMap
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

data class MemoExportResult(
    val exportedCount: Int,
    val exportedAttachmentCount: Int,
    val failedCount: Int,
)

data class MemoImportResult(
    val total: Int,
    val imported: Int,
    val failed: Int,
    val skipped: Int,
    val importedAttachmentCount: Int,
)

data class MemoImportPreviewResult(
    val total: Int,
    val estimatedImportable: Int,
    val estimatedSkipped: Int,
)

enum class MemoTransferOperation {
    EXPORT,
    IMPORT,
}

enum class MemoTransferStage {
    PREPARING,
    READING_PACKAGE,
    PROCESSING_MEMOS,
    PROCESSING_ATTACHMENTS,
    WRITING_MANIFEST,
    COMPLETED,
}

data class MemoTransferProgress(
    val operation: MemoTransferOperation,
    val stage: MemoTransferStage,
    val completed: Int? = null,
    val total: Int? = null,
)

@Singleton
class MemoTransferService @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val accountService: AccountService,
) {
    suspend fun exportPersonalMemos(
        destinationUri: Uri,
        onProgress: ((MemoTransferProgress) -> Unit)? = null,
    ): Result<MemoExportResult> = withContext(Dispatchers.IO) {
        runCatching {
            onProgress?.invoke(
                MemoTransferProgress(
                    operation = MemoTransferOperation.EXPORT,
                    stage = MemoTransferStage.PREPARING,
                )
            )
            val remoteRepository = requireRemoteRepository()
            val currentUserId = requireCurrentUserId(remoteRepository)
            val personalMemos = loadPersonalMemos(remoteRepository, currentUserId)
            val source = buildTransferSource(currentUserId)
            val localRepository = accountService.getRepository()
            exportAsZip(
                destinationUri = destinationUri,
                source = source,
                memos = personalMemos,
                localRepository = localRepository,
                onProgress = onProgress,
            )
        }
    }

    suspend fun importPersonalMemos(
        sourceUri: Uri,
        onProgress: ((MemoTransferProgress) -> Unit)? = null,
    ): Result<MemoImportResult> = withContext(Dispatchers.IO) {
        runCatching {
            onProgress?.invoke(
                MemoTransferProgress(
                    operation = MemoTransferOperation.IMPORT,
                    stage = MemoTransferStage.PREPARING,
                )
            )
            val importContext = prepareImportContext()
            if (isZipDocument(sourceUri)) {
                importFromZip(
                    sourceUri = sourceUri,
                    localRepository = importContext.localRepository,
                    dedupStore = importContext.dedupStore,
                    dedupKeys = importContext.dedupKeys,
                    onProgress = onProgress,
                )
            } else {
                importFromJson(
                    sourceUri = sourceUri,
                    localRepository = importContext.localRepository,
                    dedupStore = importContext.dedupStore,
                    dedupKeys = importContext.dedupKeys,
                    onProgress = onProgress,
                )
            }
        }
    }

    suspend fun previewPersonalMemoImport(sourceUri: Uri): Result<MemoImportPreviewResult> =
        withContext(Dispatchers.IO) {
            runCatching {
                val importContext = prepareImportContext()
                val entries = if (isZipDocument(sourceUri)) {
                    val payload = readManifestFromZip(sourceUri)
                    MemoTransferCodec.decodeImportEntries(payload)
                } else {
                    val payload = readTextFromUri(sourceUri)
                    MemoTransferCodec.decodeImportEntries(payload)
                        .map { entry -> entry.copy(attachments = emptyList()) }
                }
                estimateImportPreview(
                    entries = entries,
                    dedupKeys = importContext.dedupKeys,
                )
            }
        }

    private suspend fun requireRemoteRepository() = accountService.getRemoteRepository()
        ?: throw IllegalStateException(R.string.current_account_no_remote_memo_operations.string)

    private suspend fun requireCurrentUserId(remoteRepository: RemoteRepository): String {
        val response = remoteRepository.getCurrentUser()
        return when (response) {
            is ApiResponse.Success -> normalizeUserIdentifier(response.data.identifier)
            is ApiResponse.Failure.Error -> throw IllegalStateException(response.getErrorMessage())
            is ApiResponse.Failure.Exception -> throw response.throwable
        }.ifEmpty {
            throw IllegalStateException("Current user is unavailable")
        }
    }

    private suspend fun buildTransferSource(currentUserId: String): MemoTransferSource {
        val account = accountService.currentAccount.first() as? Account.KeerV2
        return MemoTransferSource(
            host = account?.info?.host,
            userId = currentUserId,
            username = account?.info?.name,
        )
    }

    private suspend fun requireCurrentRemoteAccount(): Account.KeerV2 {
        return accountService.currentAccount.first() as? Account.KeerV2
            ?: throw IllegalStateException(R.string.current_account_no_remote_memo_operations.string)
    }

    private fun buildMemoImportDedupStore(accountKey: String): MemoImportDedupStore {
        val normalizedAccountKey = accountKey.trim()
        val filename = "${sha256(normalizedAccountKey)}.json"
        return MemoImportDedupStore(
            File(
                File(context.filesDir, memoImportDedupDirectory),
                filename
            )
        )
    }

    private suspend fun loadPersonalMemos(
        remoteRepository: RemoteRepository,
        currentUserId: String,
    ): List<Memo> = coroutineScope {
        val activeDeferred = async { requireSuccess(remoteRepository.listMemos()) }
        val archivedDeferred = async { requireSuccess(remoteRepository.listArchivedMemos()) }
        val activeMemos = activeDeferred.await()
        val archivedMemos = archivedDeferred.await()
        val merged = LinkedHashMap<String, Memo>()
        (activeMemos + archivedMemos).forEach { memo ->
            merged[memo.remoteId] = memo
        }
        val candidates = merged.values.map { memo ->
            memo to memo.creator?.identifier?.let(::normalizeUserIdentifier).orEmpty()
        }
        val hasCreatorInfo = candidates.any { (_, creatorId) -> creatorId.isNotEmpty() }
        candidates
            .asSequence()
            .filter { (_, creatorId) ->
                if (hasCreatorInfo) creatorId == currentUserId else true
            }
            .map { (memo, _) -> memo }
            .sortedByDescending(Memo::date)
            .toList()
    }

    private suspend fun prepareImportContext(): MemoImportContext {
        val remoteRepository = requireRemoteRepository()
        val account = requireCurrentRemoteAccount()
        val currentUserId = requireCurrentUserId(remoteRepository)
        val source = buildTransferSource(currentUserId)
        val existingMemoDedupKeys = loadPersonalMemos(remoteRepository, currentUserId)
            .asSequence()
            .flatMap { memo ->
                buildExistingMemoDedupKeys(source, memo).asSequence()
            }
            .toSet()
        val dedupStore = buildMemoImportDedupStore(account.accountKey())
        val dedupKeys = dedupStore.readKeys(
            nowMillis = System.currentTimeMillis(),
            ttlMillis = memoImportDedupEntryTtlMillis,
            maxEntries = memoImportDedupMaxEntries,
        ).toMutableSet().apply {
            addAll(existingMemoDedupKeys)
        }
        val localRepository = accountService.getRepository()
        return MemoImportContext(
            localRepository = localRepository,
            dedupStore = dedupStore,
            dedupKeys = dedupKeys,
        )
    }

    private suspend fun exportAsZip(
        destinationUri: Uri,
        source: MemoTransferSource,
        memos: List<Memo>,
        localRepository: AbstractMemoRepository,
        onProgress: ((MemoTransferProgress) -> Unit)?,
    ): MemoExportResult {
        val exportableMemos = memos.filterNot(::isDecryptUnavailableMemo)
        val failedCount = (memos.size - exportableMemos.size).coerceAtLeast(0)
        val totalAttachmentCount = exportableMemos.sumOf { memo -> memo.resources.size }
        val totalWorkCount = exportableMemos.size + totalAttachmentCount
        val output = context.contentResolver.openOutputStream(destinationUri)
            ?: throw IllegalStateException("Cannot open destination file")
        val tempManifestFile = File(
            context.cacheDir,
            "memo-transfer-manifest-${UUID.randomUUID()}.json"
        )
        val exportWorkingDir = File(context.cacheDir, "memo-transfer-export-${UUID.randomUUID()}")
        if (!exportWorkingDir.exists() && !exportWorkingDir.mkdirs()) {
            throw IllegalStateException("Cannot create temp directory for export")
        }
        var attachmentCount = 0
        var memoCount = 0
        val progressReporter = MemoTransferProgressReporter(
            operation = MemoTransferOperation.EXPORT,
            emit = onProgress,
        )
        fun emitExportProgress(stage: MemoTransferStage) {
            progressReporter.emit(
                stage = stage,
                completed = memoCount + attachmentCount,
                total = totalWorkCount,
            )
        }
        emitExportProgress(MemoTransferStage.PROCESSING_MEMOS)
        try {
            output.use { outputStream ->
                ZipOutputStream(BufferedOutputStream(outputStream, ioBufferSizeBytes)).use { zip ->
                    tempManifestFile.outputStream().bufferedWriter(Charsets.UTF_8).use { manifestWriter ->
                        MemoTransferCodec.writeDocument(
                            appendable = manifestWriter,
                            exportedAt = Instant.now().toString(),
                            source = source,
                        ) { appendMemo ->
                            exportableMemos.forEachIndexed { memoIndex, memo ->
                                val attachments = mutableListOf<MemoTransferAttachment>()
                                val stagedAttachments = prepareMemoAttachmentsForZip(
                                    memo = memo,
                                    memoIndex = memoIndex,
                                    localRepository = localRepository,
                                    workingDir = exportWorkingDir,
                                )
                                stagedAttachments.forEach { staged ->
                                    zip.putNextEntry(ZipEntry(staged.entryPath))
                                    staged.file.inputStream().buffered().use { input ->
                                        input.copyTo(zip, ioBufferSizeBytes)
                                    }
                                    zip.closeEntry()
                                    attachments += MemoTransferAttachment(
                                        path = staged.entryPath,
                                        filename = staged.filename,
                                        mimeType = staged.mimeType
                                    )
                                    if (staged.deleteAfterWrite) {
                                        runCatching { staged.file.delete() }
                                    }
                                    attachmentCount += 1
                                    emitExportProgress(MemoTransferStage.PROCESSING_ATTACHMENTS)
                                }
                                appendMemo(
                                    MemoTransferMemo(
                                        importId = buildExportImportId(source, memo),
                                        content = memo.content,
                                        createdAt = memo.date.toString(),
                                        visibility = memo.visibility.name,
                                        tags = memo.tags,
                                        latitude = memo.latitude,
                                        longitude = memo.longitude,
                                        pinned = memo.pinned,
                                        archived = memo.archived,
                                        attachments = attachments,
                                    )
                                )
                                memoCount += 1
                                emitExportProgress(MemoTransferStage.PROCESSING_MEMOS)
                            }
                        }
                    }
                    progressReporter.emit(
                        stage = MemoTransferStage.WRITING_MANIFEST,
                        completed = memoCount + attachmentCount,
                        total = totalWorkCount,
                    )
                    zip.putNextEntry(ZipEntry(transferManifestEntryName))
                    tempManifestFile.inputStream().buffered().use { input ->
                        input.copyTo(zip, ioBufferSizeBytes)
                    }
                    zip.closeEntry()
                }
            }
        } finally {
            runCatching { tempManifestFile.delete() }
            runCatching { exportWorkingDir.deleteRecursively() }
        }
        progressReporter.emit(
            stage = MemoTransferStage.COMPLETED,
            completed = totalWorkCount,
            total = totalWorkCount,
        )
        return MemoExportResult(
            exportedCount = exportableMemos.size,
            exportedAttachmentCount = attachmentCount,
            failedCount = failedCount,
        )
    }

    private suspend fun prepareMemoAttachmentsForZip(
        memo: Memo,
        memoIndex: Int,
        localRepository: AbstractMemoRepository,
        workingDir: File,
    ): List<PreparedExportAttachment> = coroutineScope {
        val semaphore = Semaphore(exportAttachmentParallelism)
        memo.resources.mapIndexed { attachmentIndex, resource ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    val filename = resolveAttachmentFilename(resource.filename, attachmentIndex)
                    val entryPath = buildAttachmentEntryPath(memoIndex, attachmentIndex, filename)
                    val source = resolveExportAttachmentSource(
                        resource = resource,
                        localRepository = localRepository,
                        workingDir = workingDir,
                        attachmentIndex = attachmentIndex,
                    )
                    PreparedExportAttachment(
                        attachmentIndex = attachmentIndex,
                        entryPath = entryPath,
                        filename = filename,
                        mimeType = resource.mimeType,
                        file = source.file,
                        deleteAfterWrite = source.deleteAfterUse,
                    )
                }
            }
        }.awaitAll()
            .sortedBy { prepared -> prepared.attachmentIndex }
    }

    private suspend fun resolveExportAttachmentSource(
        resource: Resource,
        localRepository: AbstractMemoRepository,
        workingDir: File,
        attachmentIndex: Int,
    ): PreparedAttachmentSource {
        resolveLocalAttachmentForExport(resource, localRepository)?.let { local ->
            return PreparedAttachmentSource(
                file = local,
                deleteAfterUse = false,
            )
        }
        return downloadRemoteAttachmentForExport(resource, workingDir, attachmentIndex)
    }

    private suspend fun resolveLocalAttachmentForExport(
        resource: Resource,
        localRepository: AbstractMemoRepository,
    ): File? {
        val remoteId = resource.remoteId.trim()
        if (remoteId.isEmpty()) {
            return null
        }
        val localResource = runCatching { localRepository.getResourceById(remoteId) }.getOrNull()
            ?: return null
        val candidates = listOfNotNull(localResource.localUri, localResource.uri).distinct()
        for (candidate in candidates) {
            val uri = runCatching { candidate.toUri() }.getOrNull() ?: continue
            if (uri.scheme != "file") {
                continue
            }
            val localFile = uri.path?.let(::File) ?: continue
            if (!localFile.exists() || localFile.length() <= 0L) {
                continue
            }
            return localFile
        }
        return null
    }

    private fun downloadRemoteAttachmentForExport(
        resource: Resource,
        workingDir: File,
        attachmentIndex: Int,
    ): PreparedAttachmentSource {
        val remoteFilename = resolveAttachmentFilename(resource.filename, attachmentIndex)
        val tempFile = File(
            workingDir,
            "remote-${UUID.randomUUID()}-${remoteFilename.sanitizeFilename()}"
        )
        val request = Request.Builder()
            .url(resource.uri)
            .get()
            .build()
        accountService.httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("Failed to download attachment (${response.code}): ${resource.filename}")
            }
            response.body.byteStream().use { input ->
                tempFile.outputStream().buffered().use { output ->
                    input.copyTo(output, ioBufferSizeBytes)
                }
            }
        }
        if (!tempFile.exists() || tempFile.length() <= 0L) {
            runCatching { tempFile.delete() }
            throw IllegalStateException("Downloaded attachment is empty: ${resource.filename}")
        }
        return PreparedAttachmentSource(
            file = tempFile,
            deleteAfterUse = true,
        )
    }

    private suspend fun importFromJson(
        sourceUri: Uri,
        localRepository: AbstractMemoRepository,
        dedupStore: MemoImportDedupStore,
        dedupKeys: MutableSet<String>,
        onProgress: ((MemoTransferProgress) -> Unit)?,
    ): MemoImportResult {
        onProgress?.invoke(
            MemoTransferProgress(
                operation = MemoTransferOperation.IMPORT,
                stage = MemoTransferStage.READING_PACKAGE,
            )
        )
        val input = context.contentResolver.openInputStream(sourceUri)
            ?: throw IllegalStateException("Cannot open import file")
        return input.bufferedReader(Charsets.UTF_8).use { reader ->
            importEntriesFromReader(
                reader = reader,
                includeAttachments = false,
                resolveAttachmentFile = { null },
                localRepository = localRepository,
                dedupStore = dedupStore,
                dedupKeys = dedupKeys,
                onProgress = onProgress,
            )
        }
    }

    private suspend fun importFromZip(
        sourceUri: Uri,
        localRepository: AbstractMemoRepository,
        dedupStore: MemoImportDedupStore,
        dedupKeys: MutableSet<String>,
        onProgress: ((MemoTransferProgress) -> Unit)?,
    ): MemoImportResult {
        val extractionDir = File(context.cacheDir, "memo-transfer-${UUID.randomUUID()}")
        if (!extractionDir.exists() && !extractionDir.mkdirs()) {
            throw IllegalStateException("Cannot create temp directory for import")
        }
        val tempZipFile = File(extractionDir, "import.zip")
        return try {
            onProgress?.invoke(
                MemoTransferProgress(
                    operation = MemoTransferOperation.IMPORT,
                    stage = MemoTransferStage.READING_PACKAGE,
                )
            )
            val input = context.contentResolver.openInputStream(sourceUri)
                ?: throw IllegalStateException("Cannot open import file")
            input.use { stream ->
                tempZipFile.outputStream().buffered().use { output ->
                    stream.copyTo(output, ioBufferSizeBytes)
                }
            }
            ZipFile(tempZipFile).use { zipFile ->
                val entriesByPath = linkedMapOf<String, ZipEntry>()
                val zipEntries = zipFile.entries()
                while (zipEntries.hasMoreElements()) {
                    val zipEntry = zipEntries.nextElement()
                    if (zipEntry.isDirectory) {
                        continue
                    }
                    val normalizedName = runCatching { normalizeZipEntryName(zipEntry.name) }
                        .getOrNull()
                        .orEmpty()
                    if (normalizedName.isEmpty()) {
                        continue
                    }
                    entriesByPath[normalizedName] = zipEntry
                }
                val manifestEntry = entriesByPath[transferManifestEntryName]
                    ?: throw IllegalStateException("Missing $transferManifestEntryName in import package")
                zipFile.getInputStream(manifestEntry).bufferedReader(Charsets.UTF_8).use { reader ->
                    importEntriesFromReader(
                        reader = reader,
                        includeAttachments = true,
                        resolveAttachmentFile = { attachment ->
                            extractAttachmentFromZip(
                                zipFile = zipFile,
                                entriesByPath = entriesByPath,
                                extractionDir = extractionDir,
                                attachment = attachment,
                            )
                        },
                        localRepository = localRepository,
                        dedupStore = dedupStore,
                        dedupKeys = dedupKeys,
                        onProgress = onProgress,
                    )
                }
            }
        } finally {
            extractionDir.deleteRecursively()
        }
    }

    private suspend fun importEntriesFromReader(
        reader: java.io.Reader,
        includeAttachments: Boolean,
        resolveAttachmentFile: suspend (MemoImportAttachment) -> File?,
        localRepository: AbstractMemoRepository,
        dedupStore: MemoImportDedupStore,
        dedupKeys: MutableSet<String>,
        onProgress: ((MemoTransferProgress) -> Unit)?,
    ): MemoImportResult {
        val progressReporter = MemoTransferProgressReporter(
            operation = MemoTransferOperation.IMPORT,
            emit = onProgress,
        )
        var imported = 0
        var failed = 0
        var skipped = 0
        var total = 0
        var importedAttachmentCount = 0
        var processedEntryCount = 0
        var processedAttachmentCount = 0
        val nowMillis = System.currentTimeMillis()
        val pendingPersistDedupKeys = linkedSetOf<String>()
        var lastPersistAtMillis = nowMillis
        progressReporter.emit(
            stage = MemoTransferStage.PROCESSING_MEMOS,
            completed = 0,
            total = null,
        )
        MemoTransferCodec.forEachImportEntry(reader) { rawEntry ->
            total += 1
            val entry = if (includeAttachments) rawEntry else rawEntry.copy(attachments = emptyList())
            if (entry.content.isBlank()) {
                skipped += 1
                processedEntryCount += 1
                progressReporter.emit(
                    stage = MemoTransferStage.PROCESSING_MEMOS,
                    completed = processedEntryCount + processedAttachmentCount,
                    total = null,
                )
                return@forEachImportEntry
            }
            val entryDedupKeys = buildImportDedupKeys(entry)
            if (entryDedupKeys.any { candidate -> candidate in dedupKeys }) {
                skipped += 1
                processedEntryCount += 1
                progressReporter.emit(
                    stage = MemoTransferStage.PROCESSING_MEMOS,
                    completed = processedEntryCount + processedAttachmentCount,
                    total = null,
                )
                return@forEachImportEntry
            }

            val importResourcesResult = importAttachmentsToLocal(
                entry = entry,
                resolveAttachmentFile = resolveAttachmentFile,
                localRepository = localRepository,
                onAttachmentProcessed = {
                    processedAttachmentCount += 1
                    progressReporter.emit(
                        stage = MemoTransferStage.PROCESSING_ATTACHMENTS,
                        completed = processedEntryCount + processedAttachmentCount,
                        total = null,
                    )
                }
            )
            if (!importResourcesResult.success) {
                failed += 1
                processedEntryCount += 1
                progressReporter.emit(
                    stage = MemoTransferStage.PROCESSING_MEMOS,
                    completed = processedEntryCount + processedAttachmentCount,
                    total = null,
                )
                return@forEachImportEntry
            }

            val createResponse = localRepository.createMemo(
                content = entry.content,
                visibility = entry.visibility,
                resources = importResourcesResult.resources,
                tags = normalizeTagList(entry.tags),
                createdAt = entry.createdAt,
                latitude = entry.latitude,
                longitude = entry.longitude,
            )
            val createdMemo = (createResponse as? ApiResponse.Success)?.data
            if (createdMemo == null) {
                cleanupImportedLocalResources(localRepository, importResourcesResult.resources)
                failed += 1
                processedEntryCount += 1
                progressReporter.emit(
                    stage = MemoTransferStage.PROCESSING_MEMOS,
                    completed = processedEntryCount + processedAttachmentCount,
                    total = null,
                )
                return@forEachImportEntry
            }

            if (entry.pinned) {
                localRepository.updateMemo(
                    identifier = createdMemo.identifier,
                    pinned = true,
                )
            }
            if (entry.archived) {
                localRepository.archiveMemo(createdMemo.identifier)
            }
            dedupKeys.addAll(entryDedupKeys)
            pendingPersistDedupKeys.addAll(entryDedupKeys)
            val now = System.currentTimeMillis()
            val shouldPersistCheckpoint =
                pendingPersistDedupKeys.size >= memoImportDedupPersistBatchSize ||
                    now - lastPersistAtMillis >= memoImportDedupPersistIntervalMillis
            if (shouldPersistCheckpoint) {
                dedupStore.upsertImported(
                    keys = pendingPersistDedupKeys,
                    nowMillis = now,
                    ttlMillis = memoImportDedupEntryTtlMillis,
                    maxEntries = memoImportDedupMaxEntries,
                )
                pendingPersistDedupKeys.clear()
                lastPersistAtMillis = now
            }
            importedAttachmentCount += importResourcesResult.importedAttachmentCount
            imported += 1
            processedEntryCount += 1
            progressReporter.emit(
                stage = MemoTransferStage.PROCESSING_MEMOS,
                completed = processedEntryCount + processedAttachmentCount,
                total = null,
            )
        }

        val finalPersistNowMillis = System.currentTimeMillis()
        dedupStore.upsertImported(
            keys = pendingPersistDedupKeys,
            nowMillis = finalPersistNowMillis,
            ttlMillis = memoImportDedupEntryTtlMillis,
            maxEntries = memoImportDedupMaxEntries,
        )
        progressReporter.emit(
            stage = MemoTransferStage.COMPLETED,
            completed = processedEntryCount + processedAttachmentCount,
            total = null,
        )

        return MemoImportResult(
            total = total,
            imported = imported,
            failed = failed,
            skipped = skipped,
            importedAttachmentCount = importedAttachmentCount,
        )
    }

    private suspend fun importAttachmentsToLocal(
        entry: MemoImportEntry,
        resolveAttachmentFile: suspend (MemoImportAttachment) -> File?,
        localRepository: AbstractMemoRepository,
        onAttachmentProcessed: () -> Unit,
    ): LocalAttachmentImportResult {
        if (entry.attachments.isEmpty()) {
            return LocalAttachmentImportResult(
                success = true,
                resources = emptyList(),
                importedAttachmentCount = 0,
            )
        }
        val importedResources = mutableListOf<ResourceEntity>()
        for (attachment in entry.attachments) {
            val localFile = resolveAttachmentFile(attachment)
            if (localFile == null || !localFile.exists() || localFile.length() <= 0L) {
                cleanupImportedLocalResources(localRepository, importedResources)
                onAttachmentProcessed()
                return LocalAttachmentImportResult(
                    success = false,
                    resources = emptyList(),
                    importedAttachmentCount = 0,
                )
            }
            val sourceUri = localFile.toUri()
            val createResponse = try {
                localRepository.createResource(
                    filename = attachment.filename.ifBlank { localFile.name },
                    type = attachment.mimeType?.toMediaTypeOrNull(),
                    sourceUri = sourceUri,
                    memoIdentifier = null,
                )
            } finally {
                runCatching { localFile.delete() }
            }
            val createdResource = (createResponse as? ApiResponse.Success)?.data
            if (createdResource == null) {
                cleanupImportedLocalResources(localRepository, importedResources)
                onAttachmentProcessed()
                return LocalAttachmentImportResult(
                    success = false,
                    resources = emptyList(),
                    importedAttachmentCount = 0,
                )
            }
            importedResources += createdResource
            onAttachmentProcessed()
        }
        return LocalAttachmentImportResult(
            success = true,
            resources = importedResources,
            importedAttachmentCount = importedResources.size,
        )
    }

    private suspend fun cleanupImportedLocalResources(
        localRepository: AbstractMemoRepository,
        resources: List<ResourceEntity>,
    ) {
        resources.forEach { resource ->
            runCatching {
                localRepository.deleteResource(resource.identifier)
            }
        }
    }

    private fun extractAttachmentFromZip(
        zipFile: ZipFile,
        entriesByPath: Map<String, ZipEntry>,
        extractionDir: File,
        attachment: MemoImportAttachment,
    ): File? {
        val normalizedPath = runCatching { normalizeZipEntryName(attachment.path) }
            .getOrNull()
            ?: return null
        val zipEntry = entriesByPath[normalizedPath] ?: return null
        val outputFile = File(
            extractionDir,
            "attachment-${UUID.randomUUID()}-${attachment.filename.sanitizeFilename()}"
        )
        zipFile.getInputStream(zipEntry).buffered().use { input ->
            outputFile.outputStream().buffered().use { output ->
                input.copyTo(output, ioBufferSizeBytes)
            }
        }
        if (!outputFile.exists() || outputFile.length() <= 0L) {
            runCatching { outputFile.delete() }
            return null
        }
        return outputFile
    }

    private fun isZipDocument(sourceUri: Uri): Boolean {
        val input = context.contentResolver.openInputStream(sourceUri)
            ?: throw IllegalStateException("Cannot open import file")
        return input.use { stream ->
            val signature = ByteArray(4)
            val read = stream.read(signature)
            read == 4 &&
                signature[0] == 0x50.toByte() &&
                signature[1] == 0x4B.toByte() &&
                signature[2] in setOf(0x03, 0x05, 0x07).map(Int::toByte) &&
                signature[3] in setOf(0x04, 0x06, 0x08).map(Int::toByte)
        }
    }

    private fun readTextFromUri(sourceUri: Uri): String {
        val input = context.contentResolver.openInputStream(sourceUri)
            ?: throw IllegalStateException("Cannot open import file")
        return input.bufferedReader(Charsets.UTF_8).use { reader ->
            reader.readText()
        }
    }

    private fun readManifestFromZip(sourceUri: Uri): String {
        val input = context.contentResolver.openInputStream(sourceUri)
            ?: throw IllegalStateException("Cannot open import file")
        input.use { stream ->
            ZipInputStream(stream.buffered(ioBufferSizeBytes)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val entryName = runCatching { normalizeZipEntryName(entry.name.orEmpty()) }
                        .getOrNull()
                        .orEmpty()
                    if (entry.isDirectory || entryName.isEmpty()) {
                        zip.closeEntry()
                        continue
                    }
                    if (entryName == transferManifestEntryName) {
                        val payload = readCurrentZipEntryAsText(zip)
                        zip.closeEntry()
                        return payload
                    }
                    zip.closeEntry()
                }
            }
        }
        throw IllegalStateException("Missing $transferManifestEntryName in import package")
    }

    private fun readCurrentZipEntryAsText(zip: ZipInputStream): String {
        val reader = zip.reader(Charsets.UTF_8)
        val buffer = CharArray(ioTextBufferSizeChars)
        val builder = StringBuilder()
        while (true) {
            val read = reader.read(buffer)
            if (read <= 0) {
                break
            }
            builder.append(buffer, 0, read)
        }
        return builder.toString()
    }

    private fun estimateImportPreview(
        entries: List<MemoImportEntry>,
        dedupKeys: MutableSet<String>,
    ): MemoImportPreviewResult {
        var estimatedSkipped = 0
        var estimatedImportable = 0
        entries.forEach { entry ->
            if (entry.content.isBlank()) {
                estimatedSkipped += 1
                return@forEach
            }
            val entryDedupKeys = buildImportDedupKeys(entry)
            if (entryDedupKeys.any { candidate -> candidate in dedupKeys }) {
                estimatedSkipped += 1
                return@forEach
            }
            estimatedImportable += 1
            dedupKeys.addAll(entryDedupKeys)
        }
        return MemoImportPreviewResult(
            total = entries.size,
            estimatedImportable = estimatedImportable,
            estimatedSkipped = estimatedSkipped,
        )
    }

    private fun normalizeZipEntryName(raw: String): String {
        val normalized = raw
            .replace('\\', '/')
            .trim()
            .trimStart('/')
        if (normalized.isEmpty()) {
            return ""
        }
        if (normalized.contains("..") || normalized.contains(':')) {
            throw IllegalArgumentException("Invalid zip entry name")
        }
        return normalized
    }

    private fun buildAttachmentEntryPath(
        memoIndex: Int,
        attachmentIndex: Int,
        filename: String,
    ): String {
        val memoSegment = (memoIndex + 1).toString().padStart(4, '0')
        val attachmentSegment = (attachmentIndex + 1).toString().padStart(3, '0')
        return "$transferAttachmentsPrefix/memo-$memoSegment/$attachmentSegment-${filename.sanitizeFilename()}"
    }

    private fun resolveAttachmentFilename(rawFilename: String, attachmentIndex: Int): String {
        val trimmed = rawFilename.trim()
        if (trimmed.isNotEmpty()) {
            return trimmed
        }
        return "attachment-${attachmentIndex + 1}.bin"
    }

    private fun String.sanitizeFilename(): String {
        val sanitized = replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .replace(Regex("\\s+"), " ")
            .trim()
        return sanitized.ifEmpty { "attachment.bin" }
    }

    private fun normalizeUserIdentifier(raw: String): String {
        return raw.trim()
            .substringBefore('|')
            .substringAfterLast('/')
            .trim()
    }

    private fun isDecryptUnavailableMemo(memo: Memo): Boolean {
        return memo.content.trim() == encryptedContentUnavailablePlaceholder
    }

    private fun buildExportImportId(
        source: MemoTransferSource,
        memo: Memo,
    ): String {
        val raw = listOf(
            source.host?.trim().orEmpty(),
            source.userId?.trim().orEmpty(),
            memo.remoteId.trim(),
        ).joinToString("\u001f")
        return "keer:$memoTransferImportIdVersion:${sha256(raw)}"
    }

    private fun buildImportDedupKeys(entry: MemoImportEntry): Set<String> {
        val keys = linkedSetOf<String>()
        entry.importId
            ?.trim()
            ?.takeIf { value -> value.isNotEmpty() }
            ?.let { importId ->
                keys += "id:$importId"
            }
        keys += "fp:${buildImportEntryFingerprint(entry)}"
        return keys
    }

    private fun buildExistingMemoDedupKeys(
        source: MemoTransferSource,
        memo: Memo,
    ): Set<String> {
        return linkedSetOf(
            "id:${buildExportImportId(source, memo)}",
            "fp:${buildMemoFingerprint(memo)}",
        )
    }

    private fun buildImportEntryFingerprint(entry: MemoImportEntry): String {
        val createdAt = entry.createdAt?.epochSecond?.toString().orEmpty()
        val tags = normalizeTagList(entry.tags).sorted().joinToString("\u001f")
        val latitude = canonicalizeCoordinate(entry.latitude)
        val longitude = canonicalizeCoordinate(entry.longitude)
        val attachments = entry.attachments
            .map { attachment ->
                "${attachment.filename.trim()}|${attachment.mimeType?.trim().orEmpty()}"
            }
            .sorted()
            .joinToString("\u001f")
        val raw = listOf(
            entry.content.replace("\r\n", "\n"),
            createdAt,
            entry.visibility.name,
            tags,
            latitude,
            longitude,
            if (entry.pinned) "1" else "0",
            if (entry.archived) "1" else "0",
            attachments,
        ).joinToString("\u001e")
        return sha256(raw)
    }

    private fun buildMemoFingerprint(memo: Memo): String {
        val tags = normalizeTagList(memo.tags).sorted().joinToString("\u001f")
        val latitude = canonicalizeCoordinate(memo.latitude)
        val longitude = canonicalizeCoordinate(memo.longitude)
        val attachments = memo.resources
            .map { resource -> "${resource.filename.trim()}|${resource.mimeType?.trim().orEmpty()}" }
            .sorted()
            .joinToString("\u001f")
        val raw = listOf(
            memo.content.replace("\r\n", "\n"),
            memo.date.epochSecond.toString(),
            memo.visibility.name,
            tags,
            latitude,
            longitude,
            if (memo.pinned) "1" else "0",
            if (memo.archived) "1" else "0",
            attachments,
        ).joinToString("\u001e")
        return sha256(raw)
    }

    private fun canonicalizeCoordinate(value: Double?): String {
        return value?.let { coordinate ->
            BigDecimal.valueOf(coordinate).stripTrailingZeros().toPlainString()
        }.orEmpty()
    }

    private fun sha256(text: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return buildString(bytes.size * 2) {
            bytes.forEach { byte ->
                val value = byte.toInt() and 0xFF
                append(hex[value ushr 4])
                append(hex[value and 0x0F])
            }
        }
    }

    private fun <T> requireSuccess(response: ApiResponse<T>): T {
        return when (response) {
            is ApiResponse.Success -> response.data
            is ApiResponse.Failure.Error -> throw IllegalStateException(response.getErrorMessage())
            is ApiResponse.Failure.Exception -> throw response.throwable
        }
    }

    private data class MemoImportContext(
        val localRepository: AbstractMemoRepository,
        val dedupStore: MemoImportDedupStore,
        val dedupKeys: MutableSet<String>,
    )

    private data class PreparedAttachmentSource(
        val file: File,
        val deleteAfterUse: Boolean,
    )

    private data class PreparedExportAttachment(
        val attachmentIndex: Int,
        val entryPath: String,
        val filename: String,
        val mimeType: String?,
        val file: File,
        val deleteAfterWrite: Boolean,
    )

    private data class LocalAttachmentImportResult(
        val success: Boolean,
        val resources: List<ResourceEntity>,
        val importedAttachmentCount: Int,
    )

    companion object {
        private const val transferManifestEntryName = "manifest.json"
        private const val transferAttachmentsPrefix = "attachments"
        private const val memoImportDedupDirectory = "memo_import_dedup"
        private const val memoImportDedupMaxEntries = 20_000
        private const val memoImportDedupEntryTtlMillis = 7L * 24L * 60L * 60L * 1000L
        private const val memoImportDedupPersistBatchSize = 24
        private const val memoImportDedupPersistIntervalMillis = 2_500L
        private const val memoTransferImportIdVersion = "v1"
        private const val encryptedContentUnavailablePlaceholder = "[Encrypted content unavailable]"
        private const val ioBufferSizeBytes = 64 * 1024
        private const val ioTextBufferSizeChars = 8 * 1024
        private const val exportAttachmentParallelism = 3
        private const val hex = "0123456789abcdef"
    }
}

private class MemoTransferProgressReporter(
    private val operation: MemoTransferOperation,
    private val emit: ((MemoTransferProgress) -> Unit)?,
) {
    private var lastStage: MemoTransferStage? = null
    private var lastCompleted: Int? = null
    private var lastTotal: Int? = null

    fun emit(
        stage: MemoTransferStage,
        completed: Int?,
        total: Int?,
    ) {
        if (emit == null) {
            return
        }
        val unchanged = lastStage == stage && lastCompleted == completed && lastTotal == total
        if (unchanged) {
            return
        }
        lastStage = stage
        lastCompleted = completed
        lastTotal = total
        emit.invoke(
            MemoTransferProgress(
                operation = operation,
                stage = stage,
                completed = completed,
                total = total,
            )
        )
    }
}
