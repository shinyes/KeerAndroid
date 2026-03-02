package site.lcyk.keer.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback

@Composable
fun rememberListEdgeHaptics(
    itemCount: Int,
    atTop: Boolean,
    atBottom: Boolean,
    feedbackType: HapticFeedbackType = HapticFeedbackType.LongPress
) {
    val hapticFeedback = LocalHapticFeedback.current
    var topHapticArmed by remember { mutableStateOf(false) }
    var bottomHapticArmed by remember { mutableStateOf(false) }

    LaunchedEffect(itemCount) {
        if (itemCount <= 0) {
            topHapticArmed = false
            bottomHapticArmed = false
            return@LaunchedEffect
        }
        topHapticArmed = !atTop
        bottomHapticArmed = !atBottom
    }

    LaunchedEffect(atTop, atBottom, itemCount) {
        if (itemCount <= 0) {
            return@LaunchedEffect
        }

        var shouldVibrate = false
        if (!atTop) {
            topHapticArmed = true
        } else if (topHapticArmed) {
            shouldVibrate = true
            topHapticArmed = false
        }

        if (!atBottom) {
            bottomHapticArmed = true
        } else if (bottomHapticArmed) {
            shouldVibrate = true
            bottomHapticArmed = false
        }

        if (shouldVibrate) {
            hapticFeedback.performHapticFeedback(feedbackType)
        }
    }
}
