@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.lastwave.app.ui.download

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lastwave.app.data.download.DownloadProgress
import java.util.Locale

@Composable
fun DownloadQueueSheet(downloads: List<DownloadProgress>, onDismiss: () -> Unit,
    onCancel: (String) -> Unit, onRetry: (String) -> Unit, onReconnect: (String) -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    val active = downloads.filter { !it.isFinished && it.error == null }
    val failed = downloads.filter { it.error != null }
    val complete = downloads.filter { it.isFinished && it.error == null }
    val visible = when (tab) { 0 -> active; 1 -> failed; else -> complete }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.85f).padding(horizontal = 20.dp)) {
            Text("Download queue", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("${active.size} in progress · ${failed.size} need attention", color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp, bottom = 16.dp))
            TabRow(selectedTabIndex = tab) {
                listOf("Active", "Issues", "Finished").forEachIndexed { index, label ->
                    Tab(tab == index, { tab = index }, text = { Text(label) })
                }
            }
            if (tab == 1 && failed.isNotEmpty()) TextButton(onClick = { failed.forEach { onRetry(it.key) } }) { Text("Retry all failed") }
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (visible.isEmpty()) item { Text(when (tab) { 0 -> "No downloads waiting or running."; 1 -> "No download issues."; else -> "Completed downloads from this session appear here." }, Modifier.padding(20.dp)) }
                items(visible, key = { it.key }) { item ->
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row {
                                Column(Modifier.weight(1f)) {
                                    Text(item.title, style = MaterialTheme.typography.titleMedium, maxLines = 2)
                                    Text(item.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                IconButton(onClick = { onCancel(item.key) }) { Icon(Icons.Filled.Close, if (tab == 0) "Cancel download" else "Dismiss entry") }
                            }
                            val status = when {
                                item.error != null -> if (item.isFinished) "Audio saved · video needs attention" else "Download failed"
                                item.isWaitingForConnection -> "Waiting for connection · progress saved"
                                item.isFinished -> "Ready offline"
                                item.formatBadge == "Queued" -> "Queued · waiting for a download slot"
                                item.bytesDownloaded > 0 && item.progressPercent < 100 -> "Downloading · ${item.progressPercent}%"
                                item.progressPercent >= 100 -> "Finishing file"
                                item.formatBadge == "AUDIO" -> "Preparing recording"
                                else -> item.formatBadge
                            }
                            Text(status, style = MaterialTheme.typography.labelLarge, color = if (item.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                            if (tab == 0 && item.bytesDownloaded > 0 && item.progressPercent < 100) {
                                LinearProgressIndicator(progress = { item.progressPercent.coerceIn(0, 100) / 100f }, modifier = Modifier.fillMaxWidth())
                                Text("${queueBytes(item.bytesDownloaded)}" + if (item.totalBytes > 0) " / ${queueBytes(item.totalBytes)}" else " saved", style = MaterialTheme.typography.bodySmall)
                            } else if (tab == 0 && item.formatBadge != "Queued") LinearProgressIndicator(Modifier.fillMaxWidth())
                            item.error?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                            if (tab == 1) FilledTonalButton(onClick = { onRetry(item.key) }) { Text("Retry") }
                            if (item.isWaitingForConnection) TextButton(onClick = { onReconnect(item.key) }) { Text("Try connection now") }
                        }
                    }
                }
            }
            Text("Completed audio is kept if its optional video fails. Actual speed depends on the source and connection.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.navigationBarsPadding().padding(bottom = 16.dp))
        }
    }
}

private fun queueBytes(bytes: Long): String = if (bytes < 1024L * 1024L) "${bytes / 1024} KB"
    else "%.1f MB".format(Locale.ROOT, bytes / (1024.0 * 1024.0))
