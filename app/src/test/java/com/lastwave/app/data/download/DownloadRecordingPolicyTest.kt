package com.lastwave.app.data.download

import org.junit.Test
import org.junit.Assert.*

class DownloadRecordingPolicyTest {
    @Test fun selectedRecordingAndFallbackRules() = DownloadRecordingPolicyRegression.verify()

    @Test fun publicFilenamesKeepDifferentRecordingsAndSanitizedNamesSeparate() {
        val live = DownloadRecordingPolicy.filenameSuffix("Artist|Song", "liveVideo01")
        val studio = DownloadRecordingPolicy.filenameSuffix("Artist|Song", "studioVid01")
        assertNotEquals(live, studio)
        assertEquals(live, DownloadRecordingPolicy.filenameSuffix("Other metadata", " liveVideo01 "))
        assertNotEquals(DownloadRecordingPolicy.filenameSuffix("Artist|A/B", null),
            DownloadRecordingPolicy.filenameSuffix("Artist|A:B", null))
        assertTrue(live.matches(Regex(" \\[[0-9a-f]{16}\\]")))
    }
}
