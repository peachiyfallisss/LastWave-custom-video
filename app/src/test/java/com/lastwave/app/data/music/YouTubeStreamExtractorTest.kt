package com.lastwave.app.data.music

import io.mockk.*
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.VideoStream
import org.schabi.newpipe.extractor.stream.StreamInfo

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class YouTubeStreamExtractorTest {
    private val client = OkHttpClient()
    private lateinit var extractor: YouTubeStreamExtractor
    private fun audio(url: String, kbps: Int) = mockk<AudioStream>(relaxed = true) {
        every { content } returns url
        every { averageBitrate } returns kbps
        every { bitrate } returns kbps
        every { isUrl } returns true
        every { format } returns null
        every { codec } returns "opus"
        every { itagItem } returns null
    }
    private fun info(expiry: Long = System.currentTimeMillis() / 1000 + 3_600): StreamInfo = mockk(relaxed = true) {
        every { audioStreams } returns listOf(audio("https://cdn.test/high?expire=$expiry", 160), audio("https://cdn.test/low?expire=$expiry", 128))
        every { videoStreams } returns emptyList()
        every { videoOnlyStreams } returns listOf(mockk<VideoStream>(relaxed = true) {
            every { content } returns "https://cdn.test/video?expire=$expiry"
            every { isUrl } returns true
            every { isVideoOnly } returns true
            every { height } returns 1080
            every { resolution } returns "1080p"
            every { format } returns null
        })
        every { duration } returns 180L
    }
    @Before fun setup() {
        mockkStatic(StreamInfo::class)
        extractor = YouTubeStreamExtractor(client)
        every { StreamInfo.getInfo(ServiceList.YouTube, any<String>()) } returns info()
    }
    @After fun cleanup() {
        unmockkAll()
        client.dispatcher.cancelAll(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdownNow()
    }

    @Test fun audioAndVideoReuseOneResponseWithoutChangingQualityOrSoundtrack() = runBlocking {
        val audio = extractor.resolveAudioStream("abcdefghijk")
        val video = extractor.resolveVideoStream("abcdefghijk", "offline-video")
        assertTrue(audio.url.contains("/high?"))
        assertEquals(160_000, audio.bitrate)
        assertEquals(1080, video.videoHeight)
        assertEquals(audio.url, video.companionAudio!!.url)
        verify(exactly = 1) { StreamInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/watch?v=abcdefghijk") }
    }

    @Test fun differentRecordingNeverReusesCachedConcertResponse() = runBlocking {
        extractor.resolveAudioStream("abcdefghijk")
        extractor.resolveVideoStream("different12", "different")
        verify(exactly = 2) { StreamInfo.getInfo(ServiceList.YouTube, any<String>()) }
    }

    @Test fun rejectedUrlInvalidatesTheSharedAudioVideoEntry() = runBlocking {
        extractor.resolveAudioStream("abcdefghijk")
        extractor.invalidateCache("abcdefghijk")
        extractor.resolveVideoStream("abcdefghijk", "fresh")
        verify(exactly = 2) { StreamInfo.getInfo(ServiceList.YouTube, any<String>()) }
    }

    @Test fun urlsNearExpiryAreNotReusedForVideo() = runBlocking {
        every { StreamInfo.getInfo(ServiceList.YouTube, any<String>()) } returns info(System.currentTimeMillis() / 1000 + 30)
        extractor.resolveAudioStream("abcdefghijk")
        extractor.resolveVideoStream("abcdefghijk", "fresh")
        verify(exactly = 2) { StreamInfo.getInfo(ServiceList.YouTube, any<String>()) }
    }

    @Test fun cancellingSynchronousNewPipeExtractionCancelsAndJoinsItsHttpCall() = runBlocking {
        val server = MockWebServer().apply { enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)); start() }
        every { StreamInfo.getInfo(ServiceList.YouTube, any<String>()) } answers {
            NewPipe.getDownloader().get(server.url("/player").toString())
            error("Cancelled extraction must not finish")
        }
        val job = launch(Dispatchers.IO) { extractor.resolveAudioStream("abcdefghijk") }
        try {
            assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(5, TimeUnit.SECONDS) })
            withTimeout(5_000) { job.cancelAndJoin() }
            assertEquals(0, client.dispatcher.runningCallsCount())
        } finally { job.cancelAndJoin(); server.shutdown() }
    }
}
