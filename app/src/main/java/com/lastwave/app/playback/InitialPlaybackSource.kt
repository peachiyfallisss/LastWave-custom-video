package com.lastwave.app.playback

/** An explicit concert source must not depend on a successful song/audio search. */
suspend fun <T> resolveInitialPlaybackSource(
    videoSource: String?,
    resolveVideo: suspend (String) -> T,
    resolveAudio: suspend () -> T,
): T = if (!videoSource.isNullOrBlank()) resolveVideo(videoSource) else resolveAudio()
