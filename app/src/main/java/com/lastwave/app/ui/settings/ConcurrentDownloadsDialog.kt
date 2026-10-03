package com.lastwave.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun ConcurrentDownloadsDialog(count: Int, onSelect: (Int) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Simultaneous downloads") },
        text = {
            Column {
                for (value in 1..4) {
                    Row(Modifier.fillMaxWidth().clickable { onSelect(value) }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = count == value, onClick = { onSelect(value) })
                        Text(when (value) {
                            1 -> "1 song at a time"
                            2 -> "2 songs at once (default)"
                            else -> "$value songs at once"
                        }, modifier = Modifier.padding(start = 12.dp))
                    }
                }
                Text("Includes each song’s audio and optional video. Current downloads finish when you lower the limit.",
                    style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
