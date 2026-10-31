package com.lastwave.app.data.download

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class ArtworkPreparationTest {
    private fun png(): ByteArray = ByteArrayOutputStream().use { output ->
        val image = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        try { check(image.compress(Bitmap.CompressFormat.PNG, 100, output)); output.toByteArray() }
        finally { image.recycle() }
    }
    private fun fixture(block: suspend (MockWebServer, OkHttpClient, AudioTagWriter) -> Unit) = runBlocking {
        val server = MockWebServer().apply { start() }
        val client = OkHttpClient()
        try { withTimeout(10_000) { block(server, client, AudioTagWriter(client)) } }
        finally { client.connectionPool.evictAll(); client.dispatcher.executorService.shutdownNow(); server.shutdown() }
    }

    @Test fun sameAlbumArtworkIsFetchedAndNormalizedOnceForConcurrentSongs() = fixture { server, _, writer ->
        val image = png()
        server.enqueue(MockResponse().setBody(Buffer().write(image)).setBodyDelay(50, TimeUnit.MILLISECONDS))
        val url = server.url("/same-album.png").toString()
        coroutineScope {
            val images = List(8) { async { writer.prepareArtworkBytes(url) } }.awaitAll()
            images.forEach { assertArrayEquals(image, it) }
        }
        assertArrayEquals(image, writer.prepareArtworkBytes(url))
        assertEquals(1, server.requestCount)
    }

    @Test fun preparedTaggingDoesNotFetchNetworkAndPreservesTheAudioPayload() = fixture { server, _, writer ->
        val image = png()
        server.enqueue(MockResponse().setBody(Buffer().write(image)))
        val cover = writer.prepareArtworkBytes(server.url("/cover.png").toString())
        assertNotNull(cover)
        val audio = File.createTempFile("lastwave-local-tags-", ".mp3")
        val original = ByteArray(4_096) { 0x55 }
        try {
            audio.writeBytes(original)
            assertTrue(writer.embedPreparedMetadata(audio, "Exact Live Song", "Artist", "Concert", cover, "line", "2026"))
            val tagged = audio.readBytes()
            assertArrayEquals(original, tagged.takeLast(original.size).toByteArray())
            assertTrue(String(tagged, Charsets.ISO_8859_1).contains("APIC"))
            assertEquals(1, server.requestCount)
        } finally { audio.delete() }
    }

    @Test fun failedPrimaryCoverTriesSuppliedFallbackOnceNotRepeatedInlineRetries() = fixture { server, _, writer ->
        val image = png()
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setBody(Buffer().write(image)))
        assertArrayEquals(image, writer.prepareArtworkBytes(server.url("/missing").toString(), server.url("/fallback").toString()))
        assertEquals(2, server.requestCount)
    }

    @Test fun failedCoverIsNotCachedAsPermanentAbsence() = fixture { server, _, writer ->
        server.enqueue(MockResponse().setResponseCode(503))
        val url = server.url("/cover").toString()
        assertNull(writer.prepareArtworkBytes(url))
        val image = png()
        server.enqueue(MockResponse().setBody(Buffer().write(image)))
        assertArrayEquals(image, writer.prepareArtworkBytes(url))
        assertEquals(2, server.requestCount)
    }

    @Test fun cancellingOptionalArtworkJoinsTheNativeReader() = fixture { server, client, writer ->
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        coroutineScope {
            val job = launch { writer.prepareArtworkBytes(server.url("/slow-cover").toString()) }
            assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(5, TimeUnit.SECONDS) })
            withTimeout(5_000) { job.cancelAndJoin() }
            assertEquals(0, client.dispatcher.runningCallsCount())
        }
    }
}
