package site.lcyk.keer.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import site.lcyk.keer.data.local.entity.MemoEntity
import site.lcyk.keer.data.model.MemoRepresentable
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
    val versionSignature = remember(memos, warmupCount) {
        memos
            .asSequence()
            .take(warmupCount)
            .map { memo -> buildMemoRenderVersionKey(memo).stableKey.hashCode() }
            .toList()
    }
    LaunchedEffect(enabled, versionSignature, warmupCount, warmupDelayMillis) {
        if (!enabled || memos.isEmpty()) {
            return@LaunchedEffect
        }
        if (warmupDelayMillis > 0L) {
            delay(warmupDelayMillis)
        }
        withContext(Dispatchers.Default) {
            warmupMemoPreviews(
                memos = memos.asSequence(),
                warmupCount = warmupCount,
                batchSize = MEMO_PREVIEW_WARMUP_BATCH_SIZE,
                interBatchDelayMillis = MEMO_PREVIEW_WARMUP_FRAME_BUDGET_DELAY_MS,
            )
        }
    }
}

internal fun resolveMemoPreviewSnapshot(
    markdownText: String,
    versionKey: MemoRenderVersionKey = buildUntrackedMemoRenderVersionKey(markdownText),
): MemoPreviewSnapshot {
    MemoRenderCache.getPreview(versionKey)?.let { cached ->
        return cached
    }
    val resolved = extractPreviewContent(markdownText)
        .let { (text, previewed) ->
            MemoPreviewSnapshot(text = text, previewed = previewed)
        }
    MemoRenderCache.putPreview(versionKey, resolved)
    return resolved
}

internal suspend fun warmupMemoPreviews(
    memos: Sequence<MemoRepresentable>,
    warmupCount: Int = MEMO_PREVIEW_WARMUP_COUNT,
    batchSize: Int = MEMO_PREVIEW_WARMUP_BATCH_SIZE,
    interBatchDelayMillis: Long = MEMO_PREVIEW_WARMUP_FRAME_BUDGET_DELAY_MS,
) {
    val targets = memos.take(warmupCount.coerceAtLeast(0)).toList()
    val normalizedBatchSize = batchSize.coerceAtLeast(1)
    var cursor = 0
    while (cursor < targets.size) {
        val endExclusive = (cursor + normalizedBatchSize).coerceAtMost(targets.size)
        for (index in cursor until endExclusive) {
            val memo = targets[index]
            resolveMemoPreviewSnapshot(
                markdownText = memo.content,
                versionKey = buildMemoRenderVersionKey(memo),
            )
        }
        cursor = endExclusive
        if (cursor < targets.size) {
            yield()
            if (interBatchDelayMillis > 0L) {
                delay(interBatchDelayMillis)
            }
        }
    }
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
    MemoRenderCache.clear()
}

internal fun memoPreviewCacheSizeForTest(): Int {
    return MemoRenderCache.size()
}

internal const val MEMO_PREVIEW_WARMUP_COUNT = 24
internal const val MEMO_PREVIEW_WARMUP_DELAY_MS = 140L
private const val MEMO_PREVIEW_WARMUP_BATCH_SIZE = 3
private const val MEMO_PREVIEW_WARMUP_FRAME_BUDGET_DELAY_MS = 16L
