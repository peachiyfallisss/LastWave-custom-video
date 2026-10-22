package com.lastwave.app.data.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConcertTimestampTest {
    @Test fun acceptsTheFullConcertTimestampExample() {
        assertEquals(5_754_000L, parseConcertTimestampMs("1:35:54"))
        assertEquals(5_900_000L, parseConcertTimestampMs("1:38:20"))
    }

    @Test fun acceptsMinuteSecondTimes() {
        assertEquals(95_000L, parseConcertTimestampMs("1:35"))
    }

    @Test fun rejectsMalformedOrOutOfRangeTimes() {
        assertNull(parseConcertTimestampMs(""))
        assertNull(parseConcertTimestampMs("1:90:00"))
        assertNull(parseConcertTimestampMs("1:2:3:4"))
        assertNull(parseConcertTimestampMs("-1:00"))
    }
}
