package com.lastwave.app.data.download

import java.io.File
import java.io.IOException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class RangedMediaDownloaderTest {
    private val payload = ByteArray(384 * 1024 + 127) { ((it * 31 + it / 256) % 251).toByte() }
    private val chunk = 64L * 1024

    private fun rangeResponse(request: RecordedRequest, bytes: ByteArray = payload): MockResponse {
        val parts = Regex("bytes=(\\d+)-(\\d+)").matchEntire(request.getHeader("Range").orEmpty())!!
        val start = parts.groupValues[1].toInt()
        val end = minOf(parts.groupValues[2].toInt(), bytes.lastIndex)
        return MockResponse().setResponseCode(206).setHeader("Content-Type", "video/webm")
            .setHeader("Content-Range", "bytes $start-$end/${bytes.size}")
            .setHeader("ETag", "\"same-recording\"")
            .setBody(Buffer().write(bytes, start, end - start + 1))
    }

    private fun fixture(block: suspend (MockWebServer, OkHttpClient, File) -> Unit) = runBlocking {
        val server = MockWebServer().apply { start() }
        val client = OkHttpClient.Builder().readTimeout(3, TimeUnit.SECONDS).build()
        val file = File.createTempFile("lastwave-ranges-", ".media")
        try { withTimeout(15_000) { block(server, client, file) } }
        finally {
            client.dispatcher.cancelAll()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdownNow()
            server.shutdown()
            file.delete()
        }
    }

    @Test fun boundedParallelRangesPreserveExactBytesHeadersUrlAndMonotonicProgress() = fixture { server, client, file ->
        val concurrent = AtomicInteger()
        val maximum = AtomicInteger()
        val seen = Collections.synchronizedList(mutableListOf<RecordedRequest>())
        val enteredWorkers = CountDownLatch(4)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                seen += request
                val active = concurrent.incrementAndGet()
                maximum.updateAndGet { maxOf(it, active) }
                try {
                    if (request.getHeader("Range") != "bytes=0-${chunk - 1}") {
                        enteredWorkers.countDown()
                        check(enteredWorkers.await(5, TimeUnit.SECONDS))
                    }
                    return rangeResponse(request)
                } finally { concurrent.decrementAndGet() }
            }
        }
        val progress = mutableListOf<Int>()
        val request = Request.Builder().url(server.url("/media?signature=keep%2Fexact&itag=248"))
            .header("Referer", "https://www.youtube.com/watch?v=abcdefghijk").header("User-Agent", "original-agent").build()
        RangedMediaDownloader(client, chunk).copy(request, file, useRanges = true) { progress += it }
        assertArrayEquals(payload, file.readBytes())
        assertEquals(4, maximum.get())
        assertEquals(7, seen.size)
        val ranges = seen.map { Regex("bytes=(\\d+)-(\\d+)").matchEntire(it.getHeader("Range")!!)!!.groupValues.drop(1).map(String::toLong) }
        assertTrue(ranges.all { it[1] - it[0] + 1 <= chunk })
        assertEquals((0 until payload.size step chunk.toInt()).map(Int::toLong), ranges.map { it[0] }.sorted())
        seen.forEach {
            assertEquals(request.url.encodedPath + "?" + request.url.encodedQuery, it.path)
            assertEquals("identity", it.getHeader("Accept-Encoding"))
            assertEquals("original-agent", it.getHeader("User-Agent"))
            assertEquals(request.header("Referer"), it.getHeader("Referer"))
            if (it.getHeader("Range") != "bytes=0-${chunk - 1}") assertEquals("\"same-recording\"", it.getHeader("If-Range"))
        }
        assertEquals(progress.sorted().distinct(), progress)
        assertEquals(100, progress.last())
        assertEquals(1, progress.count { it == 100 })
    }

    @Test fun ignoredInitialRangeConsumesSingleFullBodyWithoutAnotherGet() = fixture { server, client, file ->
        server.enqueue(MockResponse().setBody(Buffer().write(payload)))
        RangedMediaDownloader(client, chunk).copy(Request.Builder().url(server.url("/")).build(), file, true) { }
        assertArrayEquals(payload, file.readBytes())
        assertEquals(1, server.requestCount)
        assertEquals("bytes=0-${chunk - 1}", server.takeRequest().getHeader("Range"))
    }

    @Test fun unknownLengthNonYoutubeMediaKeepsSingleRequest() = fixture { server, client, file ->
        server.enqueue(MockResponse().setChunkedBody(Buffer().write(payload), 8192))
        RangedMediaDownloader(client, chunk).copy(Request.Builder().url(server.url("/concert.mp4")).build(), file) { }
        assertArrayEquals(payload, file.readBytes())
        assertNull(server.takeRequest().getHeader("Range"))
        assertEquals(1, server.requestCount)
    }

    @Test fun tinyMediaUsesOneClippedRange() = fixture { server, client, file ->
        val tiny = ByteArray(99) { it.toByte() }
        server.dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest) = rangeResponse(request, tiny) }
        RangedMediaDownloader(client, chunk).copy(Request.Builder().url(server.url("/")).build(), file, true) { }
        assertArrayEquals(tiny, file.readBytes())
        assertEquals(1, server.requestCount)
    }

    private suspend fun expectFailure(client: OkHttpClient, server: MockWebServer, file: File, ranges: Boolean = true) {
        var successReported = false
        try {
            RangedMediaDownloader(client, chunk).copy(Request.Builder().url(server.url("/")).build(), file, ranges) { if (it == 100) successReported = true }
            fail("Invalid response must not be accepted")
        } catch (_: IOException) { }
        assertFalse(successReported)
        assertFalse("Partial file must be removed after all writers join", file.exists())
    }

    @Test fun mismatchedInitialRangeFailsWithoutRetryOrPublication() = fixture { server, client, file ->
        server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 1-3/4").setBody("bad"))
        expectFailure(client, server, file)
        assertEquals(1, server.requestCount)
    }

    @Test fun malformedAndOverflowingRangeFailsCleanly() = fixture { server, client, file ->
        server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 0-3/9999999999999999999999").setBody("bad"))
        expectFailure(client, server, file)
    }

    @Test fun laterIgnoredRangeNeverMixesWholeFileWithChunks() = fixture { server, client, file ->
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = if (request.getHeader("Range")!!.startsWith("bytes=0-")) rangeResponse(request)
                else MockResponse().setBody(Buffer().write(payload))
        }
        expectFailure(client, server, file)
    }

    @Test fun laterChangedTotalFailsInsteadOfPublishingHoles() = fixture { server, client, file ->
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = rangeResponse(request).apply {
                if (!request.getHeader("Range")!!.startsWith("bytes=0-")) setHeader("Content-Range", "bytes 65536-131071/999999")
            }
        }
        expectFailure(client, server, file)
    }

    @Test fun changedEntityIsRejectedEvenIfRangeAndSizeMatch() = fixture { server, client, file ->
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = rangeResponse(request).apply {
                if (!request.getHeader("Range")!!.startsWith("bytes=0-")) setHeader("ETag", "\"different-recording\"")
            }
        }
        expectFailure(client, server, file)
    }

    @Test fun oversizedRangeBodyIsRejected() = fixture { server, client, file ->
        server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 0-1/2").setBody("extra"))
        expectFailure(client, server, file)
    }

    @Test fun htmlErrorPageIsNeverSavedAsMedia() = fixture { server, client, file ->
        server.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody("<html>error</html>"))
        expectFailure(client, server, file)
        assertEquals(1, server.requestCount)
    }

    @Test fun forbiddenDoesNotRetryOrFallBackToAnUnrangedTransfer() = fixture { server, client, file ->
        server.enqueue(MockResponse().setResponseCode(403))
        expectFailure(client, server, file)
        assertEquals(1, server.requestCount)
    }

    @Test fun transientServerFailureRetriesSameFiniteRange() = fixture { server, client, file ->
        val calls = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = if (calls.getAndIncrement() == 0) MockResponse().setResponseCode(503) else rangeResponse(request)
        }
        RangedMediaDownloader(client, chunk).copy(Request.Builder().url(server.url("/")).build(), file, true) { }
        assertArrayEquals(payload, file.readBytes())
        assertEquals("bytes=0-${chunk - 1}", server.takeRequest().getHeader("Range"))
        assertEquals("bytes=0-${chunk - 1}", server.takeRequest().getHeader("Range"))
    }

    @Test fun truncatedLaterBodyRetriesOnlyItsChunkAndProgressDoesNotDoubleCount() = fixture { server, client, file ->
        val interrupted = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = rangeResponse(request).apply {
                if (request.getHeader("Range") == "bytes=65536-131071" && interrupted.getAndIncrement() == 0) {
                    setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
                }
            }
        }
        val progress = mutableListOf<Int>()
        RangedMediaDownloader(client, chunk).copy(Request.Builder().url(server.url("/")).build(), file, true) { progress += it }
        assertArrayEquals(payload, file.readBytes())
        assertEquals(8, server.requestCount)
        assertEquals(progress.sorted().distinct(), progress)
        assertEquals(100, progress.last())
    }

    @Test fun cancellationJoinsAllNativeCallsAndDeletesPartialFile() = fixture { server, client, file ->
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = if (request.getHeader("Range")!!.startsWith("bytes=0-")) rangeResponse(request)
                else MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
        }
        coroutineScope {
            val job = launch { RangedMediaDownloader(client, chunk).copy(Request.Builder().url(server.url("/")).build(), file, true) { } }
            try {
                repeat(5) { assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(5, TimeUnit.SECONDS) }) }
                withTimeout(5_000) { job.cancelAndJoin() }
                assertEquals(0, client.dispatcher.runningCallsCount())
                assertFalse(file.exists())
                delay(50)
                assertFalse("No callback may recreate the partial file after cleanup", file.exists())
            } finally { job.cancelAndJoin() }
        }
    }

    @Test fun youtubeHostCheckDoesNotOptOtherOriginsIntoRangeDownloads() {
        assertTrue(RangedMediaDownloader.isGoogleVideoMedia("https://r1---sn-abcd.googlevideo.com/videoplayback".toHttpUrl()))
        assertTrue(RangedMediaDownloader.isGoogleVideoMedia("https://googlevideo.com/media".toHttpUrl()))
        assertFalse(RangedMediaDownloader.isGoogleVideoMedia("https://googlevideo.com.example.org/video.mp4".toHttpUrl()))
        assertFalse(RangedMediaDownloader.isGoogleVideoMedia("https://example.org/googlevideo.com".toHttpUrl()))
        assertFalse(RangedMediaDownloader.isGoogleVideoMedia("https://bilibili.com/video/BV123".toHttpUrl()))
    }

    @Test fun everyRangeRetainsItsDiagnosticRoleAndCountsTheRealNetworkBytes() = fixture { server, _, file ->
        val diagnostics = DownloadDiagnostics()
        val id = diagnostics.begin("private song")
        diagnostics.trace.worker(id)
        val client = OkHttpClient.Builder().eventListenerFactory(diagnostics.listenerFactory)
            .addNetworkInterceptor(diagnostics.networkInterceptor).build()
        server.dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest) = rangeResponse(request) }
        try {
            val request = Request.Builder().url(server.url("/"))
                .tag(DownloadRequestTag::class.java, diagnostics.tag("private song", DownloadTrace.Role.VIDEO_AUDIO)).build()
            RangedMediaDownloader(client, chunk).copy(request, file, true) { }
            diagnostics.finish("private song", id, "success")
            val report = diagnostics.report()
            assertTrue(report, report.contains("VIDEO_AUDIO bytes=${payload.size}"))
            assertTrue(report, report.contains("rangedCalls=7 maxRangeBytes=$chunk"))
            assertTrue(report, report.contains("calls=7 active=0 failed=0"))
            assertFalse(report.contains("private song"))
        } finally { client.connectionPool.evictAll(); client.dispatcher.executorService.shutdownNow() }
    }
}
