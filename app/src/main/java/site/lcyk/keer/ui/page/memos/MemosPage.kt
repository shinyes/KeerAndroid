package site.lcyk.keer.ui.page.memos

import androidx.activity.compose.BackHandler
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.PermanentDrawerSheet
import androidx.compose.material3.PermanentNavigationDrawer
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.navigation.compose.rememberNavController
import androidx.window.core.layout.WindowSizeClass
import kotlinx.coroutines.launch
import site.lcyk.keer.ui.component.SideDrawer

@Composable
fun MemosPage() {
    val windowSizeClass = currentWindowAdaptiveInfo().windowSizeClass
    val isExpanded = windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND)
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val memosNavController = rememberNavController()
    val hapticFeedback = LocalHapticFeedback.current
    var suppressNextCloseHaptic by remember { mutableStateOf(false) }
    var lastDrawerValue by remember { mutableStateOf(drawerState.currentValue) }

    BackHandler(enabled = drawerState.isOpen) {
        suppressNextCloseHaptic = true
        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
        scope.launch {
            drawerState.close()
        }
    }

    LaunchedEffect(drawerState.currentValue, isExpanded) {
        if (isExpanded) return@LaunchedEffect
        val currentValue = drawerState.currentValue
        if (currentValue == lastDrawerValue) {
            return@LaunchedEffect
        }
        if (currentValue == DrawerValue.Closed) {
            if (suppressNextCloseHaptic) {
                suppressNextCloseHaptic = false
            } else {
                hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
            }
        }
        lastDrawerValue = currentValue
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
                navController = memosNavController
            )
        }
    } else {
        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                ModalDrawerSheet {
                    SideDrawer(
                        memosNavController = memosNavController,
                        drawerState = drawerState,
                        onDrawerItemCloseRequested = {
                            suppressNextCloseHaptic = true
                        }
                    )
                }
            }
        ) {
            MemosNavigation(
                drawerState = drawerState,
                navController = memosNavController
            )
        }
    }
}
