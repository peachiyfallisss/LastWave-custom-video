package com.lastwave.app.data.download

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class DownloadEnrichmentTest {
    @Test fun completedLookupAddsNoWait() = runBlocking {
        assertEquals("ready", awaitOptionalDownloadResult(CompletableDeferred("ready"), 100))
    }
    @Test fun optionalLookupCannotHoldTheQueue() = runBlocking {
        val lookup = async { delay(60_000); "late" }
        assertNull(awaitOptionalDownloadResult(lookup, 25))
        assertTrue(lookup.isCancelled)
    }
    @Test fun noLookupIsValid() = runBlocking { assertNull(awaitOptionalDownloadResult<String>(null, 10)) }
    @Test fun parentCancellationIsNotSwallowed() = runBlocking {
        val job = launch {
            val lookup = async { delay(60_000); "late" }
            awaitOptionalDownloadResult(lookup, 60_000)
            fail("Cancellation must stop finalization")
        }
        yield(); job.cancelAndJoin()
        assertTrue(job.isCancelled)
    }
}
