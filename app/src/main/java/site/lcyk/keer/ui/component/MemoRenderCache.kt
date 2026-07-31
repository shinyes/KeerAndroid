package site.lcyk.keer.ui.component

import androidx.collection.LruCache
import site.lcyk.keer.data.local.entity.MemoEntity
import site.lcyk.keer.data.model.MemoRepresentable
import site.lcyk.keer.data.model.ResourceRepresentable

internal data class MemoRenderVersionKey(
    val memoId: String,
    val memoVersionMillis: Long,
    val contentHash: Int,
    val resourceSignature: Int,
) {
    val stableKey: String = buildString(96) {
        append(memoId)
        append('|')
        append(memoVersionMillis)
        append('|')
        append(contentHash)
        append('|')
        append(resourceSignature)
    }
}

internal fun buildMemoRenderVersionKey(
    memo: MemoRepresentable,
): MemoRenderVersionKey {
    val memoId = when (memo) {
        is MemoEntity -> memo.identifier
        else -> memo.remoteId?.trim().orEmpty().ifBlank {
            "${memo.date.toEpochMilli()}|${memo.content.hashCode()}"
        }
    }
    // memoVersionMillis anchors the key to a stable memo identity (creation date) rather than
    // lastModified, which also flips on pin/archive/needsSync and would otherwise invalidate
    // the preview cache on every non-content change. Render-relevant changes are captured by
    // contentHash and resourceSignature.
    val memoVersionMillis = memo.date.toEpochMilli()
    return MemoRenderVersionKey(
        memoId = memoId,
        memoVersionMillis = memoVersionMillis,
        contentHash = memo.content.hashCode(),
        resourceSignature = computeResourceSignature(memo.resources),
    )
}

internal fun buildUntrackedMemoRenderVersionKey(
    markdownText: String,
): MemoRenderVersionKey {
    return MemoRenderVersionKey(
        memoId = "untracked",
        memoVersionMillis = 0L,
        contentHash = markdownText.hashCode(),
        resourceSignature = 0,
    )
}

internal object MemoRenderCache {
    private val previewSnapshotCache =
        object : LruCache<String, MemoPreviewSnapshot>(MEMO_RENDER_CACHE_MAX_BYTES) {
            override fun sizeOf(key: String, value: MemoPreviewSnapshot): Int {
                return estimatePreviewSnapshotBytes(key = key, value = value)
            }
        }

    fun getPreview(versionKey: MemoRenderVersionKey): MemoPreviewSnapshot? {
        return synchronized(previewSnapshotCache) {
            previewSnapshotCache.get(versionKey.stableKey)
        }
    }

    fun putPreview(
        versionKey: MemoRenderVersionKey,
        snapshot: MemoPreviewSnapshot,
    ) {
        synchronized(previewSnapshotCache) {
            previewSnapshotCache.put(versionKey.stableKey, snapshot)
        }
    }

    fun clear() {
        synchronized(previewSnapshotCache) {
            previewSnapshotCache.evictAll()
        }
    }

    fun size(): Int {
        return synchronized(previewSnapshotCache) {
            previewSnapshotCache.snapshot().size
        }
    }
}

private fun computeResourceSignature(
    resources: List<ResourceRepresentable>,
): Int {
    var signature = resources.size
    resources.forEach { resource ->
        signature = 31 * signature + (resource.remoteId?.hashCode() ?: resource.uri.hashCode())
        signature = 31 * signature + resource.filename.hashCode()
        signature = 31 * signature + (resource.mimeType?.hashCode() ?: 0)
        signature = 31 * signature + (resource.localUri?.hashCode() ?: 0)
        signature = 31 * signature + (resource.thumbnailUri?.hashCode() ?: 0)
        signature = 31 * signature + (resource.thumbnailLocalUri?.hashCode() ?: 0)
        signature = 31 * signature + (resource.encryptionMetadata?.hashCode() ?: 0)
    }
    return signature
}

private fun estimatePreviewSnapshotBytes(
    key: String,
    value: MemoPreviewSnapshot,
): Int {
    val keyBytes = key.length * 2
    val textBytes = value.text.length * 2
    val flagsBytes = 16
    val objectOverheadBytes = 96
    return keyBytes + textBytes + flagsBytes + objectOverheadBytes
}

private const val MEMO_RENDER_CACHE_MAX_BYTES = 64 * 1024 * 1024
