package com.lastwave.app.playback

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class PlaybackSourceRaceTest {
    @Test fun readyYoutubeDoesNotWaitForSlowPreferredLookup() = runTest {
        val youtube = async { delay(200); "youtube" }
        val preferred = async { delay(4_000); "lossless" }
        assertEquals(false to "youtube", firstReadyPlaybackSource(youtube, preferred, 3_500))
        assertEquals(200L, currentTime)
        assertTrue(preferred.isActive)
        assertEquals("lossless", preferred.await())
    }

    @Test fun fasterPreferredStreamKeepsChosenQuality() = runTest {
        val youtube = async { delay(200); "youtube" }
        val preferred = async { delay(100); "lossless" }
        assertEquals(true to "lossless", firstReadyPlaybackSource(youtube, preferred, 3_500))
        assertEquals(100L, currentTime)
    }

    @Test fun failedPreferredLookupDoesNotBeatUsableYoutube() = runTest {
        val youtube = async { delay(200); "youtube" }
        val preferred = async<String?> { delay(100); null }
        assertEquals(false to "youtube", firstReadyPlaybackSource(youtube, preferred, 3_500))
        assertEquals(200L, currentTime)
    }

    @Test fun failedYoutubeUsesAvailablePreferredStream() = runTest {
        val youtube = async<String?> { delay(100); null }
        val preferred = async { delay(400); "lossless" }
        assertEquals(true to "lossless", firstReadyPlaybackSource(youtube, preferred, 3_500))
        assertEquals(400L, currentTime)
    }

    @Test fun failingYoutubeDoesNotWaitForeverForPreferred() = runTest {
        val youtube = async<String?> { delay(100); null }
        val preferred = async { delay(10_000); "lossless" }
        assertEquals(true to null, firstReadyPlaybackSource(youtube, preferred, 500))
        assertEquals(600L, currentTime)
        assertTrue(preferred.isActive)
        preferred.cancel()
    }
}
