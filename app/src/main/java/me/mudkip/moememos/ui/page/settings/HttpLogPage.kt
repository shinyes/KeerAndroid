package me.mudkip.moememos.ui.page.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavHostController
import me.mudkip.moememos.R
import me.mudkip.moememos.data.model.HttpLogEntry
import me.mudkip.moememos.ext.popBackStackIfLifecycleIsResumed
import me.mudkip.moememos.ext.string
import me.mudkip.moememos.viewmodel.HttpLogViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HttpLogPage(
    navController: NavHostController,
    viewModel: HttpLogViewModel = hiltViewModel()
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val logs by viewModel.logs.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = R.string.http_logs.string) },
                navigationIcon = {
                    IconButton(onClick = {
                        navController.popBackStackIfLifecycleIsResumed(lifecycleOwner)
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = R.string.back.string)
                    }
                },
                actions = {
                    if (logs.isNotEmpty()) {
                        IconButton(onClick = { viewModel.clearLogs() }) {
                            Icon(Icons.Outlined.Delete, contentDescription = R.string.http_logs_clear.string)
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        if (logs.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = R.string.http_logs_empty.string,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(logs, key = { it.id }) { entry ->
                    HttpLogItem(entry = entry)
                }
            }
        }
    }
}

@Composable
private fun HttpLogItem(entry: HttpLogEntry) {
    var expanded by remember(entry.id) { mutableStateOf(false) }
    val timestamp = remember(entry.timestamp) { formatTimestamp(entry.timestamp) }
    val statusLine = remember(entry) { buildStatusLine(entry) }
    val colorScheme = MaterialTheme.colorScheme
    val statusColor = when {
        entry.error != null -> colorScheme.error
        entry.responseCode != null && entry.responseCode >= 400 -> colorScheme.error
        else -> colorScheme.onSurfaceVariant
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = "$timestamp  ${entry.method}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
            Text(
                text = entry.url,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = statusLine,
                style = MaterialTheme.typography.bodySmall,
                color = statusColor
            )
            TextButton(onClick = { expanded = !expanded }) {
                Text(
                    if (expanded) {
                        R.string.http_logs_hide_details.string
                    } else {
                        R.string.http_logs_show_details.string
                    }
                )
            }
            if (expanded) {
                SelectionContainer {
                    Text(
                        text = buildDetailText(entry),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

private fun buildStatusLine(entry: HttpLogEntry): String {
    val statusValue = if (entry.error != null) {
        "${R.string.http_logs_error.string}: ${entry.error}"
    } else {
        val code = entry.responseCode?.toString() ?: "-"
        val message = entry.responseMessage.orEmpty()
        "${R.string.http_logs_status.string}: $code $message".trimEnd()
    }
    val durationValue = entry.durationMs?.let {
        " | ${R.string.http_logs_duration.string}: ${it}ms"
    }.orEmpty()
    return statusValue + durationValue
}

private fun buildDetailText(entry: HttpLogEntry): String {
    return buildString {
        appendLine(R.string.http_logs_request.string)
        appendLine("${entry.method} ${entry.url}")
        if (entry.requestHeaders.isNotBlank()) {
            appendLine(entry.requestHeaders)
        }
        if (!entry.requestBody.isNullOrBlank()) {
            appendLine()
            appendLine(entry.requestBody)
        }

        appendLine()
        appendLine(R.string.http_logs_response.string)
        if (entry.error != null) {
            appendLine("${R.string.http_logs_error.string}: ${entry.error}")
        } else {
            val code = entry.responseCode?.toString() ?: "-"
            val message = entry.responseMessage.orEmpty()
            appendLine("${R.string.http_logs_status.string}: $code $message".trimEnd())
            entry.durationMs?.let { appendLine("${R.string.http_logs_duration.string}: ${it}ms") }
        }
        if (!entry.responseHeaders.isNullOrBlank()) {
            appendLine(entry.responseHeaders)
        }
        if (!entry.responseBody.isNullOrBlank()) {
            appendLine()
            appendLine(entry.responseBody)
        }
    }.trimEnd()
}

private fun formatTimestamp(instant: Instant): String {
    return LOG_TIMESTAMP_FORMATTER.format(instant.atZone(ZoneId.systemDefault()))
}

private val LOG_TIMESTAMP_FORMATTER: DateTimeFormatter = DateTimeFormatter
    .ofPattern("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
