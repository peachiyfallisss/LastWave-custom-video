package com.lastwave.app.playback

import org.junit.Test

class PlaybackModeStreamCacheTest {
    @Test fun cachedModesKeepTheirSourceIdentityAndExpireSafely() = PlaybackModeStreamCacheRegression.verify()
}
