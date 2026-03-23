package site.lcyk.keer.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.transformLatest

@Composable
internal fun rememberDelayedScrollFreeze(
    isScrollInProgressProvider: () -> Boolean,
    startDelayMillis: Long = UI_FREEZE_START_DELAY_MS,
    releaseHoldMillis: Long = UI_FREEZE_RELEASE_HOLD_MS,
): Boolean {
    val currentProvider by rememberUpdatedState(isScrollInProgressProvider)
    val frozen by produceState(
        initialValue = false,
        startDelayMillis,
        releaseHoldMillis,
    ) {
        snapshotFlow { currentProvider() }
            .distinctUntilChanged()
            .delayedScrollFreezeFlow(
                startDelayMillis = startDelayMillis,
                releaseHoldMillis = releaseHoldMillis,
            )
            .collect { delayedFrozen ->
                value = delayedFrozen
            }
    }
    return frozen
}

internal data class ScrollResumeGates(
    val prefetchAllowed: Boolean,
    val warmupAllowed: Boolean,
)

@Composable
internal fun rememberScrollResumeGates(
    isScrollInProgressProvider: () -> Boolean,
    prefetchResumeDelayMillis: Long = POST_SCROLL_PREFETCH_RESUME_DELAY_MS,
    warmupResumeDelayMillis: Long = POST_SCROLL_WARMUP_RESUME_DELAY_MS,
): ScrollResumeGates {
    val currentProvider by rememberUpdatedState(isScrollInProgressProvider)
    val gates by produceState(
        initialValue = ScrollResumeGates(
            prefetchAllowed = false,
            warmupAllowed = false,
        ),
        prefetchResumeDelayMillis,
        warmupResumeDelayMillis,
    ) {
        snapshotFlow { currentProvider() }
            .distinctUntilChanged()
            .scrollResumeGatesFlow(
                prefetchResumeDelayMillis = prefetchResumeDelayMillis,
                warmupResumeDelayMillis = warmupResumeDelayMillis,
            )
            .collect { stagedGates ->
                value = stagedGates
            }
    }
    return gates
}

@OptIn(ExperimentalCoroutinesApi::class)
internal fun Flow<Boolean>.scrollResumeGatesFlow(
    prefetchResumeDelayMillis: Long = POST_SCROLL_PREFETCH_RESUME_DELAY_MS,
    warmupResumeDelayMillis: Long = POST_SCROLL_WARMUP_RESUME_DELAY_MS,
): Flow<ScrollResumeGates> {
    val safePrefetchDelay = prefetchResumeDelayMillis.coerceAtLeast(0L)
    val safeWarmupDelay = warmupResumeDelayMillis.coerceAtLeast(safePrefetchDelay)
    return transformLatest { isScrolling ->
        if (isScrolling) {
            emit(
                ScrollResumeGates(
                    prefetchAllowed = false,
                    warmupAllowed = false,
                )
            )
        } else {
            if (safePrefetchDelay > 0L) {
                delay(safePrefetchDelay)
            }
            emit(
                ScrollResumeGates(
                    prefetchAllowed = true,
                    warmupAllowed = false,
                )
            )
            val extraWarmupDelay = safeWarmupDelay - safePrefetchDelay
            if (extraWarmupDelay > 0L) {
                delay(extraWarmupDelay)
            }
            emit(
                ScrollResumeGates(
                    prefetchAllowed = true,
                    warmupAllowed = true,
                )
            )
        }
    }
        .onStart {
            emit(
                ScrollResumeGates(
                    prefetchAllowed = false,
                    warmupAllowed = false,
                )
            )
        }
        .distinctUntilChanged()
}

@OptIn(ExperimentalCoroutinesApi::class)
internal fun Flow<Boolean>.delayedScrollFreezeFlow(
    startDelayMillis: Long = UI_FREEZE_START_DELAY_MS,
    releaseHoldMillis: Long = UI_FREEZE_RELEASE_HOLD_MS,
): Flow<Boolean> {
    return transformLatest { isScrolling ->
        if (isScrolling) {
            if (startDelayMillis > 0L) {
                delay(startDelayMillis)
            }
            emit(true)
        } else {
            if (releaseHoldMillis > 0L) {
                delay(releaseHoldMillis)
            }
            emit(false)
        }
    }
        .onStart { emit(false) }
        .distinctUntilChanged()
}

internal const val UI_FREEZE_START_DELAY_MS = 120L
internal const val UI_FREEZE_RELEASE_HOLD_MS = 220L
internal const val POST_SCROLL_PREFETCH_RESUME_DELAY_MS = 380L
internal const val POST_SCROLL_WARMUP_RESUME_DELAY_MS = 620L
