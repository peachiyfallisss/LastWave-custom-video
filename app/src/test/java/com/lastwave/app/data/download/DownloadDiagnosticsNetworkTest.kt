package com.lastwave.app.data.download

import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class DownloadDiagnosticsNetworkTest {
    @Test fun realNetworkObserverCountsPayloadWithoutLeakingRequestDataOrReadingEarly() {
        val diagnostics = DownloadDiagnostics()
        val id = diagnostics.begin("PRIVATE SONG TITLE")
        diagnostics.trace.worker(id)
        val payload = ByteArray(65536) { 8 }
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody(Buffer().write(payload)))
        server.start()
        val client = OkHttpClient.Builder().eventListenerFactory(diagnostics.listenerFactory)
            .addNetworkInterceptor(diagnostics.networkInterceptor).build()
        try {
            val request = Request.Builder().url(server.url("/private-recording?n=SECRET_SIGNATURE"))
                .header("Authorization", "PRIVATE TOKEN").header("Cookie", "PRIVATE COOKIE")
                .tag(DownloadRequestTag::class.java, diagnostics.tag("PRIVATE SONG TITLE", DownloadTrace.Role.VIDEO)).build()
            client.newCall(request).execute().use { response ->
                assertTrue(diagnostics.report().contains("VIDEO bytes=0"))
                assertArrayEquals(payload, response.body!!.bytes())
            }
            diagnostics.finish("PRIVATE SONG TITLE", id, "success")
            val report = diagnostics.report()
            assertTrue(report.contains("VIDEO bytes=65536"))
            assertTrue(report.contains("active=0 failed=0"))
            assertTrue(report.contains("nPresentCalls=1"))
            listOf("PRIVATE", "SECRET", "http://", "private-recording", "127.0.0.1").forEach { assertFalse(report.contains(it)) }
        } finally {
            client.connectionPool.evictAll(); client.dispatcher.executorService.shutdownNow(); server.shutdown()
        }
    }
    @Test fun nativeCancellationIsMeasuredAndJoined() = runBlocking {
        val diagnostics = DownloadDiagnostics()
        val id = diagnostics.begin("key"); diagnostics.trace.worker(id)
        val server = MockWebServer()
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        server.start()
        val client = OkHttpClient.Builder().eventListenerFactory(diagnostics.listenerFactory)
            .addNetworkInterceptor(diagnostics.networkInterceptor).build()
        val job = launch(Dispatchers.IO) {
            val request = Request.Builder().url(server.url("/"))
                .tag(DownloadRequestTag::class.java, diagnostics.tag("key", DownloadTrace.Role.AUDIO)).build()
            awaitDownloadResponse(client.newCall(request)) { response, _ -> response.body!!.bytes() }
        }
        try {
            assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(5, TimeUnit.SECONDS) })
            withTimeout(5_000) { job.cancelAndJoin() }
            diagnostics.finish("key", id, "cancelled")
            assertTrue(diagnostics.report().contains("failed=1 cancelled=1"))
        } finally {
            job.cancelAndJoin(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdownNow(); server.shutdown()
        }
    }
}
