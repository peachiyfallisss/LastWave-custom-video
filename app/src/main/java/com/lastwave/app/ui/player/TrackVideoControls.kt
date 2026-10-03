@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.lastwave.app.ui.player

import android.content.Intent
import android.graphics.Matrix
import android.view.TextureView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import com.lastwave.app.playback.MusicPlayer
import com.lastwave.app.playback.PlayableTrack

@Composable
internal fun TrackVideoControls(track: PlayableTrack, player: MusicPlayer) {
    val mode by player.videoState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showSources by remember(track) { mutableStateOf(false) }
    var link by remember(track) { mutableStateOf(player.videoSource(track).orEmpty().takeUnless { it.startsWith("content:") }.orEmpty()) }
    var localError by remember(track) { mutableStateOf<String?>(null) }
    var pendingFileTrack by remember { mutableStateOf<PlayableTrack?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val target = pendingFileTrack
        pendingFileTrack = null
        if (uri != null && target != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                player.setVideoSource(target, uri.toString())
            }.onFailure { localError = "Could not keep access to this file. Choose it from device storage." }
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        TextButton(onClick = { player.setVideoEnabled(false) }, enabled = !mode.loading && mode.enabled) { Text("Audio") }
        TextButton(onClick = { player.setVideoEnabled(true) }, enabled = !mode.loading) {
            Text(if (mode.loading) "Opening…" else if (mode.enabled) "Video ✓" else "Video")
        }
        TextButton(onClick = { showSources = true }) { Text("Choose video") }
    }
    (localError ?: mode.error)?.let { message ->
        Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp))
    }
    if (showSources) {
        AlertDialog(
            onDismissRequest = { showSources = false },
            title = { Text("Video for ${track.title}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Video mode plays the video's soundtrack. Your song stays linked to your library and listening history.")
                    OutlinedTextField(value = link, onValueChange = { link = it; localError = null },
                        label = { Text("YouTube, Bilibili, or direct video link") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    localError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    TextButton(onClick = {
                        pendingFileTrack = track
                        showSources = false
                        picker.launch(arrayOf("video/*"))
                    }) { Text("Choose from device") }
                    if (player.videoSource(track) != null) {
                        TextButton(onClick = { player.removeVideoSource(track); link = ""; showSources = false }) {
                            Text("Remove custom video")
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = link.isNotBlank(), onClick = {
                    runCatching { player.setVideoSource(track, link) }
                        .onSuccess { showSources = false; localError = null }
                        .onFailure { localError = it.message ?: "Invalid video link." }
                }) { Text("Use video") }
            },
            dismissButton = { TextButton(onClick = { showSources = false }) { Text("Cancel") } },
        )
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
