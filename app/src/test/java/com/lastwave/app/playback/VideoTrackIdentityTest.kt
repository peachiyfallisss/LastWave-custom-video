@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.lastwave.app.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

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

    @Test fun switchingBackRetainsOriginalAudioAndDuration() {
        val video = song.toMediaItem(MusicPlayer.ResolvedStream("content://videos/42", "video/mp4", null, null, "video:test"))
        val audio = video.toPlayableTrack().toMediaItem()
        assertEquals("lastwave", audio.localConfiguration?.uri?.scheme)
        assertEquals("youtube", audio.localConfiguration?.uri?.host)
        assertEquals(song, audio.toPlayableTrack())
    }
}
