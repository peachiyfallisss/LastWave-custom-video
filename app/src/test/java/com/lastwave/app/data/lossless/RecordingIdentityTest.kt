package com.lastwave.app.data.lossless

import org.junit.Test

class RecordingIdentityTest {
    @Test fun recordingVersionsMustNotBeSubstituted() = RecordingIdentityRegression.verify()
}
