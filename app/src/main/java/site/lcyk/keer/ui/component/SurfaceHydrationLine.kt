package site.lcyk.keer.ui.component

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import site.lcyk.keer.viewmodel.UiHydrationState

@Composable
fun SurfaceHydrationLine(
    hydrationState: UiHydrationState,
    modifier: Modifier = Modifier,
) {
    val visible = hydrationState.isHydrating
    val targetWidthFraction = when {
        !visible -> 0f
        hydrationState.hasWarmSnapshot -> 0.26f
        else -> 0.42f
    }
    val targetAlpha = when {
        !visible -> 0f
        hydrationState.hasWarmSnapshot -> 0.58f
        else -> 0.9f
    }
    val targetHeight = if (hydrationState.hasWarmSnapshot) 2.dp else 3.dp
    val widthFraction by animateFloatAsState(
        targetValue = targetWidthFraction,
        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
        label = "surface_hydration_width",
    )
    val alpha by animateFloatAsState(
        targetValue = targetAlpha,
        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
        label = "surface_hydration_alpha",
    )

    if (widthFraction <= 0f || alpha <= 0f) {
        return
    }

    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(widthFraction.coerceIn(0f, 1f))
                .height(targetHeight)
                .clip(RoundedCornerShape(999.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = alpha)),
        )
    }
}

@Composable
fun BoxScope.SurfaceHydrationLineOverlay(
    hydrationState: UiHydrationState,
    topPadding: Dp = 10.dp,
) {
    SurfaceHydrationLine(
        hydrationState = hydrationState,
        modifier = Modifier
            .align(Alignment.TopCenter)
            .padding(top = topPadding),
    )
}
