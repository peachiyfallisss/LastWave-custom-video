package com.lastwave.app.data.video

import android.content.Context
import android.net.Uri
import com.lastwave.app.data.music.YouTubeStreamExtractor
import com.lastwave.app.playback.MusicPlayer
import com.lastwave.app.playback.PlayableTrack
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.every
import io.mockk.verify
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class OfflineVideoTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val youtube = mockk<YouTubeStreamExtractor>()
    private val live = PlayableTrack("Song LIVE", "Artist", videoId = "live1234567")
    private fun repository(failAudio: Boolean = false, onRequest: (Boolean) -> Unit = {},
        rangedYoutube: Boolean = false, inspect: (Request) -> Unit = {}): TrackVideoRepository {
        context.getSharedPreferences("lastwave_track_videos", Context.MODE_PRIVATE).edit().clear().commit()
        File(context.filesDir, "offline-videos").deleteRecursively()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val audio = request.url.encodedPath == "/audio"
            onRequest(audio)
            inspect(request)
            val bytes = (if (audio) "original live soundtrack" else "original live video")
                .repeat(if (rangedYoutube) 80_000 else 1).toByteArray()
            val range = request.header("Range")?.let { Regex("bytes=(\\d+)-(\\d+)").matchEntire(it) }
            val start = range?.groupValues?.get(1)?.toInt() ?: 0
            val end = minOf(range?.groupValues?.get(2)?.toInt() ?: bytes.lastIndex, bytes.lastIndex)
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(if (audio && failAudio) 500 else if (range != null) 206 else 200).message("test")
                .apply { if (range != null) header("Content-Range", "bytes $start-$end/${bytes.size}") }
                .body(bytes.copyOfRange(start, end + 1).toResponseBody()).build()
        }.build()
        val host = if (rangedYoutube) "r1---sn-test.googlevideo.com" else "example.test"
        every { youtube.invalidateCache(any()) } returns Unit
        coEvery { youtube.resolveVideoStream("live1234567", any()) } answers {
            MusicPlayer.ResolvedStream("https://$host/video", "video/mp4", null, null, "live-video",
                companionAudio = MusicPlayer.ResolvedStream("https://$host/audio", "audio/mp4", null, null, "live-audio"))
        }
        return TrackVideoRepository(context, youtube, http)
    }

    @Test fun savesExactVideoAndSoundtrackAndPlaysThroughDownloadAlias() = runBlocking {
        val repo = repository()
        repo.downloadVideo(live, listOf("/music/live.opus")) { }
        assertTrue(repo.hasOfflineVideo(live))
        val downloaded = live.copy(videoId = null, playbackUrl = "/music/live.opus")
        val source = repo.sourceFor(downloaded)!!
        assertTrue(source.startsWith("file:"))
        val offline = repo.resolve(source)
        assertEquals("original live video", File(Uri.parse(offline.url).path!!).readText())
        assertEquals("original live soundtrack", File(Uri.parse(offline.companionAudio!!.url).path!!).readText())
        coVerify(exactly = 1) { youtube.resolveVideoStream("live1234567", any()) }
        repo.deleteOfflineVideo("/music/live.opus")
        assertFalse(repo.hasOfflineVideo(live))
        assertFalse(File(Uri.parse(source).path!!).exists())
    }

    @Test fun incompletePairNeverBecomesPlayableAndPartialFilesAreRemoved() = runBlocking {
        val repo = repository(failAudio = true)
        assertTrue(runCatching { repo.downloadVideo(live, listOf("/music/live.opus")) { } }.isFailure)
        assertFalse(repo.hasOfflineVideo(live))
        assertEquals("https://www.youtube.com/watch?v=live1234567", repo.sourceFor(live))
        assertTrue(File(context.filesDir, "offline-videos").listFiles().orEmpty().isEmpty())
    }

    @Test fun youtubeOfflineVideoAndItsExactLiveSoundtrackBothUseBoundedRanges() = runBlocking {
        val requests = java.util.Collections.synchronizedList(mutableListOf<Request>())
        val repo = repository(rangedYoutube = true, inspect = { requests += it })
        val progress = mutableListOf<Int>()
        repo.downloadVideo(live, listOf("/music/live.opus")) { progress += it }
        val offline = repo.resolve(repo.sourceFor(live)!!)
        assertEquals("original live video".repeat(80_000), File(Uri.parse(offline.url).path!!).readText())
        assertEquals("original live soundtrack".repeat(80_000), File(Uri.parse(offline.companionAudio!!.url).path!!).readText())
        assertEquals(4, requests.size)
        assertEquals(setOf("/video", "/audio"), requests.map { it.url.encodedPath }.toSet())
        requests.forEach { request ->
            val range = Regex("bytes=(\\d+)-(\\d+)").matchEntire(request.header("Range").orEmpty())!!
            assertTrue(range.groupValues[2].toLong() - range.groupValues[1].toLong() + 1 <= 1024 * 1024)
        }
        assertEquals(100, progress.last())
        assertEquals(progress.sorted().distinct(), progress)
    }

    @Test fun rangedSoundtrackFailureStillCannotPublishVideoOrDeleteSavedSong() = runBlocking {
        val repo = repository(failAudio = true, rangedYoutube = true)
        val audio = File(context.filesDir, "completed-live.opus").apply { writeText("saved song") }
        try {
            assertTrue(runCatching { repo.downloadVideo(live, listOf(audio.absolutePath)) { } }.isFailure)
            assertFalse(repo.hasOfflineVideo(live))
            assertEquals("saved song", audio.readText())
            assertTrue(File(context.filesDir, "offline-videos").listFiles().orEmpty().isEmpty())
        } finally { audio.delete() }
    }

    @Test fun failedCustomConcertTransferInvalidatesItsOwnSourceNotTheCatalogSong() = runBlocking {
        val repo = repository(failAudio = true)
        repo.save(live, "https://www.youtube.com/watch?v=custom12345")
        coEvery { youtube.resolveVideoStream("custom12345", any()) } returns MusicPlayer.ResolvedStream(
            "https://example.test/video", "video/mp4", null, null, "custom",
            companionAudio = MusicPlayer.ResolvedStream("https://example.test/audio", "audio/mp4", null, null, "custom-audio"))
        assertTrue(runCatching { repo.downloadVideo(live, emptyList()) { } }.isFailure)
        verify(exactly = 1) { youtube.invalidateCache("custom12345") }
        verify(exactly = 0) { youtube.invalidateCache("live1234567") }
    }

    @Test fun changingSelectedVideoNeverReusesDifferentOfflineRendition() = runBlocking {
        val repo = repository()
        repo.downloadVideo(live, emptyList()) { }
        repo.save(live, "https://www.youtube.com/watch?v=other123456")
        assertFalse(repo.hasOfflineVideo(live))
        assertEquals("https://www.youtube.com/watch?v=other123456", repo.sourceFor(live))
        repo.remove(live)
        assertTrue(repo.hasOfflineVideo(live))
    }

    @Test fun adaptiveVideoAndItsOwnSoundtrackTransferInParallel() = runBlocking {
        val entered = CountDownLatch(2)
        val repo = repository(onRequest = {
            entered.countDown()
            check(entered.await(5, TimeUnit.SECONDS)) { "Video and soundtrack were serialized" }
        })
        withTimeout(10_000) { repo.downloadVideo(live, emptyList()) { } }
        assertTrue(repo.hasOfflineVideo(live))
    }

    @Test fun stagedPairIsInvisibleUntilPublishedAndClosingUnpublishedPairRemovesFiles() = runBlocking {
        val repo = repository()
        val pending = repo.prepareDownload(live)
        try {
            pending.transfer { }
            assertFalse(repo.hasOfflineVideo(live))
            assertTrue(File(context.filesDir, "offline-videos").listFiles().orEmpty().isNotEmpty())
        } finally { pending.close() }
        assertTrue(File(context.filesDir, "offline-videos").listFiles().orEmpty().isEmpty())
        val completed = repo.prepareDownload(live)
        try {
            assertTrue(runCatching { completed.publish(emptyList()) }.isFailure)
            completed.transfer { }
            completed.publish(listOf("/music/live.opus"))
        } finally { completed.close() }
        assertTrue(repo.hasOfflineVideo(live))
    }

    @Test fun cancellationWaitsForNativeCallbacksBeforeRemovingStagedFiles() = runBlocking {
        val entered = CountDownLatch(2)
        val release = CountDownLatch(1)
        val repo = repository(onRequest = {
            entered.countDown()
            check(release.await(10, TimeUnit.SECONDS))
        })
        val job = launch(Dispatchers.Default) { repo.downloadVideo(live, emptyList()) { } }
        try {
            assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
            job.cancel()
            delay(50)
            assertFalse("Cleanup must join callbacks, not race their file writes", job.isCompleted)
        } finally {
            release.countDown()
            withTimeout(5_000) { job.cancelAndJoin() }
        }
        assertFalse(repo.hasOfflineVideo(live))
        assertTrue(File(context.filesDir, "offline-videos").listFiles().orEmpty().isEmpty())
    }

    @Test fun failedVideoNeverDeletesAlreadySavedAudio() = runBlocking {
        val repo = repository(failAudio = true)
        val audio = File(context.filesDir, "completed-song.opus").apply { writeText("saved audio") }
        try {
            assertTrue(runCatching { repo.downloadVideo(live, listOf(audio.absolutePath)) { } }.isFailure)
            assertEquals("saved audio", audio.readText())
            assertFalse(repo.hasOfflineVideo(live))
        } finally { audio.delete() }
    }

    @Test fun oneConcertCanHaveDifferentSongSegments() {
        val repo = repository()
        val odo = PlayableTrack("Odo", "Ado", "Concert", videoId = "odo12345678")
        val songTwo = PlayableTrack("Song Two", "Ado", "Concert", videoId = "songtwo1234")
        val source = "https://example.test/ado-concert.mp4"

        repo.save(odo, source, 5_754_000L, 5_900_000L)
        repo.save(songTwo, source, 5_900_000L, 6_120_000L)

        assertEquals(source, repo.sourceFor(odo))
        assertEquals(VideoSegmentRange(5_754_000L, 5_900_000L), repo.segmentFor(odo))
        assertEquals(VideoSegmentRange(5_900_000L, 6_120_000L), repo.segmentFor(songTwo))
        repo.remove(odo)
        assertNull(repo.segmentFor(odo))
        assertEquals(VideoSegmentRange(5_900_000L, 6_120_000L), repo.segmentFor(songTwo))
    }

    @Test fun segmentRangeMustBeCompleteAndOrdered() {
        val repo = repository()
        assertTrue(runCatching { repo.save(live, "https://example.test/concert.mp4", 10_000L, null) }.isFailure)
        assertTrue(runCatching { repo.save(live, "https://example.test/concert.mp4", 20_000L, 10_000L) }.isFailure)
        assertNull(repo.segmentFor(live))
    }
}
