@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.lastwave.app.data.music

import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ExpiringSingleFlightCacheTest {
    @Test fun concurrentAudioAndVideoConsumersExtractOnceForTheSameRecording() = runTest {
        val cache = ExpiringSingleFlightCache<String, String>(4, 1_000) { testScheduler.currentTime }
        var loads = 0
        val results = List(12) { async { cache.get("exact-video") { loads++; delay(20); "audio+video" } } }.awaitAll()
        assertEquals(1, loads)
        assertTrue(results.all { it == "audio+video" })
    }

    @Test fun differentRecordingDoesNotWaitBehindSlowExtraction() = runTest {
        val cache = ExpiringSingleFlightCache<String, String>(4, 1_000) { testScheduler.currentTime }
        val slow = async { cache.get("concert") { delay(1_000); "live" } }
        yield()
        assertEquals("studio", cache.get("different-video") { "studio" })
        assertFalse(slow.isCompleted)
        assertEquals("live", slow.await())
    }

    @Test fun expiryAndSignedUrlMarginForceFreshExtraction() = runTest {
        var clock = 0L
        val cache = ExpiringSingleFlightCache<String, String>(4, 1_000) { clock }
        assertEquals("first", cache.get("id", expiresAt = { 100 }) { "first" })
        clock = 99
        assertEquals("first", cache.get("id") { fail("Still fresh"); "bad" })
        clock = 100
        assertEquals("second", cache.get("id") { "second" })
        clock = 1_100
        assertEquals("third", cache.get("id") { "third" })
    }

    @Test fun boundedLruCacheKeepsRecentlyUsedNotEveryPlaylistItem() = runTest {
        val cache = ExpiringSingleFlightCache<String, String>(2, 1_000)
        cache.get("a") { "a" }; cache.get("b") { "b" }
        cache.get("a") { fail("Cached"); "bad" }
        cache.get("c") { "c" }
        assertEquals("new b", cache.get("b") { "new b" })
    }

    @Test fun failedExtractionDoesNotPoisonFutureAttempts() = runTest {
        val cache = ExpiringSingleFlightCache<String, String>(4, 1_000)
        try { cache.get("id") { throw IOException("temporary") }; fail() } catch (_: IOException) { }
        assertEquals("recovered", cache.get("id") { "recovered" })
    }

    @Test fun cancelledOwnerDoesNotCancelOtherWaitersOrCacheAbandonedValue() = runTest {
        val cache = ExpiringSingleFlightCache<String, String>(4, 1_000)
        val entered = CompletableDeferred<Unit>()
        val first = async { cache.get("id") { entered.complete(Unit); awaitCancellation() } }
        entered.await()
        val second = async { cache.get("id") { "second" } }
        yield(); first.cancelAndJoin()
        assertEquals("second", second.await())
        assertEquals("second", cache.get("id") { fail("Cached second result"); "bad" })
    }

    @Test fun cancelledWaiterDoesNotInterruptCurrentOwner() = runTest {
        val cache = ExpiringSingleFlightCache<String, String>(4, 1_000)
        val owner = async { cache.get("id") { delay(50); "ready" } }
        yield()
        val waiter = async { cache.get("id") { fail("Not another extraction"); "bad" } }
        yield(); waiter.cancelAndJoin()
        assertEquals("ready", owner.await())
    }

    @Test fun invalidationDuringLoadCannotRepopulateStaleCache() = runTest {
        val cache = ExpiringSingleFlightCache<String, String>(4, 1_000)
        val release = CompletableDeferred<Unit>()
        val first = async { cache.get("id") { release.await(); "old" } }
        yield(); cache.invalidate("id")
        assertEquals("fresh", cache.get("id") { "fresh" })
        release.complete(Unit); first.await()
        assertEquals("fresh", cache.get("id") { fail("Old load replaced fresh data"); "bad" })
    }

    @Test fun invalidatingOneRecordingDoesNotClearAnother() = runTest {
        val cache = ExpiringSingleFlightCache<String, String>(4, 1_000)
        cache.get("a") { "a" }; cache.get("b") { "b" }
        cache.invalidate("a")
        assertEquals("new a", cache.get("a") { "new a" })
        assertEquals("b", cache.get("b") { fail("Wrong recording invalidated"); "bad" })
    }
}
