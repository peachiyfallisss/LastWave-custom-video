package com.lastwave.app.data.download

import org.junit.Test

class DownloadRecordingPolicyTest {
    @Test fun selectedRecordingAndFallbackRules() = DownloadRecordingPolicyRegression.verify()
}
