@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.lastwave.app.data.music

import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class DownloadStreamResolutionTest {
    @Test fun freshScopedCacheSkipsBothExtractors() = runTest {
        assertEquals("cached", resolveDownloadSource(cached = { "cached" },
            extract = { fail("No extraction"); "bad" }, fallback = { fail("No fallback"); "bad" }))
    }
    @Test fun successfulDownloadExtractionDoesNotWaitForPlaybackWaterfall() = runTest {
        val result = resolveDownloadSource(cached = { null }, extract = { delay(100); "highest-bitrate exact recording" },
            fallback = { delay(4_000); fail("Playback waterfall must not run first"); "bad" })
        assertEquals("highest-bitrate exact recording", result)
        assertEquals(100L, testScheduler.currentTime)
    }
    @Test fun failedAnonymousExtractionRetainsAuthenticatedPlaybackFallback() = runTest {
        assertEquals("authenticated exact recording", resolveDownloadSource(cached = { null },
            extract = { throw IOException("Anonymous extractor unavailable") }, fallback = { "authenticated exact recording" }))
    }
    @Test fun cancelledExtractionNeverStartsFallback() = runTest {
        var fallback = false
        val job = launch { resolveDownloadSource(cached = { null }, extract = { awaitCancellation() }, fallback = { fallback = true; "bad" }) }
        yield(); job.cancelAndJoin()
        assertFalse(fallback)
    }
    @Test fun aBoundedExtractionMissCanUseFallbackWithoutSwallowingParentCancellation() = runTest {
        val result = resolveDownloadSource(cached = { null }, extract = {
            withTimeoutOrNull(50) { delay(1_000); "late" } ?: throw IOException("timeout")
        }, fallback = { "fallback" })
        assertEquals("fallback", result)
        assertEquals(50L, testScheduler.currentTime)
    }
}
