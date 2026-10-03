package com.lastwave.app.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource

/** DASH sites often expose separate progressive video and audio URLs. */
@UnstableApi
internal class VideoMediaSourceFactory(
    private val delegate: MediaSource.Factory,
    private val prepared: (String?) -> MusicPlayer.ResolvedStream?,
) : MediaSource.Factory by delegate {
    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        val video = delegate.createMediaSource(mediaItem)
        val audio = prepared(mediaItem.localConfiguration?.customCacheKey)?.companionAudio ?: return video
        val audioItem = mediaItem.buildUpon().setUri(audio.url).setMimeType(audio.mimeType)
            .setCustomCacheKey(audio.cacheKey).build()
        return MergingMediaSource(true, true, video, delegate.createMediaSource(audioItem))
    }
}
