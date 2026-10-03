package com.lastwave.app.data.video

import android.content.Context
import android.net.Uri
import com.lastwave.app.data.music.YouTubeStreamExtractor
import com.lastwave.app.playback.MusicPlayer
import com.lastwave.app.playback.PlayableTrack
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
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
    private fun repository(failAudio: Boolean = false): TrackVideoRepository {
        context.getSharedPreferences("lastwave_track_videos", Context.MODE_PRIVATE).edit().clear().commit()
        File(context.filesDir, "offline-videos").deleteRecursively()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val audio = request.url.encodedPath == "/audio"
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(if (audio && failAudio) 500 else 200).message("test")
                .body((if (audio) "original live soundtrack" else "original live video").toResponseBody()).build()
        }.build()
        coEvery { youtube.resolveVideoStream("live1234567", any()) } answers {
            MusicPlayer.ResolvedStream("https://example.test/video", "video/mp4", null, null, "live-video",
                companionAudio = MusicPlayer.ResolvedStream("https://example.test/audio", "audio/mp4", null, null, "live-audio"))
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

    @Test fun changingSelectedVideoNeverReusesDifferentOfflineRendition() = runBlocking {
        val repo = repository()
        repo.downloadVideo(live, emptyList()) { }
        repo.save(live, "https://www.youtube.com/watch?v=other123456")
        assertFalse(repo.hasOfflineVideo(live))
        assertEquals("https://www.youtube.com/watch?v=other123456", repo.sourceFor(live))
        repo.remove(live)
        assertTrue(repo.hasOfflineVideo(live))
    }
}
