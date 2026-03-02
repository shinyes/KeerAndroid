package site.lcyk.keer.ui.component

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import site.lcyk.keer.R
import site.lcyk.keer.ext.string
import site.lcyk.keer.viewmodel.ManualSyncResult

sealed class SyncAlertState {
    data class Blocked(val message: String) : SyncAlertState()
    data class Failed(val message: String) : SyncAlertState()
}

fun handleManualSyncResult(result: ManualSyncResult): SyncAlertState? {
    return when (result) {
        ManualSyncResult.Completed -> null
        is ManualSyncResult.Blocked -> SyncAlertState.Blocked(result.message)
        is ManualSyncResult.Failed -> SyncAlertState.Failed(result.message)
    }
}

@Composable
fun SyncAlertDialog(
    alert: SyncAlertState?,
    onDismiss: () -> Unit
) {
    when (alert) {
        null -> Unit
        is SyncAlertState.Blocked -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text(R.string.unsupported_memos_version_title.string) },
                text = { Text(alert.message) },
                confirmButton = {
                    TextButton(onClick = onDismiss) {
                        Text(R.string.close.string)
                    }
                }
            )
        }

        is SyncAlertState.Failed -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text(R.string.sync_failed.string) },
                text = { Text(alert.message) },
                confirmButton = {
                    TextButton(onClick = onDismiss) {
                        Text(R.string.close.string)
                    }
                }
            )
        }
    }
}
