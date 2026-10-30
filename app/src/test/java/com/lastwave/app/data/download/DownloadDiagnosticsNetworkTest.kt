package com.lastwave.app.data.download

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test

class DownloadDiagnosticsNetworkTest {
    @Test fun realNetworkObserverCountsPayloadWithoutLeakingRequestDataOrReadingEarly() {
        val diagnostics = DownloadDiagnostics()
        val id = diagnostics.begin("PRIVATE SONG TITLE")
        diagnostics.trace.worker(id)
        val gate = CountDownLatch(1)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val executor = Executors.newCachedThreadPool()
        val payload = ByteArray(65536) { 8 }
        server.executor = executor
        server.createContext("/") { exchange ->
            exchange.sendResponseHeaders(200, payload.size.toLong())
            exchange.responseBody.flush()
            try { check(gate.await(5, TimeUnit.SECONDS)); exchange.responseBody.use { it.write(payload) } }
            finally { exchange.close() }
        }
        server.start()
        val client = OkHttpClient.Builder().eventListenerFactory(diagnostics.listenerFactory)
            .addNetworkInterceptor(diagnostics.networkInterceptor).build()
        try {
            val request = Request.Builder().url("http://127.0.0.1:${server.address.port}/private-recording?n=SECRET_SIGNATURE")
                .header("Authorization", "PRIVATE TOKEN").header("Cookie", "PRIVATE COOKIE")
                .tag(DownloadRequestTag::class.java, diagnostics.tag("PRIVATE SONG TITLE", DownloadTrace.Role.VIDEO)).build()
            client.newCall(request).execute().use { response ->
                assertTrue(diagnostics.report().contains("VIDEO bytes=0"))
                gate.countDown()
                assertArrayEquals(payload, response.body!!.bytes())
            }
            diagnostics.finish("PRIVATE SONG TITLE", id, "success")
            val report = diagnostics.report()
            assertTrue(report.contains("VIDEO bytes=65536"))
            assertTrue(report.contains("active=0 failed=0"))
            assertTrue(report.contains("nPresentCalls=1"))
            listOf("PRIVATE", "SECRET", "http://", "private-recording", "127.0.0.1").forEach { assertFalse(report.contains(it)) }
        } finally {
            gate.countDown(); server.stop(0); executor.shutdownNow()
            client.connectionPool.evictAll(); client.dispatcher.executorService.shutdownNow()
        }
    }
    @Test fun nativeCancellationIsMeasuredAndJoined() = runBlocking {
        val diagnostics = DownloadDiagnostics()
        val id = diagnostics.begin("key"); diagnostics.trace.worker(id)
        val entered = CountDownLatch(1)
        val gate = CountDownLatch(1)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val executor = Executors.newCachedThreadPool()
        server.executor = executor
        server.createContext("/") { exchange ->
            try { exchange.sendResponseHeaders(200, 1024); exchange.responseBody.flush(); entered.countDown(); gate.await(5, TimeUnit.SECONDS) }
            finally { exchange.close() }
        }
        server.start()
        val client = OkHttpClient.Builder().eventListenerFactory(diagnostics.listenerFactory)
            .addNetworkInterceptor(diagnostics.networkInterceptor).build()
        val job = launch(Dispatchers.IO) {
            val request = Request.Builder().url("http://127.0.0.1:${server.address.port}/")
                .tag(DownloadRequestTag::class.java, diagnostics.tag("key", DownloadTrace.Role.AUDIO)).build()
            awaitDownloadResponse(client.newCall(request)) { response, _ -> response.body!!.bytes() }
        }
        try {
            assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
            withTimeout(5_000) { job.cancelAndJoin() }
            diagnostics.finish("key", id, "cancelled")
            assertTrue(diagnostics.report().contains("failed=1 cancelled=1"))
        } finally {
            gate.countDown(); job.cancelAndJoin(); server.stop(0); executor.shutdownNow()
            client.connectionPool.evictAll(); client.dispatcher.executorService.shutdownNow()
        }
    }
}
