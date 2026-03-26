package site.lcyk.keer.ui.component

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import com.skydoves.sandwich.ApiResponse
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import site.lcyk.keer.data.local.entity.MemoEntity
import site.lcyk.keer.data.local.entity.ResourceEntity
import site.lcyk.keer.data.model.ResourceRepresentable
import site.lcyk.keer.data.security.EncryptedBlobVariant
import java.io.File
import okhttp3.OkHttpClient
import timber.log.Timber

internal object MediaPreviewPrefetchCoordinator {
    private val networkSemaphore = Semaphore(permits = PREFETCH_NETWORK_CONCURRENCY)
    private val decryptSemaphore = Semaphore(permits = PREFETCH_DECRYPT_CONCURRENCY)
    private val writeSemaphore = Semaphore(permits = PREFETCH_WRITE_CONCURRENCY)
    private val inFlightMutex = Mutex()
    private val inFlightKeys = linkedSetOf<String>()
    private val lifecycleStateMutex = Mutex()
    private val lifecycleStates = linkedMapOf<String, ResourcePrefetchLifecycleState>()

    suspend fun prefetchMemoWindowResources(
        context: Context,
        okHttpClient: OkHttpClient,
        currentAccountKey: String?,
        memos: List<MemoEntity>,
        visibleIndices: List<Int>,
        windowAhead: Int = PREFETCH_WINDOW_AHEAD,
        windowBehind: Int = PREFETCH_WINDOW_BEHIND,
        cacheResourceFile: suspend (String, Uri) -> ApiResponse<Unit>,
        cacheResourceThumbnail: suspend (String, Uri) -> ApiResponse<Unit>,
        updateResourceThumbnail: suspend (String, String) -> ApiResponse<Unit>,
    ) {
        if (memos.isEmpty() || visibleIndices.isEmpty()) {
            return
        }
        val resources = collectWindowResources(
            memos = memos,
            visibleIndices = visibleIndices,
            windowAhead = windowAhead,
            windowBehind = windowBehind,
        )
        prefetchResources(
            context = context,
            okHttpClient = okHttpClient,
            currentAccountKey = currentAccountKey,
            resources = resources,
            cacheResourceFile = cacheResourceFile,
            cacheResourceThumbnail = cacheResourceThumbnail,
            updateResourceThumbnail = updateResourceThumbnail,
        )
    }

    suspend fun prefetchResources(
        context: Context,
        okHttpClient: OkHttpClient,
        currentAccountKey: String?,
        resources: List<ResourceEntity>,
        cacheResourceFile: suspend (String, Uri) -> ApiResponse<Unit>,
        cacheResourceThumbnail: suspend (String, Uri) -> ApiResponse<Unit>,
        updateResourceThumbnail: suspend (String, String) -> ApiResponse<Unit>,
    ) {
        if (resources.isEmpty()) {
            return
        }
        coroutineScope {
            resources.distinctBy(ResourceEntity::identifier).forEach { resource ->
                launch {
                    prefetchSingleResource(
                        context = context,
                        okHttpClient = okHttpClient,
                        currentAccountKey = currentAccountKey,
                        resource = resource,
                        cacheResourceFile = cacheResourceFile,
                        cacheResourceThumbnail = cacheResourceThumbnail,
                        updateResourceThumbnail = updateResourceThumbnail,
                    )
                }
            }
        }
    }

    private suspend fun prefetchSingleResource(
        context: Context,
        okHttpClient: OkHttpClient,
        currentAccountKey: String?,
        resource: ResourceEntity,
        cacheResourceFile: suspend (String, Uri) -> ApiResponse<Unit>,
        cacheResourceThumbnail: suspend (String, Uri) -> ApiResponse<Unit>,
        updateResourceThumbnail: suspend (String, String) -> ApiResponse<Unit>,
    ) {
        logPrefetchTrace(resource, "prefetch_start")
        if (!resource.isMediaResource()) {
            logPrefetchTrace(resource, "prefetch_skip_non_media")
            return
        }
        if (resource.isUntrackedMemoScope()) {
            val previewUri = prefetchUntrackedResourcePreview(
                context = context,
                okHttpClient = okHttpClient,
                resource = resource,
                currentAccountKey = currentAccountKey,
            )
            logPrefetchTrace(
                resource,
                if (previewUri == null) "prefetch_untracked_no_preview" else "prefetch_untracked_ready",
            )
            return
        }
        val previewSnapshot = resolvePreviewSnapshot(resource)
        if (previewSnapshot != null) {
            logPrefetchTrace(resource, "prefetch_snapshot_hit", "source=${previewSnapshot.source}")
            MediaPreviewRuntimeCache.rememberPreviewUri(previewCacheKey(resource), previewSnapshot.uri)
            val localThumbnail = resolveUsableThumbnailLocalUri(resource.thumbnailLocalUri)
                ?: previewSnapshot
                    .takeIf { snapshot -> snapshot.source == "runtime_cache" }
                    ?.let { snapshot -> resolveUsableThumbnailLocalUri(snapshot.uri) }
            val shouldUploadMissingRemoteThumbnail = shouldTriggerRemoteThumbnailUpload(
                resource = resource,
                localThumbnailUri = localThumbnail,
            )
            if (shouldUploadMissingRemoteThumbnail) {
                if (ThumbnailUploadKickoffGate.tryAcquire(resource.identifier)) {
                    logPrefetchTrace(resource, "prefetch_upload_enqueue")
                    localThumbnail?.let { usableLocalThumbnail ->
                        writeSemaphore.withPermit {
                            updateResourceThumbnail(resource.identifier, usableLocalThumbnail)
                        }
                    }
                } else {
                    logPrefetchTrace(resource, "prefetch_upload_skip", "reason=gate_denied")
                }
            } else {
                logPrefetchTrace(
                    resource,
                    "prefetch_upload_skip",
                    "reason=${resolvePrefetchUploadSkipReason(resource, localThumbnail)}",
                )
            }
            val hasResolvableLocalMain = hasResolvableLocalMainUri(resource.localUri)
            val shouldBackfillFromLocalMain = (previewSnapshot.source == "local_main" ||
                (previewSnapshot.source == "runtime_cache" && hasResolvableLocalMain)) &&
                resolveUsableThumbnailLocalUri(resource.thumbnailLocalUri).isNullOrBlank() &&
                resource.resolveUsableRemoteThumbnailUri().isNullOrBlank()
            if (!shouldBackfillFromLocalMain) {
                markLifecycleReady(resource)
                logPrefetchDecision(resource, previewSnapshot.source)
                return
            }
            logPrefetchDecision(
                resource,
                if (previewSnapshot.source == "runtime_cache") "runtime_cache_backfill" else "local_main_backfill",
            )
        }
        val lifecycleDecision = resolveLifecycleDecision(resource)
        logPrefetchTrace(resource, "prefetch_lifecycle", "decision=${lifecycleDecision.name}")
        if (lifecycleDecision == MainFallbackLifecycleDecision.COOLDOWN) {
            logPrefetchDecision(resource, "cooldown")
            return
        }
        val inFlightKey = "tracked:${resolveResourceStableKey(resource)}"
        if (!acquireInFlight(inFlightKey)) {
            logPrefetchTrace(resource, "prefetch_skip_inflight")
            return
        }
        try {
            var ensureResult = PreviewEnsureResult(source = PreviewEnsureSource.NONE)
            networkSemaphore.withPermit {
                decryptSemaphore.withPermit {
                    ensureResult = when {
                        resource.isVideoResource() -> {
                            ensureMemoVideoCardPreview(
                                context = context,
                                okHttpClient = okHttpClient,
                                resource = resource,
                                currentAccountKey = currentAccountKey,
                                cacheResourceThumbnail = { identifier, downloadedUri ->
                                    writeSemaphore.withPermit {
                                        cacheResourceThumbnail(identifier, downloadedUri)
                                    }
                                },
                                updateResourceThumbnail = { identifier, localThumbnailUri ->
                                    writeSemaphore.withPermit {
                                        updateResourceThumbnail(identifier, localThumbnailUri)
                                    }
                                },
                                allowMainFallback = lifecycleDecision == MainFallbackLifecycleDecision.ALLOWED,
                            )
                        }
                        else -> {
                            ensureMemoImageCardPreview(
                                context = context,
                                okHttpClient = okHttpClient,
                                resource = resource,
                                currentAccountKey = currentAccountKey,
                                cacheResourceFile = { identifier, downloadedUri ->
                                    writeSemaphore.withPermit {
                                        cacheResourceFile(identifier, downloadedUri)
                                    }
                                },
                                cacheResourceThumbnail = { identifier, downloadedUri ->
                                    writeSemaphore.withPermit {
                                        cacheResourceThumbnail(identifier, downloadedUri)
                                    }
                                },
                                updateResourceThumbnail = { identifier, localThumbnailUri ->
                                    writeSemaphore.withPermit {
                                        updateResourceThumbnail(identifier, localThumbnailUri)
                                    }
                                },
                                allowMainFallback = lifecycleDecision == MainFallbackLifecycleDecision.ALLOWED,
                            )
                        }
                    }
                }
            }
            if (ensureResult.mainFallbackAttempted) {
                markMainFallbackFetched(resource)
            }

            if (ensureResult.source == PreviewEnsureSource.REMOTE_THUMB && !ensureResult.previewReady) {
                markLifecycleCooldown(
                    resource = resource,
                    nowMillis = System.currentTimeMillis(),
                    cooldownMillis = REMOTE_THUMBNAIL_FAILURE_COOLDOWN_MILLIS,
                )
            }

            resolvePreviewSnapshot(resource)?.let { snapshot ->
                MediaPreviewRuntimeCache.rememberPreviewUri(previewCacheKey(resource), snapshot.uri)
                markLifecycleReady(resource)
                logPrefetchDecision(resource, snapshot.source)
                return
            }

            when {
                ensureResult.mainFallbackAttempted -> {
                    logPrefetchDecision(resource, "main_fallback")
                }
                ensureResult.source == PreviewEnsureSource.MAIN_FALLBACK_SKIPPED &&
                    lifecycleDecision == MainFallbackLifecycleDecision.SKIPPED_ONCE -> {
                    logPrefetchDecision(resource, "skipped_once")
                }
                ensureResult.source == PreviewEnsureSource.MAIN_FALLBACK_SKIPPED -> {
                    logPrefetchDecision(resource, "skipped")
                }
                else -> {
                    logPrefetchDecision(resource, ensureResult.source.logLabel)
                }
            }
        } finally {
            releaseInFlight(inFlightKey)
        }
    }

    suspend fun prefetchUntrackedResourcePreview(
        context: Context,
        okHttpClient: OkHttpClient,
        resource: ResourceRepresentable,
        currentAccountKey: String?,
    ): String? {
        val cacheKey = previewCacheKey(resource)
        MediaPreviewRuntimeCache.resolvePreviewUri(cacheKey)?.let { cached ->
            logPrefetchTrace(resource, "prefetch_untracked_hit_runtime_cache")
            return cached
        }
        val inFlightKey = "untracked:$cacheKey"
        if (!acquireInFlight(inFlightKey)) {
            logPrefetchTrace(resource, "prefetch_untracked_skip_inflight")
            return null
        }
        try {
            val previewFile = networkSemaphore.withPermit {
                decryptSemaphore.withPermit {
                    when {
                        resource.isVideoResource() -> {
                            val remoteThumbnail = resource.resolveUsableRemoteThumbnailUri().orEmpty()
                            if (!remoteThumbnail.isHttpUrl()) {
                                null
                            } else {
                                downloadResourceVariantToTemp(
                                    context = context,
                                    okHttpClient = okHttpClient,
                                    resource = resource,
                                    accountKey = resolveResourceAccountKey(resource, currentAccountKey),
                                    url = remoteThumbnail,
                                    filename = resource.filename,
                                    variant = EncryptedBlobVariant.THUMBNAIL,
                                    cacheDirName = "thumbnail_cache",
                                    prefix = "video_thumb_",
                                )
                            }
                        }
                        else -> {
                            val remoteThumbnail = resource.resolveUsableRemoteThumbnailUri().orEmpty()
                            if (!remoteThumbnail.isHttpUrl()) {
                                // Keep list/explore preview lightweight: do not pull original blobs here.
                                null
                            } else {
                                downloadResourceVariantToTemp(
                                    context = context,
                                    okHttpClient = okHttpClient,
                                    resource = resource,
                                    accountKey = resolveResourceAccountKey(resource, currentAccountKey),
                                    url = remoteThumbnail,
                                    filename = resource.filename,
                                    variant = EncryptedBlobVariant.THUMBNAIL,
                                    cacheDirName = "thumbnail_cache",
                                    prefix = "thumb_",
                                )
                            }
                        }
                    }
                }
            } ?: run {
                logPrefetchTrace(resource, "prefetch_untracked_no_preview_file")
                return null
            }
            val previewUri = previewFile.toUri().toString()
            MediaPreviewRuntimeCache.rememberPreviewUri(cacheKey, previewUri)
            logPrefetchTrace(resource, "prefetch_untracked_ready")
            return previewUri
        } finally {
            releaseInFlight(inFlightKey)
        }
    }

    private suspend fun acquireInFlight(key: String): Boolean {
        return inFlightMutex.withLock {
            if (inFlightKeys.contains(key)) {
                false
            } else {
                inFlightKeys += key
                true
            }
        }
    }

    private suspend fun releaseInFlight(key: String) {
        inFlightMutex.withLock {
            inFlightKeys.remove(key)
        }
    }

    internal fun collectWindowResourcesForTest(
        memos: List<MemoEntity>,
        visibleIndices: List<Int>,
        windowAhead: Int = PREFETCH_WINDOW_AHEAD,
        windowBehind: Int = PREFETCH_WINDOW_BEHIND,
    ): List<ResourceEntity> {
        return collectWindowResources(
            memos = memos,
            visibleIndices = visibleIndices,
            windowAhead = windowAhead,
            windowBehind = windowBehind,
        )
    }

    internal fun resolveResourceLifecycleKeyForTest(resource: ResourceEntity): String {
        return resolveResourceLifecycleKey(resource)
    }

    internal suspend fun resolveLifecycleDecisionForTest(resource: ResourceEntity): String {
        return resolveLifecycleDecision(resource).name
    }

    internal suspend fun markMainFallbackFetchedForTest(resource: ResourceEntity) {
        markMainFallbackFetched(resource)
    }

    internal suspend fun markLifecycleCooldownForTest(
        resource: ResourceEntity,
        nowMillis: Long,
        cooldownMillis: Long,
    ) {
        markLifecycleCooldown(resource, nowMillis, cooldownMillis)
    }

    internal suspend fun clearPrefetchStateForTest() {
        lifecycleStateMutex.withLock {
            lifecycleStates.clear()
        }
        inFlightMutex.withLock {
            inFlightKeys.clear()
        }
    }

    private fun collectWindowResources(
        memos: List<MemoEntity>,
        visibleIndices: List<Int>,
        windowAhead: Int,
        windowBehind: Int,
    ): List<ResourceEntity> {
        val firstVisible = visibleIndices.minOrNull() ?: return emptyList()
        val lastVisible = visibleIndices.maxOrNull() ?: return emptyList()
        val start = (firstVisible - windowBehind).coerceAtLeast(0)
        val endInclusive = (lastVisible + windowAhead).coerceAtMost(memos.lastIndex)
        if (start > endInclusive) {
            return emptyList()
        }
        val window = memos.subList(start, endInclusive + 1)
        return window
            .asSequence()
            .flatMap { memo -> memo.resources.asSequence() }
            .filterIsInstance<ResourceEntity>()
            .filter { resource -> resource.isMediaResource() }
            .toList()
    }

    private fun resolvePreviewSnapshot(resource: ResourceEntity): PreviewSnapshot? {
        MediaPreviewRuntimeCache.resolvePreviewUri(previewCacheKey(resource))?.let { runtimeCached ->
            return PreviewSnapshot(
                uri = runtimeCached,
                source = "runtime_cache",
            )
        }
        val localThumbnail = resolveUsableThumbnailLocalUri(resource.thumbnailLocalUri)
        if (!localThumbnail.isNullOrBlank()) {
            return PreviewSnapshot(
                uri = localThumbnail,
                source = "local_thumb",
            )
        }
        val localUri = resource.localUri?.trim().orEmpty()
        if (localUri.isNotEmpty()) {
            val uri = localUri.toUri()
            if (uri.scheme != "file") {
                return PreviewSnapshot(
                    uri = localUri,
                    source = "local_main",
                )
            }
            val file = uri.path?.let(::File)
            if (file != null && file.exists() && file.length() > 0L) {
                return PreviewSnapshot(
                    uri = localUri,
                    source = "local_main",
                )
            }
        }
        return null
    }

    private fun hasResolvableLocalMainUri(rawLocalUri: String?): Boolean {
        val localUri = rawLocalUri?.trim().orEmpty()
        if (localUri.isEmpty()) {
            return false
        }
        val uri = localUri.toUri()
        if (uri.scheme != "file") {
            return true
        }
        val localFile = uri.path?.let(::File) ?: return false
        return localFile.exists() && localFile.length() > 0L
    }

    private suspend fun resolveLifecycleDecision(resource: ResourceEntity): MainFallbackLifecycleDecision {
        val nowMillis = System.currentTimeMillis()
        return lifecycleStateMutex.withLock {
            val state = lifecycleStates[resolveResourceLifecycleKey(resource)] ?: return@withLock MainFallbackLifecycleDecision.ALLOWED
            when {
                state.mainFetchedOnce -> MainFallbackLifecycleDecision.SKIPPED_ONCE
                nowMillis < state.cooldownUntilMillis -> MainFallbackLifecycleDecision.COOLDOWN
                else -> MainFallbackLifecycleDecision.ALLOWED
            }
        }
    }

    private suspend fun markLifecycleReady(resource: ResourceEntity) {
        lifecycleStateMutex.withLock {
            val key = resolveResourceLifecycleKey(resource)
            val state = lifecycleStates.getOrPut(key) { ResourcePrefetchLifecycleState() }
            state.ready = true
            state.cooldownUntilMillis = 0L
            trimLifecycleStateIfNeeded()
        }
    }

    private suspend fun markMainFallbackFetched(resource: ResourceEntity) {
        lifecycleStateMutex.withLock {
            val key = resolveResourceLifecycleKey(resource)
            val state = lifecycleStates.getOrPut(key) { ResourcePrefetchLifecycleState() }
            state.mainFetchedOnce = true
            trimLifecycleStateIfNeeded()
        }
    }

    private suspend fun markLifecycleCooldown(
        resource: ResourceEntity,
        nowMillis: Long,
        cooldownMillis: Long,
    ) {
        lifecycleStateMutex.withLock {
            val key = resolveResourceLifecycleKey(resource)
            val state = lifecycleStates.getOrPut(key) { ResourcePrefetchLifecycleState() }
            state.cooldownUntilMillis = nowMillis + cooldownMillis
            trimLifecycleStateIfNeeded()
        }
    }

    private fun resolveResourceStableKey(resource: ResourceEntity): String {
        val normalizedIdentifier = resource.identifier.trim()
        if (normalizedIdentifier.isNotEmpty()) {
            return normalizedIdentifier
        }
        return resolveResourceLifecycleKey(resource)
    }

    private fun resolveResourceLifecycleKey(resource: ResourceEntity): String {
        val normalizedRemoteId = resource.remoteId?.trim().orEmpty()
        if (normalizedRemoteId.isNotEmpty()) {
            return "remote:$normalizedRemoteId"
        }
        val normalizedLocalUri = resource.localUri?.trim().orEmpty()
        if (normalizedLocalUri.isNotEmpty()) {
            return "local:$normalizedLocalUri"
        }
        return "uri:${resource.uri.trim()}"
    }

    private fun trimLifecycleStateIfNeeded() {
        while (lifecycleStates.size > MAX_LIFECYCLE_STATE_ENTRIES) {
            val eldestKey = lifecycleStates.entries.firstOrNull()?.key ?: return
            lifecycleStates.remove(eldestKey)
        }
    }

    private fun logPrefetchDecision(resource: ResourceEntity, source: String) {
        logPrefetchTrace(resource, "prefetch_decision", "source=$source")
    }

    private fun logPrefetchTrace(
        resource: ResourceRepresentable,
        stage: String,
        detail: String? = null,
    ) {
        val suffix = detail?.trim()?.takeIf { it.isNotEmpty() }?.let { " $it" }.orEmpty()
        val identifier = (resource as? ResourceEntity)?.identifier ?: "-"
        Timber.tag(PREFETCH_LOG_TAG).d(
            "resource=%s remote=%s stage=%s%s",
            identifier,
            resource.remoteId?.trim().orEmpty().ifEmpty { "-" },
            stage,
            suffix,
        )
    }

    private fun resolvePrefetchUploadSkipReason(
        resource: ResourceEntity,
        localThumbnailUri: String?,
    ): String {
        val remoteId = resource.remoteId?.trim().orEmpty()
        if (remoteId.isEmpty()) {
            return "no_remote_id"
        }
        if (!resource.resolveUsableRemoteThumbnailUri().isNullOrEmpty()) {
            return "remote_thumb_exists"
        }
        if (localThumbnailUri.isNullOrBlank()) {
            return "no_local_thumb"
        }
        return "predicate_false"
    }

    private const val PREFETCH_WINDOW_AHEAD = 10
    private const val PREFETCH_WINDOW_BEHIND = 4
    private const val PREFETCH_NETWORK_CONCURRENCY = 4
    private const val PREFETCH_DECRYPT_CONCURRENCY = 2
    private const val PREFETCH_WRITE_CONCURRENCY = 2
    private const val REMOTE_THUMBNAIL_FAILURE_COOLDOWN_MILLIS = 30_000L
    private const val MAX_LIFECYCLE_STATE_ENTRIES = 6_000
    private const val PREFETCH_LOG_TAG = "MediaPreviewPrefetch"

    private data class PreviewSnapshot(
        val uri: String,
        val source: String,
    )

    private data class ResourcePrefetchLifecycleState(
        var ready: Boolean = false,
        var mainFetchedOnce: Boolean = false,
        var cooldownUntilMillis: Long = 0L,
    )

    private enum class MainFallbackLifecycleDecision {
        ALLOWED,
        SKIPPED_ONCE,
        COOLDOWN,
    }
}

private val PreviewEnsureSource.logLabel: String
    get() = when (this) {
        PreviewEnsureSource.LOCAL_THUMB -> "local_thumb"
        PreviewEnsureSource.LOCAL_MAIN -> "local_main"
        PreviewEnsureSource.REMOTE_THUMB -> "remote_thumb"
        PreviewEnsureSource.MAIN_FALLBACK -> "main_fallback"
        PreviewEnsureSource.MAIN_FALLBACK_SKIPPED -> "skipped"
        PreviewEnsureSource.NONE -> "none"
    }
