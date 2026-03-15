package site.lcyk.keer.ui.page.memos

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.tween
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.PermanentDrawerSheet
import androidx.compose.material3.PermanentNavigationDrawer
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.rememberNavController
import androidx.window.core.layout.WindowSizeClass
import kotlinx.coroutines.launch
import site.lcyk.keer.ui.component.SideDrawer
import site.lcyk.keer.viewmodel.LocalMemos
import site.lcyk.keer.viewmodel.MemoUiScope
import site.lcyk.keer.viewmodel.UiInteractionType

@Composable
fun MemosPage(
    startDestination: String = site.lcyk.keer.ui.page.common.RouteName.MEMOS
) {
    val windowSizeClass = currentWindowAdaptiveInfo().windowSizeClass
    val isExpanded = windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND)
    val memosViewModel = LocalMemos.current
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val memosNavController = rememberNavController()
    val density = LocalDensity.current

    DisposableEffect(drawerState, isExpanded) {
        if (!isExpanded) {
            drawerState.applyFastSwipeAlgorithm(density = density)
        }
        onDispose {}
    }

    BackHandler(enabled = drawerState.isOpen) {
        scope.launch {
            drawerState.close()
        }
    }

    LaunchedEffect(drawerState, isExpanded) {
        if (isExpanded) {
            memosViewModel.setInteractionActive(MemoUiScope.DRAWER, UiInteractionType.DRAWER_TRANSITION, false)
            return@LaunchedEffect
        }
        snapshotFlow { drawerState.currentValue != drawerState.targetValue }
            .collect { inTransition ->
                memosViewModel.setInteractionActive(MemoUiScope.DRAWER, UiInteractionType.DRAWER_TRANSITION, inTransition)
            }
    }

    DisposableEffect(isExpanded) {
        onDispose {
            memosViewModel.setInteractionActive(MemoUiScope.DRAWER, UiInteractionType.DRAWER_TRANSITION, false)
        }
    }

    if (isExpanded) {
        PermanentNavigationDrawer(
            drawerContent = {
                PermanentDrawerSheet {
                    SideDrawer(
                        memosNavController = memosNavController,
                    )
                }
            }
        ) {
            MemosNavigation(
                navController = memosNavController,
                startDestination = startDestination
            )
        }
    } else {
        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                ModalDrawerSheet {
                    SideDrawer(
                        memosNavController = memosNavController,
                        drawerState = drawerState
                    )
                }
            }
        ) {
            MemosNavigation(
                drawerState = drawerState,
                navController = memosNavController,
                startDestination = startDestination
            )
        }
    }
}

private fun DrawerState.applyFastSwipeAlgorithm(
    density: androidx.compose.ui.unit.Density
) {
    // Keep velocity-friendly behavior, but smooth the visual transition curve.
    val smoothFastEasing = CubicBezierEasing(0.2f, 0.0f, 0.0f, 1.0f)
    val dragSettleSpec = tween<Float>(durationMillis = 132, easing = smoothFastEasing)
    val openSpec = tween<Float>(durationMillis = 168, easing = smoothFastEasing)
    val closeSpec = tween<Float>(durationMillis = 150, easing = smoothFastEasing)

    setDrawerMotionSpec("setAnchoredDraggableMotionSpec\$material3", dragSettleSpec)
    setDrawerMotionSpec("setOpenDrawerMotionSpec\$material3", openSpec)
    setDrawerMotionSpec("setCloseDrawerMotionSpec\$material3", closeSpec)

    // Make high-speed flicks win even with short travel distance.
    val velocityThresholdPx = with(density) { 460.dp.toPx() }
    val decaySpec = exponentialDecay<Float>(frictionMultiplier = 3.6f)
    setAnchoredDraggableConfig(
        positionalThresholdFraction = 0.24f,
        velocityThresholdPx = velocityThresholdPx,
        snapSpec = dragSettleSpec,
        decaySpec = decaySpec
    )
}

private fun DrawerState.setDrawerMotionSpec(
    methodName: String,
    spec: FiniteAnimationSpec<Float>
) {
    runCatching {
        javaClass
            .getMethod(methodName, FiniteAnimationSpec::class.java)
            .invoke(this, spec)
    }
}

private fun DrawerState.setAnchoredDraggableConfig(
    positionalThresholdFraction: Float,
    velocityThresholdPx: Float,
    snapSpec: FiniteAnimationSpec<Float>,
    decaySpec: androidx.compose.animation.core.DecayAnimationSpec<Float>
) {
    val anchoredState = runCatching {
        javaClass
            .getMethod("getAnchoredDraggableState\$material3")
            .invoke(this)
    }.getOrNull() ?: return

    runCatching {
        anchoredState.javaClass
            .getMethod("setPositionalThreshold\$foundation", kotlin.Function1::class.java)
            .invoke(anchoredState, { distance: Float -> distance * positionalThresholdFraction })
    }

    runCatching {
        anchoredState.javaClass
            .getMethod("setVelocityThreshold\$foundation", kotlin.Function0::class.java)
            .invoke(anchoredState, { velocityThresholdPx })
    }

    runCatching {
        anchoredState.javaClass
            .getMethod("setSnapAnimationSpec\$foundation", androidx.compose.animation.core.AnimationSpec::class.java)
            .invoke(anchoredState, snapSpec)
    }

    runCatching {
        anchoredState.javaClass
            .getMethod("setDecayAnimationSpec\$foundation", androidx.compose.animation.core.DecayAnimationSpec::class.java)
            .invoke(anchoredState, decaySpec)
    }
}
