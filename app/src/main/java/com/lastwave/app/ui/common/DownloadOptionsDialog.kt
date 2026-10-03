package com.lastwave.app.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

@Composable
fun DownloadOptionsDialog(onDismiss: () -> Unit, onDownload: (includeVideo: Boolean) -> Unit) {
    var includeVideo by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Download for offline playback") },
        text = {
            Column {
                listOf(false to "Audio only", true to "Audio + video").forEach { (video, label) ->
                    Row(Modifier.fillMaxWidth().selectable(selected = includeVideo == video,
                        role = Role.RadioButton, onClick = { includeVideo = video }).padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = includeVideo == video, onClick = null)
                        Text(label, modifier = Modifier.padding(start = 12.dp))
                    }
                }
                Text("Video includes its soundtrack and uses more storage. Your selected video is saved when available.",
                    style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = { onDownload(includeVideo) }) { Text("Download") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
