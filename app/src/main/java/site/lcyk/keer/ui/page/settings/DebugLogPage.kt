package site.lcyk.keer.ui.page.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavHostController
import site.lcyk.keer.R
import site.lcyk.keer.data.model.DebugLogCategory
import site.lcyk.keer.data.model.DebugLogEntry
import site.lcyk.keer.data.model.DebugLogLevel
import site.lcyk.keer.ext.popBackStackIfLifecycleIsResumed
import site.lcyk.keer.ext.string
import site.lcyk.keer.viewmodel.DebugLogViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugLogPage(
    navController: NavHostController,
    viewModel: DebugLogViewModel = hiltViewModel()
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val settings by viewModel.settings.collectAsState()
    val logs by viewModel.logs.collectAsState()
    val displayLogs = remember(logs) { logs.asReversed() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(R.string.debug_logs.string) },
                navigationIcon = {
                    IconButton(onClick = {
                        navController.popBackStackIfLifecycleIsResumed(lifecycleOwner)
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = R.string.back.string)
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.clearLogs() }) {
                        Icon(Icons.Outlined.Delete, contentDescription = R.string.clear_logs.string)
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.padding(innerPadding),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                DebugToggleItem(
                    title = R.string.enable_app_debug_logs,
                    enabled = settings.appDebugLogEnabled,
                    onToggle = viewModel::setAppDebugLogEnabled
                )
            }
            item {
                DebugToggleItem(
                    title = R.string.enable_http_debug_logs,
                    enabled = settings.httpDebugLogEnabled,
                    onToggle = viewModel::setHttpDebugLogEnabled
                )
            }

            if (displayLogs.isEmpty()) {
                item {
                    Text(
                        text = R.string.debug_logs_empty.string,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 20.dp)
                    )
                }
            } else {
                items(displayLogs, key = { it.id }) { log ->
                    DebugLogItem(log)
                }
            }
        }
    }
}

@Composable
private fun DebugToggleItem(
    title: Int,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        androidx.compose.foundation.layout.Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = title.string,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = 4.dp)
            )
            Switch(
                checked = enabled,
                onCheckedChange = onToggle
            )
        }
    }
}

@Composable
private fun DebugLogItem(log: DebugLogEntry) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "${formatTimestamp(log.timestampMillis)}  ${log.category.displayName()}  ${log.level.displayName()}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.outline
            )
            if (!log.tag.isNullOrBlank()) {
                Text(
                    text = log.tag,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Text(
                text = log.message,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
    }
}

private fun DebugLogCategory.displayName(): String {
    return when (this) {
        DebugLogCategory.APP -> "APP"
        DebugLogCategory.HTTP -> "HTTP"
    }
}

private fun DebugLogLevel.displayName(): String {
    return when (this) {
        DebugLogLevel.VERBOSE -> "VERBOSE"
        DebugLogLevel.DEBUG -> "DEBUG"
        DebugLogLevel.INFO -> "INFO"
        DebugLogLevel.WARN -> "WARN"
        DebugLogLevel.ERROR -> "ERROR"
    }
}

private val debugLogTimeFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")

private fun formatTimestamp(timestampMillis: Long): String {
    return Instant.ofEpochMilli(timestampMillis)
        .atZone(ZoneId.systemDefault())
        .format(debugLogTimeFormatter)
}
