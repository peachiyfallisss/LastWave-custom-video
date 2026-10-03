@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.lastwave.app.ui.player

import android.app.Activity
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lastwave.app.playback.MusicPlayer
import kotlinx.coroutines.delay
import java.util.Locale

/** Moves the existing player's video surface; entering/exiting never reloads the recording. */
@Composable
internal fun FullscreenVideoPlayer(player: MusicPlayer, onDismiss: () -> Unit) {
    val state by player.state.collectAsStateWithLifecycle()
    var controlsVisible by remember { mutableStateOf(true) }
    var seekFraction by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(controlsVisible, state.isPlaying, seekFraction) {
        if (controlsVisible && state.isPlaying && seekFraction == null) {
            delay(3_000L)
            controlsVisible = false
        }
    }
    Dialog(onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val view = LocalView.current
        DisposableEffect(view) {
            val activity = generateSequence(view.context) { (it as? ContextWrapper)?.baseContext }
                .filterIsInstance<Activity>().firstOrNull()
            val previousOrientation = activity?.requestedOrientation
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            val window = (view.parent as? DialogWindowProvider)?.window
            val controller = window?.let { WindowCompat.getInsetsController(it, view) }
            val previousBehavior = controller?.systemBarsBehavior
            controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller?.hide(WindowInsetsCompat.Type.systemBars())
            onDispose {
                previousOrientation?.let { activity?.requestedOrientation = it }
                controller?.show(WindowInsetsCompat.Type.systemBars())
                previousBehavior?.let { controller?.systemBarsBehavior = it }
            }
        }
        Box(Modifier.fillMaxSize().background(Color.Black)
            .clickable { controlsVisible = !controlsVisible }) {
            TrackVideoSurface(player, state.isPlaying, Modifier.fillMaxSize())
            if (controlsVisible) {
                IconButton(onClick = onDismiss,
                    modifier = Modifier.align(Alignment.TopEnd).safeDrawingPadding().padding(12.dp)) {
                    Icon(Icons.Filled.FullscreenExit, "Exit fullscreen", tint = Color.White)
                }
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.7f)).safeDrawingPadding().padding(horizontal = 20.dp)) {
                    val duration = state.durationMs.coerceAtLeast(0L)
                    Slider(value = seekFraction ?: if (duration > 0L) {
                        (state.positionMs.toFloat() / duration).coerceIn(0f, 1f)
                    } else 0f,
                        enabled = duration > 0L,
                        onValueChange = { seekFraction = it },
                        onValueChangeFinished = {
                            seekFraction?.let { player.seekTo((it * duration).toLong()) }
                            seekFraction = null
                        })
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { player.togglePlayPause() }) {
                            Icon(if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                if (state.isPlaying) "Pause" else "Play", tint = Color.White)
                        }
                        Text("${videoTime(state.positionMs)} / ${videoTime(duration)}", color = Color.White)
                    }
                }
            }
        }
    }
}

private fun videoTime(milliseconds: Long): String {
    val seconds = milliseconds.coerceAtLeast(0L) / 1_000L
    return String.format(Locale.ROOT, "%d:%02d", seconds / 60L, seconds % 60L)
}
