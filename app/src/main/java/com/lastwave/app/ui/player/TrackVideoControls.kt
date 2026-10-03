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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import com.lastwave.app.playback.MusicPlayer
import com.lastwave.app.playback.PlayableTrack

/** Explicit overlay colors keep the mode switch readable over light and dark artwork. */
@Composable
internal fun TrackVideoControls(player: MusicPlayer) {
    val mode by player.videoState.collectAsStateWithLifecycle()
    Box(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp), contentAlignment = Alignment.Center) {
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
    var localError by remember(track) { mutableStateOf<String?>(null) }
    var fileError by remember { mutableStateOf<String?>(null) }
    var pendingFileTrack by remember { mutableStateOf<PlayableTrack?>(null) }
    LaunchedEffect(visible, track) {
        if (visible) {
            link = player.videoSource(track).orEmpty().takeUnless { it.startsWith("content:") }.orEmpty()
            localError = null
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val target = pendingFileTrack
        pendingFileTrack = null
        if (uri != null && target != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                player.setVideoSource(target, uri.toString())
            }.onFailure { fileError = "Could not keep access to this file. Choose it from device storage." }
        }
    }
    if (visible) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Video for ${track.title}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Video mode plays the video's soundtrack. Your song stays linked to your library and listening history.")
                    OutlinedTextField(value = link, onValueChange = { link = it; localError = null },
                        label = { Text("YouTube, Bilibili, or direct video link") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    localError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    TextButton(onClick = {
                        pendingFileTrack = track
                        onDismiss()
                        picker.launch(arrayOf("video/*"))
                    }) { Text("Choose from device") }
                    if (player.videoSource(track) != null) {
                        TextButton(onClick = { player.removeVideoSource(track); link = ""; onDismiss() }) {
                            Text("Remove custom video")
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = link.isNotBlank(), onClick = {
                    runCatching { player.setVideoSource(track, link) }
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
