package site.lcyk.keer.ui.page.common

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.DrawerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import kotlinx.coroutines.launch
import site.lcyk.keer.R
import site.lcyk.keer.ext.string

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PageScaffold(
    title: String,
    drawerState: DrawerState? = null,
    onMenuButtonOpenRequested: (() -> Unit)? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    floatingActionButton: @Composable (() -> Unit) = {},
    content: @Composable (PaddingValues) -> Unit
) {
    val scope = rememberCoroutineScope()
    val hapticFeedback = LocalHapticFeedback.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = title) },
                navigationIcon = {
                    when {
                        drawerState != null -> {
                            IconButton(
                                onClick = {
                                    onMenuButtonOpenRequested?.invoke()
                                    hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                                    scope.launch { drawerState.open() }
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Menu,
                                    contentDescription = R.string.menu.string
                                )
                            }
                        }

                        onBack != null -> {
                            IconButton(onClick = onBack) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = R.string.back.string
                                )
                            }
                        }
                    }
                },
                actions = actions
            )
        },
        floatingActionButton = floatingActionButton,
        content = content
    )
}
