package com.lastwave.app.ui.playlist

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lastwave.app.data.music.YouTubeMusicTrack
import com.lastwave.app.data.video.ConcertVideo
import com.lastwave.app.data.video.parseConcertTimestampMs
import java.net.URI

@Composable
fun ConcertLibraryDialog(
    onDismiss: () -> Unit,
    viewModel: ConcertLibraryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showAddDialog by remember { mutableStateOf(false) }
    var selectedConcertId by remember { mutableStateOf<String?>(null) }
    var selectedSong by remember { mutableStateOf<YouTubeMusicTrack?>(null) }
    var confirmDeleteId by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var startText by remember { mutableStateOf("") }
    var endText by remember { mutableStateOf("") }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxWidth().fillMaxHeight(0.94f).padding(horizontal = 10.dp, vertical = 8.dp),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column(Modifier.fillMaxWidth().padding(18.dp)) {
                val concert = state.recordings.firstOrNull { it.id == selectedConcertId }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (concert != null) {
                        IconButton(onClick = {
                            selectedConcertId = null
                            selectedSong = null
                            query = ""
                            viewModel.dismissMessage()
                        }) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back to concert videos") }
                    } else {
                        Icon(Icons.Filled.VideoLibrary, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(concert?.title ?: "Concert videos", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            if (concert == null) "Add a full video, then link song timestamps" else "${concert.segments.size} linked song segments",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (concert == null) {
                        TextButton(onClick = { showAddDialog = true }) { Text("Add video") }
                    }
                    IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "Close") }
                }
                Spacer(Modifier.height(12.dp))
                Divider()
                Spacer(Modifier.height(12.dp))

                if (concert == null) {
                    if (state.recordings.isEmpty()) {
                        Column(Modifier.fillMaxWidth().weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            Text("No concert videos yet", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(8.dp))
                            Text("Add a Bilibili link or choose a video file from this device.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(16.dp))
                            Button(onClick = { showAddDialog = true }) { Text("Add concert video") }
                        }
                    } else {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.weight(1f)) {
                            items(state.recordings, key = ConcertVideo::id) { recording ->
                                ConcertRecordingCard(
                                    recording = recording,
                                    onOpen = { selectedConcertId = recording.id; viewModel.dismissMessage() },
                                    onPlay = { viewModel.playFull(recording) },
                                    onPlayMapped = { viewModel.playMappedSegments(recording) },
                                    onDelete = { confirmDeleteId = recording.id },
                                )
                            }
                        }
                    }
                } else {
                    Text(
                        concert.source,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "Play full video keeps the whole show intact. Play linked songs to play the saved ranges under their YouTube song identities for scrobbles/history.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            label = { Text("Search YouTube songs") },
                        )
                        IconButton(
                            onClick = { viewModel.searchSongs(query); selectedSong = null },
                            enabled = query.isNotBlank() && !state.isSearching,
                            modifier = Modifier.align(Alignment.CenterVertically),
                        ) {
                            if (state.isSearching) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                            else Icon(Icons.Filled.Search, contentDescription = "Search YouTube songs")
                        }
                    }
                    selectedSong?.let { song ->
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                                Text("Selected: ${song.title}", fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(song.artist, style = MaterialTheme.typography.bodySmall)
                                Spacer(Modifier.height(8.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedTextField(value = startText, onValueChange = { startText = it }, modifier = Modifier.weight(1f), singleLine = true, label = { Text("Start (h:mm:ss)") }, placeholder = { Text("1:35:54") })
                                    OutlinedTextField(value = endText, onValueChange = { endText = it }, modifier = Modifier.weight(1f), singleLine = true, label = { Text("End (h:mm:ss)") }, placeholder = { Text("1:38:20") })
                                }
                                Spacer(Modifier.height(8.dp))
                                Button(onClick = {
                                    viewModel.addSegment(concert.id, song, startText, endText)
                                    val start = parseConcertTimestampMs(startText)
                                    val end = parseConcertTimestampMs(endText)
                                    if (start != null && end != null && end > start) {
                                        selectedSong = null
                                        startText = ""
                                        endText = ""
                                    }
                                }) { Text("Link this segment") }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                    if (state.searchResults.isNotEmpty()) {
                        Text("Choose the matching YouTube song", style = MaterialTheme.typography.labelLarge)
                        LazyColumn(Modifier.heightIn(max = 210.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            items(state.searchResults, key = YouTubeMusicTrack::videoId) { song ->
                                TextButton(onClick = { selectedSong = song }) {
                                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
                                        Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(song.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("Song segments", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    if (concert.segments.isEmpty()) {
                        Text("Search for a song and set its start and end time in the concert.", modifier = Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(concert.segments, key = { it.id }) { segment ->
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(segment.title, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text("${segment.artist}  ·  ${formatConcertTime(segment.startMs)}–${formatConcertTime(segment.endMs)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    IconButton(onClick = { viewModel.removeSegment(concert.id, segment.id) }) {
                                        Icon(Icons.Filled.Delete, contentDescription = "Remove ${segment.title}")
                                    }
                                }
                                Divider()
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Button(onClick = { viewModel.playMappedSegments(concert) }, enabled = concert.segments.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Play mapped songs")
                    }
                }

                state.message?.let { message ->
                    Spacer(Modifier.height(8.dp))
                    Text(message, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }

    if (showAddDialog) AddConcertVideoDialog(
        onDismiss = { showAddDialog = false },
        onSave = { title, source -> viewModel.saveRecording(title, source); showAddDialog = false },
    )
    confirmDeleteId?.let { id ->
        val deleting = state.recordings.firstOrNull { it.id == id }
        AlertDialog(
            onDismissRequest = { confirmDeleteId = null },
            title = { Text("Delete concert video?") },
            text = { Text("${deleting?.title ?: "This recording"} and its linked song ranges will be removed.") },
            confirmButton = { TextButton(onClick = { viewModel.deleteRecording(id); confirmDeleteId = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDeleteId = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ConcertRecordingCard(
    recording: ConcertVideo,
    onOpen: () -> Unit,
    onPlay: () -> Unit,
    onPlayMapped: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Text(recording.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("${recording.segments.size} song links  ·  ${sourceLabel(recording.source)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onPlay) { Icon(Icons.Filled.PlayArrow, null); Text("Play full video") }
                TextButton(onClick = onOpen) { Text("Link songs") }
                if (recording.segments.isNotEmpty()) TextButton(onClick = onPlayMapped) { Text("Play setlist") }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "Delete concert video", tint = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

@Composable
private fun AddConcertVideoDialog(onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    val context = LocalContext.current
    var title by remember { mutableStateOf("") }
    var source by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            source = uri.toString()
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add concert video") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(value = title, onValueChange = { title = it; error = null }, label = { Text("Concert name") }, singleLine = true)
                OutlinedTextField(value = source, onValueChange = { source = it; error = null }, label = { Text("Video link (Bilibili, YouTube, direct link)") }, minLines = 2)
                OutlinedButton(onClick = { picker.launch(arrayOf("video/*")) }, modifier = Modifier.fillMaxWidth()) { Text("Choose video from device") }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                Text("The recording is saved as one concert item. You can link each song to its YouTube Music match and timestamps.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val uri = runCatching { URI(source.trim()) }.getOrNull()
                if (title.isBlank()) error = "Enter a concert name."
                else if (source.isBlank() || (uri?.scheme != "content" && uri?.scheme != "https")) error = "Choose a device video or paste an HTTPS link."
                else onSave(title, source)
            }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun sourceLabel(source: String): String = when {
    source.startsWith("content://") -> "device video"
    else -> runCatching { URI(source).host?.removePrefix("www.") }.getOrNull() ?: "video link"
}

private fun formatConcertTime(ms: Long): String {
    val total = ms / 1_000L
    val hours = total / 3_600L
    val minutes = (total % 3_600L) / 60L
    val seconds = total % 60L
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
}
