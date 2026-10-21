package com.lastwave.app.ui.player

import com.lastwave.app.data.video.VideoSegmentRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoSegmentTimeTest {
    @Test fun parsesConcertStyleHourTimestamps() {
        assertEquals(
            VideoSegmentRange(5_754_000L, 5_900_000L),
            parseVideoSegmentRange("1:35:54", "1:38:20").getOrThrow(),
        )
    }

    @Test fun acceptsMinuteSecondFormatAndBlankMeansFullVideo() {
        assertEquals(VideoSegmentRange(95_000L, 130_000L), parseVideoSegmentRange("1:35", "2:10").getOrThrow())
        assertNull(parseVideoSegmentRange("", "").getOrThrow())
    }

    @Test fun rejectsIncompleteInvalidOrReversedRanges() {
        assertTrue(parseVideoSegmentRange("1:00", "").isFailure)
        assertTrue(parseVideoSegmentRange("1:90", "2:00").isFailure)
        assertTrue(parseVideoSegmentRange("2:00", "1:59").isFailure)
    }
}
