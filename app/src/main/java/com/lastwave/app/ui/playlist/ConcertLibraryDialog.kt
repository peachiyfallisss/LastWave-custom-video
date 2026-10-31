@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.lastwave.app.ui.playlist

import android.content.Intent
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.lastwave.app.data.music.YouTubeMusicTrack
import com.lastwave.app.data.video.*
import com.lastwave.app.ui.player.TrackVideoSurface
import kotlinx.coroutines.delay
import java.net.URI
import java.util.Locale

/** Full-screen library with a single scrolling surface and focused editing sheets. */
@Composable
fun ConcertLibraryDialog(onDismiss: () -> Unit, viewModel: ConcertLibraryViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var importing by remember { mutableStateOf(false) }
    var editingRecording by remember { mutableStateOf<ConcertVideo?>(null) }
    var mapping by remember { mutableStateOf(false) }
    var editingSegment by remember { mutableStateOf<ConcertSongSegment?>(null) }
    var deleting by remember { mutableStateOf<ConcertVideo?>(null) }
    var deletingSegment by remember { mutableStateOf<ConcertSongSegment?>(null) }
    val concert = state.recordings.firstOrNull { it.id == selectedId }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.message) {
        state.message?.let { snackbar.showSnackbar(it); viewModel.dismissMessage() }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        BackHandler(enabled = selectedId != null && !mapping) { selectedId = null }
        Scaffold(
            topBar = {
                TopAppBar(title = { Text(concert?.title ?: "Concerts", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = { IconButton(onClick = { if (concert == null) onDismiss() else selectedId = null }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    } }, actions = {
                        if (concert != null) IconButton(onClick = { editingRecording = concert }) { Icon(Icons.Filled.Edit, "Edit recording") }
                    })
            },
            snackbarHost = { SnackbarHost(snackbar) },
            floatingActionButton = {
                ExtendedFloatingActionButton(
                    onClick = { if (concert == null) importing = true else { editingSegment = null; mapping = true; viewModel.dismissMessage() } },
                    icon = { Icon(Icons.Filled.Add, null) }, text = { Text(if (concert == null) "Add recording" else "Link a song") },
                )
            },
        ) { insets ->
            Box(Modifier.fillMaxSize().padding(insets), contentAlignment = Alignment.TopCenter) {
                LazyColumn(Modifier.widthIn(max = 840.dp).fillMaxSize(),
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 112.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (concert == null) {
                        item {
                            Text("Your recordings, one library.", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                            Text("Import a video. Build its setlist. Watch the whole show.",
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 8.dp, bottom = 8.dp))
                        }
                        if (state.recordings.isEmpty()) item {
                            ElevatedCard(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Icon(Icons.Filled.VideoLibrary, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
                                    Text("Add your first concert", style = MaterialTheme.typography.titleLarge)
                                    Text("Use a video on your device, a public Bilibili or YouTube recording, or a direct video link.")
                                    Button(onClick = { importing = true }) { Text("Choose a recording") }
                                }
                            }
                        }
                        items(state.recordings, key = { it.id }) { recording ->
                            RecordingCard(recording, onOpen = { selectedId = recording.id },
                                onPlay = { selectedId = recording.id; viewModel.playFull(recording) },
                                onDelete = { deleting = recording })
                        }
                    } else {
                        item { ConcertPreview(concert, viewModel, showVideo = !mapping) }
                        item {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("Setlist", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                    Text("${concert.segments.size} linked songs · ${sourceLabel(concert.source)}",
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (concert.segments.isNotEmpty()) TextButton(onClick = { viewModel.playMappedSegments(concert) }) { Text("Songs only") }
                            }
                        }
                        if (concert.segments.isEmpty()) item {
                            OutlinedCard(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("What’s being performed?", style = MaterialTheme.typography.titleMedium)
                                    Text("Link each song to its place in the recording. You can mark the start and end from the playing video.",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    TextButton(onClick = { mapping = true; editingSegment = null }) { Text("Link first song") }
                                }
                            }
                        }
                        items(concert.segments, key = { it.id }) { segment ->
                            OutlinedCard(onClick = { editingSegment = segment; mapping = true }, modifier = Modifier.fillMaxWidth()) {
                                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text("${formatConcertTime(segment.startMs)} — ${formatConcertTime(segment.endMs)}",
                                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                        Text(segment.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        Text(segment.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text("${formatConcertTime(segment.endMs - segment.startMs)} · Tap to edit", style = MaterialTheme.typography.labelSmall)
                                    }
                                    IconButton(onClick = { viewModel.previewSegment(concert, segment) }) { Icon(Icons.Filled.PlayArrow, "Play ${segment.title}") }
                                    IconButton(onClick = { deletingSegment = segment }) { Icon(Icons.Filled.DeleteOutline, "Remove ${segment.title}") }
                                }
                            }
                        }
                        item {
                            concert.wholeVideoSong?.let { song ->
                                OutlinedCard(Modifier.fillMaxWidth()) {
                                    Column(Modifier.padding(16.dp)) {
                                        Text("Whole-recording link", style = MaterialTheme.typography.labelLarge)
                                        Text("${song.artist} — ${song.title}")
                                        Text("Used only when the setlist is empty.", style = MaterialTheme.typography.bodySmall)
                                        TextButton(onClick = { viewModel.linkWholeVideo(concert.id, null) }) { Text("Remove link") }
                                    }
                                }
                            }
                        }
                        item {
                            Text("Full-show playback changes song attribution at each mapped range without reopening the video. Unmapped sections receive no song credit. History/scrobbling uses your normal account settings and listening thresholds; YouTube controls badge eligibility.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        if (importing || editingRecording != null) RecordingEditor(editingRecording,
            onDismiss = { importing = false; editingRecording = null },
            onSave = { name, source -> viewModel.saveRecording(name, source, editingRecording?.id) { importing = false; editingRecording = null } },
            error = state.message, busy = state.isSaving)
        if (mapping && concert != null) SongMappingSheet(concert, editingSegment, viewModel,
            onDismiss = { mapping = false; editingSegment = null })
        deleting?.let { target -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Remove recording?") },
            text = { Text("${target.title} and its song links will leave this library. The original video will not be deleted.") },
            confirmButton = { TextButton(onClick = { viewModel.deleteRecording(target.id); deleting = null }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Keep") } }) }
        deletingSegment?.let { target -> AlertDialog(onDismissRequest = { deletingSegment = null }, title = { Text("Remove song link?") },
            text = { Text("Remove ${target.title} from this setlist? The recording is kept.") },
            confirmButton = { TextButton(onClick = { concert?.let { viewModel.removeSegment(it.id, target.id) }; deletingSegment = null }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { deletingSegment = null }) { Text("Keep") } }) }
    }
}

@Composable
private fun RecordingCard(recording: ConcertVideo, onOpen: () -> Unit, onPlay: () -> Unit, onDelete: () -> Unit) {
    ElevatedCard(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(72.dp).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                val art = recording.segments.firstOrNull()?.artworkUrl ?: recording.wholeVideoSong?.artworkUrl
                if (art == null) Icon(Icons.Filled.VideoLibrary, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                else AsyncImage(art, null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(recording.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(sourceLabel(recording.source), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("${recording.segments.size} song links", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
            Column {
                IconButton(onClick = onPlay) { Icon(Icons.Filled.PlayArrow, "Play ${recording.title}") }
                IconButton(onClick = onDelete) { Icon(Icons.Filled.DeleteOutline, "Remove ${recording.title}") }
            }
        }
    }
}

@Composable
private fun ConcertPreview(concert: ConcertVideo, viewModel: ConcertLibraryViewModel, showVideo: Boolean = true) {
    val player = viewModel.musicPlayer
    val mode by player.videoState.collectAsStateWithLifecycle()
    val playback by player.chromeState.collectAsStateWithLifecycle()
    val progress by player.progressState.collectAsStateWithLifecycle()
    val active = mode.source == concert.source && mode.enabled
    val pending = mode.loading && playback.current?.videoSourceUrl == concert.source
    val needsMappingRefresh = active && playback.current?.concertId == concert.id &&
        (playback.current?.concertSegments != concert.segments ||
            (concert.segments.isEmpty() && playback.current?.videoId != concert.wholeVideoSong?.youtubeVideoId))
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
            if (active && showVideo) TrackVideoSurface(player, playback.isPlaying, Modifier.fillMaxSize())
            else Icon(Icons.Filled.Movie, null, Modifier.size(52.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!active && !pending) FilledIconButton(onClick = { viewModel.playFull(concert) }, modifier = Modifier.size(64.dp)) {
                Icon(Icons.Filled.PlayArrow, "Play full concert", Modifier.size(36.dp))
            }
            if (pending) CircularProgressIndicator()
        }
        if (active) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IconButton(onClick = { player.togglePlayPause() }) { Icon(if (playback.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, "Play/pause") }
                Slider(value = progress.positionMs.toFloat().coerceIn(0f, progress.durationMs.coerceAtLeast(1).toFloat()),
                    onValueChange = { player.seekTo(it.toLong()) }, valueRange = 0f..progress.durationMs.coerceAtLeast(1).toFloat(), modifier = Modifier.weight(1f))
                Text(formatConcertTime(progress.positionMs + (mode.segmentStartMs ?: 0)), style = MaterialTheme.typography.labelMedium)
            }
            val currentSong = playback.current?.concertSegments?.firstOrNull { progress.positionMs in it.startMs until it.endMs }
            if (mode.segmentStartMs != null) Text("Previewing: ${playback.current?.title.orEmpty()}", style = MaterialTheme.typography.bodySmall)
            else currentSong?.let { Text("Now linked: ${it.artist} — ${it.title}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
        }
        if (needsMappingRefresh) Text("Setlist changed. Apply it to refresh the playing song links; your position is kept.", style = MaterialTheme.typography.bodySmall)
        if (!active || mode.segmentStartMs != null || needsMappingRefresh) Button(enabled = !pending, onClick = {
            viewModel.playFull(concert, if (needsMappingRefresh) viewModel.sourcePosition(concert) ?: 0L else 0L)
        }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.PlayArrow, null); Spacer(Modifier.width(8.dp)); Text(when {
                needsMappingRefresh -> "Apply updated setlist"
                active && mode.segmentStartMs != null -> "Return to full show"
                else -> "Play full show"
            })
        }
        if (active || playback.current?.videoSourceUrl == concert.source) mode.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun RecordingEditor(existing: ConcertVideo?, onDismiss: () -> Unit, onSave: (String, String) -> Unit, error: String?, busy: Boolean) {
    val context = LocalContext.current
    var title by rememberSaveable(existing?.id) { mutableStateOf(existing?.title.orEmpty()) }
    var source by rememberSaveable(existing?.id) { mutableStateOf(existing?.source.orEmpty()) }
    var tab by rememberSaveable { mutableIntStateOf(if (source.startsWith("content:")) 0 else 1) }
    var selectedName by remember { mutableStateOf(if (source.startsWith("content:")) "Device video selected" else "") }
    var localError by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) selectedName = it.getString(0).orEmpty()
                }
                source = uri.toString()
                if (title.isBlank()) title = selectedName.substringBeforeLast('.').ifBlank { "Concert recording" }
            }.onFailure { localError = "Could not keep access to this file. Choose it again." }
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(if (existing == null) "Add recording" else "Edit recording", style = MaterialTheme.typography.headlineSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = tab == 0, onClick = { if (tab != 0) { tab = 0; source = "" } }, label = { Text("From device") })
                FilterChip(selected = tab == 1, onClick = { if (tab != 1) { tab = 1; source = "" } }, label = { Text("Video link") })
            }
            if (tab == 0) {
                OutlinedButton(onClick = { picker.launch(arrayOf("video/*")) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.FolderOpen, null); Spacer(Modifier.width(8.dp)); Text(selectedName.ifBlank { "Choose video file" }, maxLines = 1)
                }
            } else OutlinedTextField(source, { source = it }, label = { Text("Bilibili, YouTube, or direct HTTPS link") }, modifier = Modifier.fillMaxWidth(), maxLines = 3)
            OutlinedTextField(title, { title = it }, label = { Text("Recording name") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            (localError ?: error)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(enabled = !busy, onClick = {
                val uri = runCatching { URI(source.trim()) }.getOrNull()
                if (title.isBlank()) localError = "Give this recording a name."
                else if (uri?.scheme != "content" && !(uri?.scheme == "https" && !uri.host.isNullOrBlank())) localError = "Choose a video or enter a valid HTTPS link."
                else onSave(title, source)
            }, modifier = Modifier.fillMaxWidth()) { Text(if (existing == null) "Add to library" else "Save changes") }
            Text("The original recording stays where it is. LastWave saves its source and your song links.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun SongMappingSheet(concert: ConcertVideo, existing: ConcertSongSegment?, viewModel: ConcertLibraryViewModel, onDismiss: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val previewMode by viewModel.musicPlayer.videoState.collectAsStateWithLifecycle()
    val hasPlayhead = previewMode.enabled && previewMode.source == concert.source && !previewMode.loading
    var song by remember { mutableStateOf(existing?.let { YouTubeMusicTrack(it.youtubeVideoId, it.title, it.artist, artworkUrl = it.artworkUrl) }) }
    var query by rememberSaveable { mutableStateOf("") }
    var start by rememberSaveable { mutableStateOf(existing?.startMs?.let(::formatConcertTime) ?: "0:00") }
    var end by rememberSaveable { mutableStateOf(existing?.endMs?.let(::formatConcertTime).orEmpty()) }
    var whole by rememberSaveable { mutableStateOf(false) }
    val startMs = parseConcertTimestampMs(start)
    val endMs = parseConcertTimestampMs(end)
    val overlaps = startMs != null && endMs != null && concert.segments.any { it.id != existing?.id && startMs < it.endMs && it.startMs < endMs }
    val valid = whole || (startMs != null && endMs != null && endMs > startMs && !overlaps)
    LaunchedEffect(query) { delay(350); viewModel.searchSongs(query) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.9f).imePadding().padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (existing == null) "Link a song" else "Edit song link", style = MaterialTheme.typography.headlineSmall)
            Text(if (song == null) "1 · Choose the matching song" else "2 · Set its place in the recording", color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (song == null) {
                OutlinedTextField(query, { query = it }, label = { Text("Search song and artist") }, leadingIcon = { Icon(Icons.Filled.Search, null) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (state.isSearching) LinearProgressIndicator(Modifier.fillMaxWidth())
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (query.isBlank()) item { Text("Search for the song you want credited when this performance plays.", Modifier.padding(vertical = 16.dp)) }
                    items(state.searchResults, key = { it.videoId }) { result ->
                        OutlinedCard(onClick = { song = result; viewModel.dismissMessage() }, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp)) {
                                Text(result.title, fontWeight = FontWeight.SemiBold, maxLines = 2)
                                Text(result.artist, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    state.message?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
                }
            } else {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ListItem(headlineContent = { Text(song!!.title) }, supportingContent = { Text(song!!.artist) },
                        trailingContent = { TextButton(onClick = { song = null }) { Text("Change") } })
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(!whole, { whole = false }, label = { Text("Song segment") })
                        if (existing == null) FilterChip(whole, { whole = true }, label = { Text("Entire video") })
                    }
                    if (whole) Text("Use this song for the entire video when the setlist is empty. For a multi-song concert, link separate segments instead.")
                    else {
                        ConcertPreview(concert, viewModel)
                        OutlinedTextField(start, { start = it }, label = { Text("Start · m:ss or h:mm:ss") }, singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii))
                        TextButton(enabled = hasPlayhead, onClick = { viewModel.sourcePosition(concert)?.let { start = formatConcertTime(it) } }) { Text("Use current playhead as start") }
                        OutlinedTextField(end, { end = it }, label = { Text("End · m:ss or h:mm:ss") }, singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii))
                        TextButton(enabled = hasPlayhead, onClick = { viewModel.sourcePosition(concert)?.let { end = formatConcertTime(it) } }) { Text("Use current playhead as end") }
                        if (overlaps) Text("This range overlaps another song. Adjust its start or end.", color = MaterialTheme.colorScheme.error)
                        else if (valid) Text("Segment length · ${formatConcertTime(endMs!! - startMs!!)}", color = MaterialTheme.colorScheme.primary)
                        else Text("End must be after start. Example: 1:35:54 → 1:38:20.", style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(enabled = valid, onClick = {
                            viewModel.previewSegment(concert, ConcertSongSegment(title = song!!.title, artist = song!!.artist,
                                youtubeVideoId = song!!.videoId, artworkUrl = song!!.artworkUrl, startMs = startMs!!, endMs = endMs!!))
                        }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Filled.PlayArrow, null); Text("Preview this range") }
                    }
                    state.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
                Button(enabled = valid && !state.isSaving, onClick = {
                    if (whole) viewModel.linkWholeVideo(concert.id, song, onDismiss)
                    else viewModel.addSegment(concert.id, song!!, start, end, existing?.id, onDismiss)
                }, modifier = Modifier.fillMaxWidth().navigationBarsPadding()) { Text(if (existing == null) "Save song link" else "Save changes") }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

private fun sourceLabel(source: String): String = if (source.startsWith("content://")) "On this device"
    else runCatching { URI(source).host?.removePrefix("www.") }.getOrNull() ?: "Video link"

internal fun formatConcertTime(ms: Long): String {
    val total = ms.coerceAtLeast(0L) / 1_000L
    return if (total >= 3_600L) "%d:%02d:%02d".format(Locale.ROOT, total / 3_600, total / 60 % 60, total % 60)
    else "%d:%02d".format(Locale.ROOT, total / 60, total % 60)
}
