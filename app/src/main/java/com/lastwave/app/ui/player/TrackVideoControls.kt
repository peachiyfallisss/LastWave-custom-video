@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.lastwave.app.ui.player

import android.content.Intent
import android.graphics.Matrix
import android.view.TextureView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import com.lastwave.app.data.video.VideoSegmentRange
import com.lastwave.app.playback.MusicPlayer
import com.lastwave.app.playback.PlayableTrack
import java.util.Locale
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Explicit overlay colors keep the mode switch readable over light and dark artwork. */
@Composable
internal fun TrackVideoControls(player: MusicPlayer, showModeSwitch: Boolean) {
    val mode by player.videoState.collectAsStateWithLifecycle()
    if (showModeSwitch) Box(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp), contentAlignment = Alignment.Center) {
        Surface(
            color = Color(0xFF181A20),
            contentColor = Color.White,
            shape = CircleShape,
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.18f)),
            shadowElevation = 4.dp,
        ) {
            Row(Modifier.width(224.dp).padding(4.dp).selectableGroup()) {
                listOf(false to "Audio", true to "Video").forEach { (video, label) ->
                    val active = mode.enabled == video
                    Surface(
                        onClick = { if (!active) player.setVideoEnabled(video) },
                        enabled = !mode.loading,
                        color = if (active) Color.White else Color.Transparent,
                        contentColor = if (active) Color(0xFF181A20) else Color.White,
                        shape = CircleShape,
                        modifier = Modifier.weight(1f).height(44.dp).semantics {
                            selected = active
                            role = Role.Tab
                        },
                    ) {
                        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(label, style = MaterialTheme.typography.labelLarge,
                                fontWeight = if (active) FontWeight.Bold else FontWeight.Medium)
                            if (mode.loading && video == mode.requestedVideo) {
                                Spacer(Modifier.width(6.dp))
                                CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 1.5.dp,
                                    color = if (active) Color(0xFF181A20) else Color.White)
                            }
                        }
                    }
                }
            }
        }
    }
    mode.error?.let { message ->
        Surface(color = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
            Text(message, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp))
        }
    }
}

/** The timer is cancelled when a press is released, dragged, or the header leaves composition. */
@Composable
internal fun Modifier.videoControlsHoldGesture(
    enabled: Boolean,
    seconds: Int,
    onHold: () -> Unit,
): Modifier {
    val latestOnHold by rememberUpdatedState(onHold)
    return semantics {
        if (enabled) onLongClick(label = "Show or hide Audio/Video switch") {
            latestOnHold()
            true
        }
    }.pointerInput(enabled, seconds) {
        if (enabled) coroutineScope {
            detectTapGestures(onPress = {
                val timer = launch {
                    delay(seconds.coerceIn(1, 10) * 1_000L)
                    latestOnHold()
                }
                try {
                    tryAwaitRelease()
                } finally {
                    timer.cancel()
                }
            })
        }
    }
}

/** Always composed so the document-picker callback survives dismissing the source dialog. */
@Composable
internal fun TrackVideoSourcePicker(
    track: PlayableTrack,
    player: MusicPlayer,
    visible: Boolean,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var link by remember(track) { mutableStateOf(player.videoSource(track).orEmpty().takeUnless { it.startsWith("content:") }.orEmpty()) }
    var segmentStart by remember(track) { mutableStateOf(player.videoSegment(track)?.startMs?.let(::formatVideoTimestamp).orEmpty()) }
    var segmentEnd by remember(track) { mutableStateOf(player.videoSegment(track)?.endMs?.let(::formatVideoTimestamp).orEmpty()) }
    var localError by remember(track) { mutableStateOf<String?>(null) }
    var fileError by remember { mutableStateOf<String?>(null) }
    var pendingFileTrack by remember { mutableStateOf<PlayableTrack?>(null) }
    var pendingSegment by remember { mutableStateOf<VideoSegmentRange?>(null) }
    LaunchedEffect(visible, track) {
        if (visible) {
            link = player.videoSource(track).orEmpty().takeUnless { it.startsWith("content:") }.orEmpty()
            val range = player.videoSegment(track)
            segmentStart = range?.startMs?.let(::formatVideoTimestamp).orEmpty()
            segmentEnd = range?.endMs?.let(::formatVideoTimestamp).orEmpty()
            localError = null
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val target = pendingFileTrack
        pendingFileTrack = null
        if (uri != null && target != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                player.setVideoSource(target, uri.toString(), pendingSegment?.startMs, pendingSegment?.endMs)
            }.onFailure { fileError = "Could not keep access to this file. Choose it from device storage." }
        }
    }
    val segmentInput = parseVideoSegmentRange(segmentStart, segmentEnd)
    if (visible) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Video for ${track.title}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Video mode plays the video's soundtrack for this segment while keeping the selected song's metadata. Eligible listens can use its linked YouTube ID for enabled YouTube Music history sync; Last.fm scrobbles use the song metadata.")
                    OutlinedTextField(value = link, onValueChange = { link = it; localError = null },
                        label = { Text("YouTube, Bilibili, or direct video link") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Text("To map several songs from one concert, use the same video source for each song and set a different segment range.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = segmentStart, onValueChange = { segmentStart = it; localError = null },
                            label = { Text("Segment start") }, placeholder = { Text("1:35:54") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii), modifier = Modifier.weight(1f))
                        OutlinedTextField(value = segmentEnd, onValueChange = { segmentEnd = it; localError = null },
                            label = { Text("Segment end") }, placeholder = { Text("1:38:20") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii), modifier = Modifier.weight(1f))
                    }
                    Text("Leave both times blank to use the whole video. Timestamps accept MM:SS or HH:MM:SS.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    localError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    TextButton(onClick = {
                        segmentInput.fold(
                            onSuccess = { range ->
                                pendingFileTrack = track
                                pendingSegment = range
                                onDismiss()
                                picker.launch(arrayOf("video/*"))
                            },
                            onFailure = { localError = it.message ?: "Enter a valid segment range." },
                        )
                    }) { Text("Choose from device") }
                    if (player.videoSource(track) != null) {
                        TextButton(onClick = { player.removeVideoSource(track); link = ""; onDismiss() }) {
                            Text("Remove custom video")
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = link.isNotBlank() && segmentInput.isSuccess, onClick = {
                    runCatching {
                        val range = segmentInput.getOrThrow()
                        player.setVideoSource(track, link, range?.startMs, range?.endMs)
                    }
                        .onSuccess { onDismiss(); localError = null }
                        .onFailure { localError = it.message ?: "Invalid video link." }
                }) { Text("Use video") }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        )
    }
    fileError?.let { message ->
        AlertDialog(onDismissRequest = { fileError = null }, title = { Text("Could not open video") },
            text = { Text(message) }, confirmButton = { TextButton(onClick = { fileError = null }) { Text("OK") } })
    }
}

/** Parse an optional HH:MM:SS / MM:SS segment. A successful null means full-video playback. */
internal fun parseVideoSegmentRange(start: String, end: String): Result<VideoSegmentRange?> {
    if (start.isBlank() && end.isBlank()) return Result.success(null)
    if (start.isBlank() || end.isBlank()) return Result.failure(IllegalArgumentException("Enter both a segment start and end time, or leave both blank."))
    val startMs = parseVideoTimestamp(start) ?: return Result.failure(IllegalArgumentException("Enter the start as MM:SS or HH:MM:SS."))
    val endMs = parseVideoTimestamp(end) ?: return Result.failure(IllegalArgumentException("Enter the end as MM:SS or HH:MM:SS."))
    if (endMs <= startMs) return Result.failure(IllegalArgumentException("The segment end must be after its start."))
    return Result.success(VideoSegmentRange(startMs, endMs))
}

private fun parseVideoTimestamp(input: String): Long? {
    val pieces = input.trim().split(':')
    if (pieces.size !in 2..3 || pieces.any { it.isBlank() || it.any { ch -> !ch.isDigit() } }) return null
    val values = pieces.map { it.toLongOrNull() ?: return null }
    if (values.last() !in 0..59 || (values.size == 3 && values[1] !in 0..59)) return null
    return runCatching {
        val totalSeconds = if (values.size == 2) Math.addExact(Math.multiplyExact(values[0], 60L), values[1])
        else Math.addExact(Math.addExact(Math.multiplyExact(values[0], 3_600L), Math.multiplyExact(values[1], 60L)), values[2])
        Math.multiplyExact(totalSeconds, 1_000L)
    }.getOrNull()
}

private fun formatVideoTimestamp(milliseconds: Long): String {
    val totalSeconds = milliseconds.coerceAtLeast(0L) / 1_000L
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0) String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
    else String.format(Locale.ROOT, "%d:%02d", minutes, seconds)
}

/** Attach the actual music player: no second soundtrack, timer, or history event. */
@Composable
internal fun TrackVideoSurface(player: MusicPlayer, isPlaying: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val output = player.videoOutputPlayer
    val texture = remember(context) { TextureView(context) }
    DisposableEffect(output, texture) {
        fun fit() {
            val size = output.videoSize
            if (size.width <= 0 || size.height <= 0 || texture.width <= 0 || texture.height <= 0) return
            val ratio = size.width * size.pixelWidthHeightRatio / size.height
            val viewRatio = texture.width.toFloat() / texture.height
            val scaleX = if (ratio < viewRatio) ratio / viewRatio else 1f
            val scaleY = if (ratio > viewRatio) viewRatio / ratio else 1f
            texture.setTransform(Matrix().apply {
                setScale(scaleX, scaleY, texture.width / 2f, texture.height / 2f)
            })
        }
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) { fit() }
        }
        val layout = android.view.View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fit() }
        texture.addOnLayoutChangeListener(layout)
        output.addListener(listener)
        output.setVideoTextureView(texture)
        fit()
        onDispose {
            texture.keepScreenOn = false
            output.clearVideoTextureView(texture)
            output.removeListener(listener)
            texture.removeOnLayoutChangeListener(layout)
        }
    }
    Surface(color = Color.Black, modifier = modifier) {
        AndroidView(factory = { texture }, modifier = Modifier.fillMaxSize(), update = { it.keepScreenOn = isPlaying })
    }
}
