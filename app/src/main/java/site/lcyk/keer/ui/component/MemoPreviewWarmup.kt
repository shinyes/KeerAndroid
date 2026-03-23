package site.lcyk.keer.ui.component

import androidx.collection.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import site.lcyk.keer.data.local.entity.MemoEntity
import site.lcyk.keer.util.extractPreviewContent

internal data class MemoPreviewSnapshot(
    val text: String,
    val previewed: Boolean,
)

@Composable
internal fun MemoPreviewWarmupEffect(
    memos: List<MemoEntity>,
    enabled: Boolean = true,
    warmupCount: Int = MEMO_PREVIEW_WARMUP_COUNT,
    warmupDelayMillis: Long = MEMO_PREVIEW_WARMUP_DELAY_MS,
) {
    val contentSignature = remember(memos) {
        memos
            .asSequence()
            .take(warmupCount)
            .map { memo -> memo.content.hashCode() }
            .toList()
    }
    LaunchedEffect(enabled, contentSignature, warmupCount, warmupDelayMillis) {
        if (!enabled || memos.isEmpty()) {
            return@LaunchedEffect
        }
        if (warmupDelayMillis > 0L) {
            delay(warmupDelayMillis)
        }
        withContext(Dispatchers.Default) {
            warmupMemoPreviews(
                memoContents = memos.asSequence().map { memo -> memo.content },
                warmupCount = warmupCount,
            )
        }
    }
}

internal fun resolveMemoPreviewSnapshot(
    markdownText: String,
): MemoPreviewSnapshot {
    MemoPreviewCache.get(markdownText)?.let { cached ->
        return cached
    }
    val resolved = extractPreviewContent(markdownText)
        .let { (text, previewed) ->
            MemoPreviewSnapshot(text = text, previewed = previewed)
        }
    MemoPreviewCache.put(markdownText, resolved)
    return resolved
}

internal fun warmupMemoPreviews(
    memoContents: Sequence<String>,
    warmupCount: Int = MEMO_PREVIEW_WARMUP_COUNT,
) {
    memoContents
        .take(warmupCount.coerceAtLeast(0))
        .forEach { content ->
            resolveMemoPreviewSnapshot(content)
        }
}

internal fun clearMemoPreviewCacheForTest() {
    MemoPreviewCache.clear()
}

internal fun memoPreviewCacheSizeForTest(): Int {
    return MemoPreviewCache.size()
}

private object MemoPreviewCache {
    private val cache = object : LruCache<String, MemoPreviewSnapshot>(MEMO_PREVIEW_CACHE_LIMIT) {}

    fun get(content: String): MemoPreviewSnapshot? {
        return synchronized(cache) {
            cache[content]
        }
    }

    fun put(content: String, snapshot: MemoPreviewSnapshot) {
        synchronized(cache) {
            cache.put(content, snapshot)
        }
    }

    fun clear() {
        synchronized(cache) {
            cache.evictAll()
        }
    }

    fun size(): Int {
        return synchronized(cache) {
            cache.size()
        }
    }
}

internal const val MEMO_PREVIEW_WARMUP_COUNT = 12
internal const val MEMO_PREVIEW_WARMUP_DELAY_MS = 140L
private const val MEMO_PREVIEW_CACHE_LIMIT = 1_024
