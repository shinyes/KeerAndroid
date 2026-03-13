package site.lcyk.keer.ui.component

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector

data class MemoMenuConfirmation(
    val title: String,
    val message: String? = null,
    val confirmLabel: String,
    val cancelLabel: String
)

data class MemoMenuAction(
    val key: String,
    val label: String,
    val icon: ImageVector? = null,
    val destructive: Boolean = false,
    val confirmation: MemoMenuConfirmation? = null,
    val onSelected: () -> Unit
)

@Composable
fun MemoActionMenuButton(actions: List<MemoMenuAction>) {
    if (actions.isEmpty()) {
        return
    }

    val orderedActions = remember(actions) {
        val normal = actions.filterNot { it.destructive }
        val destructive = actions.filter { it.destructive }
        normal + destructive
    }
    var menuExpanded by remember { mutableStateOf(false) }
    var pendingConfirmation by remember { mutableStateOf<MemoMenuAction?>(null) }

    Box {
        IconButton(onClick = { menuExpanded = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = null)
        }

        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false }
        ) {
            orderedActions.forEach { action ->
                DropdownMenuItem(
                    text = { Text(text = action.label) },
                    onClick = {
                        menuExpanded = false
                        if (action.confirmation != null) {
                            pendingConfirmation = action
                        } else {
                            action.onSelected()
                        }
                    },
                    leadingIcon = action.icon?.let { icon ->
                        {
                            Icon(
                                imageVector = icon,
                                contentDescription = null
                            )
                        }
                    },
                    colors = if (action.destructive) {
                        MenuDefaults.itemColors(
                            textColor = MaterialTheme.colorScheme.error,
                            leadingIconColor = MaterialTheme.colorScheme.error
                        )
                    } else {
                        MenuDefaults.itemColors()
                    }
                )
            }
        }
    }

    val confirmationAction = pendingConfirmation
    if (confirmationAction != null && confirmationAction.confirmation != null) {
        val confirmation = confirmationAction.confirmation
        AlertDialog(
            onDismissRequest = { pendingConfirmation = null },
            title = { Text(text = confirmation.title) },
            text = confirmation.message?.let { message ->
                { Text(text = message) }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingConfirmation = null
                        confirmationAction.onSelected()
                    }
                ) {
                    Text(text = confirmation.confirmLabel)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingConfirmation = null }) {
                    Text(text = confirmation.cancelLabel)
                }
            }
        )
    }
}
