package com.lastwave.app.data.download

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import com.lastwave.app.data.local.MiscSettings
import com.lastwave.app.data.local.SettingsPreferences
import com.lastwave.app.data.local.db.DownloadedTrackDao
import com.lastwave.app.data.local.db.DownloadedTrackEntity
import com.lastwave.app.data.music.InnerTubeMusicApi
import com.lastwave.app.data.music.YouTubeAudioStream
import com.lastwave.app.data.video.TrackVideoRepository
import io.mockk.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class DownloadPipelineIntegrationTest {
    @get:Rule val temp = TemporaryFolder()
    private val context = mockk<Context>(relaxed = true)
    private val resolver = mockk<ContentResolver>(relaxed = true)
    private val settings = mockk<SettingsPreferences>()
    private val dao = mockk<DownloadedTrackDao>(relaxed = true)
    private val innerTube = mockk<InnerTubeMusicApi>(relaxed = true)
    private val tagger = mockk<AudioTagWriter>()
    private val videos = mockk<TrackVideoRepository>(relaxed = true)
    private val pending = ConcurrentHashMap<String, TrackVideoRepository.PreparedDownload>()
    private val rows = ConcurrentHashMap<String, DownloadedTrackEntity>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val httpCount = AtomicInteger()
    private var onAudio: (String) -> Unit = {}
    private var audioHttpStatus = 200
    private lateinit var manager: TrackDownloadManager
    private val diagnostics = DownloadDiagnostics()

    @Before fun setup() {
        val app = RuntimeEnvironment.getApplication() as Context
        every { context.contentResolver } returns resolver
        every { context.cacheDir } returns temp.newFolder("cache")
        every { context.filesDir } returns temp.newFolder("files")
        every { context.getSystemService(any<String>()) } returns null
        every { context.getSharedPreferences(any(), any()) } answers { app.getSharedPreferences(firstArg(), secondArg()) }
        every { settings.settings } returns MutableStateFlow(MiscSettings(concurrentDownloads = 1, downloadLyrics = false))
        every { resolver.query(any(), any(), any(), any(), any()) } returns null
        every { resolver.insert(any(), any()) } answers { Uri.parse("content://media/audio/${System.nanoTime()}") }
        every { resolver.openOutputStream(any()) } answers { ByteArrayOutputStream() }
        every { resolver.openInputStream(any()) } answers { ByteArrayInputStream(ByteArray(16)) }
        coEvery { dao.findByTrackKey(any()) } answers { rows[firstArg()] }
        coEvery { dao.findByTitleAndArtist(any(), any()) } returns null
        coEvery { dao.insert(any()) } answers { firstArg<DownloadedTrackEntity>().let { rows[it.trackKey] = it }; 1L }
        coEvery { innerTube.peekCachedStream(any()) } returns null
        coEvery { innerTube.resolveDownloadStream(any()) } answers {
            val id = firstArg<String>()
            YouTubeAudioStream(id, "https://example.test/$id", 1, "audio/mpeg", "mp3", 128_000,
                44_100, 1_000, 4_096, true, "test", "test")
        }
        coEvery { tagger.prepareArtworkBytes(any(), any()) } returns null
        every { tagger.embedPreparedMetadata(any(), any(), any(), any(), any(), any(), any()) } returns true
        coEvery { videos.prepareDownload(any(), any()) } answers {
            val track = firstArg<com.lastwave.app.playback.PlayableTrack>()
            pending.getOrPut(track.title) { mockk(relaxed = true) }
        }
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            httpCount.incrementAndGet()
            onAudio(chain.request().url.pathSegments.last())
            val bytes = ByteArray(4_096).apply { "ID3".toByteArray().copyInto(this) }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(audioHttpStatus).message("test")
                .header("Content-Type", "audio/mpeg").body(bytes.toResponseBody()).build()
        }.build()
        manager = TrackDownloadManager(context, innerTube, mockk(relaxed = true), mockk(relaxed = true),
            tagger, http, dao, settings, scope, mockk(relaxed = true), mockk(relaxed = true),
            mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), videos, diagnostics)
    }

    @After fun cleanup() {
        scope.cancel()
        runBlocking { withTimeout(10_000) { scope.coroutineContext[Job]!!.join() } }
        unmockkAll()
    }

    private fun enqueue(title: String, id: String) = manager.downloadTrack(title, "Artist", "Album",
        "https://example.test/cover.jpg", videoId = id, includeVideo = true)

    private suspend fun CountDownLatch.waitForTest() = withContext(Dispatchers.IO) {
        assertTrue("Timed out waiting for pipeline stage", await(5, TimeUnit.SECONDS))
    }

    @Test fun audioAndVideoOverlapAndNextSongTransfersWhileFirstIsTagged() = runBlocking {
        val bothTransfers = CountDownLatch(2)
        val tagEntered = CountDownLatch(1)
        val releaseTag = CountDownLatch(1)
        val secondTransfer = CountDownLatch(1)
        val firstVideo = mockk<TrackVideoRepository.PreparedDownload>(relaxed = true)
        pending["First"] = firstVideo
        coEvery { firstVideo.transfer(any()) } coAnswers {
            bothTransfers.countDown()
            bothTransfers.waitForTest()
        }
        onAudio = { id ->
            if (id == "first123456") {
                bothTransfers.countDown()
                check(bothTransfers.await(5, TimeUnit.SECONDS)) { "Main audio and video were serialized" }
            } else secondTransfer.countDown()
        }
        every { tagger.embedPreparedMetadata(any(), "First", any(), any(), any(), any(), any()) } answers {
            tagEntered.countDown()
            check(releaseTag.await(5, TimeUnit.SECONDS))
            true
        }
        try {
            enqueue("First", "first123456")
            enqueue("Second", "second12345")
            tagEntered.waitForTest()
            secondTransfer.waitForTest() // Must happen BEFORE unblocking the tagger.
        } finally { releaseTag.countDown() }
        val completed = withTimeout(10_000) { manager.downloads.first { it.size == 2 && it.values.all { p -> p.isFinished } } }
        assertTrue(completed.values.all { it.error == null })
        assertEquals(2, rows.size)
        verify(exactly = 1) { firstVideo.publish(any()) }
        verify(timeout = 5_000, exactly = 1) { firstVideo.close() }
    }

    @Test fun diagnosticsSeparateAudioAndVideoResolutionWithoutChangingQuality() = runBlocking {
        coEvery { innerTube.resolveDownloadStream(any()) } coAnswers {
            kotlinx.coroutines.delay(150)
            val id = firstArg<String>()
            YouTubeAudioStream(id, "https://example.test/$id", 1, "audio/mpeg", "mp3", 128_000,
                44_100, 1_000, 4_096, true, "test", "test")
        }
        coEvery { videos.prepareDownload(any(), any()) } coAnswers {
            kotlinx.coroutines.delay(200)
            mockk<TrackVideoRepository.PreparedDownload>(relaxed = true)
        }
        enqueue("First", "first123456")
        withTimeout(10_000) { manager.downloads.first { it.values.any { p -> p.isFinished } } }
        withTimeout(5_000) { while (!manager.downloadTimingReport().contains("result=success")) kotlinx.coroutines.delay(10) }
        val report = manager.downloadTimingReport()
        val audio = Regex("AUDIO_RESOLVE=([0-9]+)").find(report)!!.groupValues[1].toLong()
        val video = Regex("VIDEO_RESOLVE=([0-9]+)").find(report)!!.groupValues[1].toLong()
        assertTrue(audio >= 100)
        assertTrue(video >= 150)
        assertTrue(report.contains("profile=test audioBitrateBps=128000"))
        assertTrue(report.contains("logicalAudioBytes=4096"))
        assertFalse(report.contains("example.test"))
        assertFalse(report.contains("Artist"))
        listOf("REMUX", "FILE_METADATA", "ARTWORK_PREPARE", "ARTWORK_WAIT", "TAG_WRITE", "PUBLIC_COPY", "DB_PUBLISH")
            .forEach { assertTrue(report, report.contains("$it=")) }
    }

    @Test fun actualCoverPreparationOverlapsTransfersAndTaggingUsesPreparedBytesWithoutNetwork() = runBlocking {
        val coverEntered = CountDownLatch(1)
        val releaseCover = CountDownLatch(1)
        val image = byteArrayOf(1, 2, 3)
        coEvery { tagger.prepareArtworkBytes(any(), any()) } coAnswers {
            coverEntered.countDown()
            withContext(Dispatchers.IO) { check(releaseCover.await(5, TimeUnit.SECONDS)) }
            image
        }
        onAudio = { check(coverEntered.await(5, TimeUnit.SECONDS)); releaseCover.countDown() }
        try {
            enqueue("First", "first123456")
            withTimeout(10_000) { manager.downloads.first { it.values.any { p -> p.isFinished } } }
            verify(exactly = 1) { tagger.embedPreparedMetadata(any(), "First", "Artist", "Album", image, null, null) }
            verify(exactly = 0) { tagger.embedMetadata(any(), any(), any(), any(), any(), any(), any(), any()) }
            coVerify(exactly = 0) { innerTube.resolveAudioStream(any()) }
        } finally { releaseCover.countDown() }
    }

    @Test fun stalledOptionalCoverCannotHoldFinalizationOrCauseASecondTaggingFetch() = runBlocking {
        val cancelled = CompletableDeferred<Unit>()
        coEvery { tagger.prepareArtworkBytes(any(), any()) } coAnswers {
            try { awaitCancellation() } finally { cancelled.complete(Unit) }
        }
        enqueue("First", "first123456")
        val finished = withTimeout(5_000) { manager.downloads.first { it.values.any { p -> p.isFinished } } }.values.single()
        assertNull(finished.error)
        withTimeout(5_000) { cancelled.await() }
        verify(exactly = 1) { tagger.embedPreparedMetadata(any(), "First", "Artist", "Album", null, null, null) }
        verify(exactly = 0) { tagger.embedMetadata(any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test fun failedAudioTransferEvictsRecordingUrlsBeforeUserRetry() = runBlocking {
        audioHttpStatus = 403
        enqueue("First", "first123456")
        val failed = withTimeout(5_000) { manager.downloads.first { it.values.any { p -> p.error != null } } }.values.single()
        assertFalse(failed.isFinished)
        verify(exactly = 1) { innerTube.invalidateCache("first123456") }
        assertTrue(rows.isEmpty())
    }

    @Test fun optionalVideoMemoryFailureDoesNotCancelSuccessfulAudio() = runBlocking {
        val staged = mockk<TrackVideoRepository.PreparedDownload>(relaxed = true)
        pending["First"] = staged
        coEvery { staged.transfer(any()) } throws OutOfMemoryError("simulated video memory pressure")
        enqueue("First", "first123456")
        val result = withTimeout(10_000) { manager.downloads.first { it.values.any { p -> p.isFinished } } }.values.single()
        assertTrue(result.error.orEmpty().contains("Not enough memory"))
        assertEquals(1, rows.size)
        verify(exactly = 0) { staged.publish(any()) }
        verify(timeout = 5_000, exactly = 1) { staged.close() }
    }

    @Test fun videoFailureSavesAudioAndRetryOnlyTransfersMissingVideo() = runBlocking {
        val staged = mockk<TrackVideoRepository.PreparedDownload>(relaxed = true)
        pending["First"] = staged
        coEvery { staged.transfer(any()) } throws IOException("video unavailable")
        enqueue("First", "first123456")
        val failed = withTimeout(10_000) { manager.downloads.first { it.values.any { p -> p.isFinished } } }.values.single()
        assertTrue(failed.error.orEmpty().startsWith("Audio saved; video failed:"))
        assertEquals(1, rows.size)
        assertEquals(1, httpCount.get())
        coEvery { staged.transfer(any()) } returns Unit
        manager.retryDownload(failed.key)
        withTimeout(10_000) { manager.downloads.first { it[failed.key]?.let { p -> p.isFinished && p.error == null } == true } }
        assertEquals("Retry must reuse the saved exact-recording audio", 1, httpCount.get())
        verify(exactly = 1) { staged.publish(any()) }
    }
}
