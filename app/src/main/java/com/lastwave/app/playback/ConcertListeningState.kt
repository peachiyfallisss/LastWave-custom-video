package com.lastwave.app.playback

/**
 * Listening identity is separate from the playing media. A full concert keeps
 * one decoder, one URL, and one absolute seekbar; only attribution changes.
 * Unmapped intros/applause never inherit the previous song's identity.
 */
fun concertListeningState(state: MusicPlayerState): MusicPlayerState {
    val recording = state.current ?: return state
    if (recording.concertSegments.isEmpty()) return if (recording.concertId != null && recording.videoId == null)
        state.copy(current = null, listeningSessionKey = null) else state
    val segment = recording.concertSegments.firstOrNull { state.positionMs >= it.startMs && state.positionMs < it.endMs }
        ?: return state.copy(current = null, listeningSessionKey = null)
    return state.copy(
        current = PlayableTrack(
            title = segment.title, artist = segment.artist, videoId = segment.youtubeVideoId,
            artworkUrl = segment.artworkUrl, preserveOriginalRecording = true,
            videoSourceUrl = recording.videoSourceUrl,
            videoSegmentStartMs = segment.startMs, videoSegmentEndMs = segment.endMs,
            durationMs = segment.endMs - segment.startMs,
        ),
        positionMs = state.positionMs - segment.startMs,
        bufferedPositionMs = (state.bufferedPositionMs - segment.startMs).coerceIn(0, segment.endMs - segment.startMs),
        durationMs = segment.endMs - segment.startMs,
        listeningSessionKey = "${recording.concertId}:${segment.id}",
    )
}
