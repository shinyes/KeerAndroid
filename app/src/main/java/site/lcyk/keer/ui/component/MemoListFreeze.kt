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
