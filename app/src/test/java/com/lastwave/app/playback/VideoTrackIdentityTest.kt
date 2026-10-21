@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.lastwave.app.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.lastwave.app.data.generate.GeneratedTrack
import com.lastwave.app.data.generate.toStored
import com.lastwave.app.data.generate.toGenerated

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class VideoTrackIdentityTest {
    private val song = PlayableTrack("Song (Live)", "Artist", "Live Album", videoId = "abcdefghijk", durationMs = 180_000L)

    @Test fun localVideoKeepsOriginalSongAndHistoryIdentity() {
        val stream = MusicPlayer.ResolvedStream("content://videos/42", "video/mp4", null, null, "video:test", durationMs = 200_000L)
        val item = song.toMediaItem(stream)
        assertEquals("content://videos/42", item.localConfiguration?.uri.toString())
        assertEquals(song.videoId, item.mediaId)
        assertEquals(song, item.toPlayableTrack())
        assertNull(item.toPlayableTrack().playbackUrl)
    }

    @Test fun bilibiliVideoDoesNotBecomeTheCatalogSongId() {
        val stream = MusicPlayer.ResolvedStream("https://example.com/bilibili.mp4", "video/mp4", null, null, "video:BV123")
        assertEquals(song, song.toMediaItem(stream).toPlayableTrack())
    }

    @Test fun concertSegmentClipsSourceWithoutChangingSongIdentity() {
        val stream = MusicPlayer.ResolvedStream("content://videos/concert", "video/mp4", null, null, "video:concert")
        val item = song.toMediaItem(stream, clipStartMs = 5_754_000L, clipEndMs = 5_900_000L)
        assertEquals(5_754_000L, item.clippingConfiguration.startPositionMs)
        assertEquals(5_900_000L, item.clippingConfiguration.endPositionMs)
        assertEquals(song, item.toPlayableTrack())
    }

    @Test fun switchingBackRetainsOriginalAudioAndDuration() {
        val video = song.toMediaItem(MusicPlayer.ResolvedStream("content://videos/42", "video/mp4", null, null, "video:test"))
        val audio = video.toPlayableTrack().toMediaItem()
        assertEquals("lastwave", audio.localConfiguration?.uri?.scheme)
        assertEquals("youtube", audio.localConfiguration?.uri?.host)
        assertEquals(song, audio.toPlayableTrack())
    }

    @Test fun locallySavedPlaylistKeepsOriginalVideoAndPlaybackPolicy() {
        val imported = GeneratedTrack("The Mermaid's Song", "Phantom Siita",
            url = "https://music.youtube.com/watch?v=liveVideo01")
        val restored = imported.toStored().toGenerated().toPlayableTrack()
        assertEquals("liveVideo01", restored.videoId)
        assertEquals(true, restored.preserveOriginalRecording)
        val item = restored.toMediaItem(MusicPlayer.ResolvedStream("https://example.com/live.webm",
            "audio/webm", null, null, "yt:liveVideo01"))
        assertEquals(restored, item.toPlayableTrack())
    }
}
