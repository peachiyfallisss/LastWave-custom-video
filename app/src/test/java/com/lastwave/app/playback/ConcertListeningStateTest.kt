package com.lastwave.app.playback

import com.lastwave.app.data.video.ConcertSongSegment
import com.lastwave.app.data.video.parseConcertTimestampMs
import org.junit.Assert.*
import org.junit.Test

class ConcertListeningStateTest {
    private val first = ConcertSongSegment(id = "one", title = "Odo", artist = "Ado", youtubeVideoId = "abcdefghijk", startMs = 5_754_000L, endMs = 5_900_000L)
    private val second = first.copy(id = "two", startMs = 6_000_000L, endMs = 6_146_000L)
    private val concert = PlayableTrack("Concert", "Concert recording", videoSourceUrl = "content://show", concertId = "show", concertSegments = listOf(first, second))
    private fun state(position: Long) = MusicPlayerState(current = concert, positionMs = position, durationMs = 8_000_000, isPlaying = true)

    @Test fun fullConcertKeepsOriginalMediaUntouched() {
        val original = state(first.startMs + 1000)
        val projected = concertListeningState(original)
        assertEquals("Concert", original.current!!.title)
        assertEquals("Odo", projected.current!!.title)
        assertEquals("content://show", projected.current!!.videoSourceUrl)
        assertEquals(1000L, projected.positionMs)
        assertEquals(146_000L, projected.durationMs)
    }
    @Test fun unmappedIntroHasNoSongAttribution() { assertNull(concertListeningState(state(0)).current) }
    @Test fun startBoundaryIsIncluded() { assertEquals("Odo", concertListeningState(state(first.startMs)).current?.title) }
    @Test fun endBoundaryIsExcluded() { assertNull(concertListeningState(state(first.endMs)).current) }
    @Test fun gapsDoNotInheritPreviousSong() { assertNull(concertListeningState(state(5_950_000)).current) }
    @Test fun repeatedSongRangesHaveSeparateSessions() {
        assertNotEquals(concertListeningState(state(first.startMs)).listeningSessionKey, concertListeningState(state(second.startMs)).listeningSessionKey)
    }
    @Test fun ordinaryPlaybackPassesThrough() {
        val ordinary = MusicPlayerState(current = PlayableTrack("Song", "Artist"), positionMs = 12000)
        assertSame(ordinary, concertListeningState(ordinary))
    }
    @Test fun unlinkedWholeConcertDoesNotScrobbleGenericConcertMetadata() {
        assertNull(concertListeningState(MusicPlayerState(current = concert.copy(concertSegments = emptyList()))).current)
    }
    @Test fun projectedTrackUsesSegmentRelativeLyricsKey() {
        val mapped = concertListeningState(state(first.startMs)).current!!
        assertEquals(first.startMs, mapped.videoSegmentStartMs)
        assertEquals(first.endMs, mapped.videoSegmentEndMs)
        assertTrue(mapped.concertSegments.isEmpty())
    }
    @Test fun concertTimestampsRejectOverflowAndInvalidParts() {
        assertEquals(5_754_000L, parseConcertTimestampMs("1:35:54"))
        assertNull(parseConcertTimestampMs("9223372036854775807:00"))
        assertNull(parseConcertTimestampMs("1:60:00"))
        assertNull(parseConcertTimestampMs("-1:00"))
    }
}
