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

internal object MediaPreviewPrefetchCoordinator {
    private val networkSemaphore = Semaphore(permits = PREFETCH_NETWORK_CONCURRENCY)
    private val decryptSemaphore = Semaphore(permits = PREFETCH_DECRYPT_CONCURRENCY)
    private val writeSemaphore = Semaphore(permits = PREFETCH_WRITE_CONCURRENCY)
    private val inFlightMutex = Mutex()
    private val inFlightKeys = linkedSetOf<String>()

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
        )
    }

    suspend fun prefetchResources(
        context: Context,
        okHttpClient: OkHttpClient,
        currentAccountKey: String?,
        resources: List<ResourceEntity>,
        cacheResourceFile: suspend (String, Uri) -> ApiResponse<Unit>,
        cacheResourceThumbnail: suspend (String, Uri) -> ApiResponse<Unit>,
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
    ) {
        if (!resource.isMediaResource()) {
            return
        }
        if (resource.shouldUseUntrackedPrefetch()) {
            prefetchUntrackedResourcePreview(
                context = context,
                okHttpClient = okHttpClient,
                resource = resource,
                currentAccountKey = currentAccountKey,
            )
            return
        }
        resolveExistingPreviewUri(resource)?.let { previewUri ->
            MediaPreviewRuntimeCache.rememberPreviewUri(previewCacheKey(resource), previewUri)
            return
        }
        val inFlightKey = "${resource.identifier}|${resource.thumbnailUri.orEmpty()}|${resource.uri}"
        if (!acquireInFlight(inFlightKey)) {
            return
        }
        try {
            networkSemaphore.withPermit {
                decryptSemaphore.withPermit {
                    when {
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
                            )
                        }
                    }
                }
            }
            resolveExistingPreviewUri(resource)?.let { previewUri ->
                MediaPreviewRuntimeCache.rememberPreviewUri(previewCacheKey(resource), previewUri)
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
            return cached
        }
        val inFlightKey = "untracked:$cacheKey"
        if (!acquireInFlight(inFlightKey)) {
            return null
        }
        try {
            val previewFile = networkSemaphore.withPermit {
                decryptSemaphore.withPermit {
                    when {
                        resource.isVideoResource() -> {
                            val remoteThumbnail = resource.thumbnailUri?.trim().orEmpty()
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
                            val remoteThumbnail = resource.thumbnailUri?.trim().orEmpty()
                            if (remoteThumbnail.isHttpUrl()) {
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
                            } else {
                                val remoteMain = resource.uri.trim()
                                if (!remoteMain.isHttpUrl()) {
                                    null
                                } else {
                                    downloadResourceVariantToTemp(
                                        context = context,
                                        okHttpClient = okHttpClient,
                                        resource = resource,
                                        accountKey = resolveResourceAccountKey(resource, currentAccountKey),
                                        url = remoteMain,
                                        filename = resource.filename,
                                        variant = EncryptedBlobVariant.MAIN,
                                        cacheDirName = "attachment_cache",
                                        prefix = "attachment_",
                                    )
                                }
                            }
                        }
                    }
                }
            } ?: return null
            val previewUri = previewFile.toUri().toString()
            MediaPreviewRuntimeCache.rememberPreviewUri(cacheKey, previewUri)
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

    private fun resolveExistingPreviewUri(resource: ResourceEntity): String? {
        val localThumbnail = resolveUsableThumbnailLocalUri(resource.thumbnailLocalUri)
        if (!localThumbnail.isNullOrBlank()) {
            return localThumbnail
        }
        val localUri = resource.localUri?.trim().orEmpty()
        if (localUri.isNotEmpty()) {
            val uri = localUri.toUri()
            if (uri.scheme != "file") {
                return localUri
            }
            val file = uri.path?.let(::File)
            if (file != null && file.exists() && file.length() > 0L) {
                return localUri
            }
        }
        return null
    }

    private const val PREFETCH_WINDOW_AHEAD = 10
    private const val PREFETCH_WINDOW_BEHIND = 4
    private const val PREFETCH_NETWORK_CONCURRENCY = 4
    private const val PREFETCH_DECRYPT_CONCURRENCY = 2
    private const val PREFETCH_WRITE_CONCURRENCY = 2
}

private fun ResourceEntity.shouldUseUntrackedPrefetch(): Boolean {
    val memoKey = memoId?.trim().orEmpty()
    return memoKey.startsWith("explore:") || memoKey.startsWith("group:")
}
