package site.lcyk.keer.ui.component

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BoxScope.PullSyncLineIndicator(
    refreshState: PullToRefreshState,
    syncing: Boolean,
    hapticFeedback: HapticFeedback
) {
    val rawPullFraction = refreshState.distanceFraction
    val pullFraction = rawPullFraction.coerceIn(0f, 1f)
    val isPulling = rawPullFraction > 0f
    val readyToRefresh = !syncing && rawPullFraction >= 1f
    var thresholdHapticTriggered by remember { mutableStateOf(false) }

    LaunchedEffect(readyToRefresh, syncing) {
        if (readyToRefresh && !thresholdHapticTriggered) {
            hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
            thresholdHapticTriggered = true
        } else if (!readyToRefresh) {
            thresholdHapticTriggered = false
        }
    }

    val targetWidthFraction = when {
        syncing -> 0.36f
        readyToRefresh -> 0.42f
        else -> 0.12f + (0.28f * pullFraction)
    }
    val targetAlpha = when {
        syncing -> 0.9f
        readyToRefresh -> 0.95f
        pullFraction > 0f -> 0.2f + (0.7f * pullFraction)
        else -> 0f
    }
    val widthFraction by animateFloatAsState(
        targetValue = targetWidthFraction,
        animationSpec = tween(
            durationMillis = if (isPulling) 90 else 260,
            easing = FastOutSlowInEasing
        ),
        label = "pull_indicator_width"
    )
    val alpha by animateFloatAsState(
        targetValue = targetAlpha,
        animationSpec = tween(
            durationMillis = if (isPulling || syncing) 90 else 340,
            easing = FastOutSlowInEasing
        ),
        label = "pull_indicator_alpha"
    )

    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .align(Alignment.TopCenter)
            .padding(top = 8.dp)
            .height(3.dp)
            .fillMaxWidth(widthFraction.coerceIn(0f, 1f))
            .clip(RoundedCornerShape(999.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = alpha))
    )
}
