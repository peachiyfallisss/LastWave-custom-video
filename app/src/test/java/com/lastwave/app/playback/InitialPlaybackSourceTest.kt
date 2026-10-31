package com.lastwave.app.playback

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class InitialPlaybackSourceTest {
    @Test fun deviceConcertDoesNotSearchForAudio() = runBlocking {
        val result = resolveInitialPlaybackSource("content://concert", { "video:$it" }, { error("Must not search for a song") })
        assertEquals("video:content://concert", result)
    }
    @Test fun externalConcertDoesNotRequireYoutubeMatch() = runBlocking {
        val result = resolveInitialPlaybackSource("https://www.bilibili.com/video/BVexample", { "video" }, { error("Must not resolve studio audio") })
        assertEquals("video", result)
    }
    @Test fun failedVideoIsNotSilentlyReplacedByAudio() = runBlocking {
        var audioCalled = false
        val failure = runCatching { resolveInitialPlaybackSource("content://missing", { error("Cannot open recording") }, { audioCalled = true; "audio" }) }
        assertTrue(failure.isFailure)
        assertFalse(audioCalled)
    }
    @Test fun ordinaryAndLocalAudioRetainTheirExistingPath() = runBlocking {
        assertEquals("audio", resolveInitialPlaybackSource(null, { error("No video selected") }, { "audio" }))
        assertNull(resolveInitialPlaybackSource<String?>("", { error("No video selected") }, { null }))
    }
}
