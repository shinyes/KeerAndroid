package site.lcyk.keer.ui.component

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

private enum class PullSyncLineVisualState {
    Hidden,
    Pulling,
    Ready,
    Settling,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BoxScope.PullSyncLineIndicator(
    refreshState: PullToRefreshState,
    syncing: Boolean
) {
    val rawPullFraction = refreshState.distanceFraction
    val pullFraction = rawPullFraction.coerceIn(0f, 1f)
    val isPulling = rawPullFraction > 0f
    val readyToRefresh = rawPullFraction >= 1f
    var refreshArmed by remember { mutableStateOf(false) }
    var settling by remember { mutableStateOf(false) }
    var wasPulling by remember { mutableStateOf(false) }

    LaunchedEffect(isPulling, readyToRefresh) {
        if (isPulling) {
            refreshArmed = readyToRefresh
        }
    }

    LaunchedEffect(isPulling) {
        if (isPulling) {
            wasPulling = true
            settling = false
            return@LaunchedEffect
        }
        if (!wasPulling) {
            return@LaunchedEffect
        }
        settling = true
        delay(180)
        settling = false
        wasPulling = false
        if (!refreshArmed) {
            return@LaunchedEffect
        }
    }

    LaunchedEffect(syncing) {
        if (!syncing) {
            refreshArmed = false
        }
    }

    val showSpinner = syncing && refreshArmed && !isPulling && !settling

    val visualState = when {
        readyToRefresh && isPulling -> PullSyncLineVisualState.Ready
        isPulling -> PullSyncLineVisualState.Pulling
        settling -> PullSyncLineVisualState.Settling
        else -> PullSyncLineVisualState.Hidden
    }

    val targetWidthFraction = when (visualState) {
        PullSyncLineVisualState.Ready -> 0.42f
        PullSyncLineVisualState.Pulling -> 0.12f + (0.28f * pullFraction)
        PullSyncLineVisualState.Settling -> 0.14f
        PullSyncLineVisualState.Hidden -> 0f
    }
    val targetAlpha = when (visualState) {
        PullSyncLineVisualState.Ready -> 0.95f
        PullSyncLineVisualState.Pulling -> 0.2f + (0.7f * pullFraction)
        PullSyncLineVisualState.Settling -> 0.12f
        PullSyncLineVisualState.Hidden -> 0f
    }
    val widthFraction by animateFloatAsState(
        targetValue = targetWidthFraction,
        animationSpec = tween(
            durationMillis = when (visualState) {
                PullSyncLineVisualState.Pulling -> 90
                PullSyncLineVisualState.Ready -> 140
                PullSyncLineVisualState.Settling -> 180
                PullSyncLineVisualState.Hidden -> 180
            },
            easing = FastOutSlowInEasing
        ),
        label = "pull_indicator_width"
    )
    val alpha by animateFloatAsState(
        targetValue = targetAlpha,
        animationSpec = tween(
            durationMillis = when (visualState) {
                PullSyncLineVisualState.Pulling -> 90
                PullSyncLineVisualState.Ready -> 130
                PullSyncLineVisualState.Settling -> 190
                PullSyncLineVisualState.Hidden -> 190
            },
            easing = FastOutSlowInEasing
        ),
        label = "pull_indicator_alpha"
    )

    if (widthFraction > 0f && alpha > 0f) {
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

    if (showSpinner) {
        CircularProgressIndicator(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 12.dp)
                .size(18.dp),
            strokeWidth = 2.dp
        )
    }
}
