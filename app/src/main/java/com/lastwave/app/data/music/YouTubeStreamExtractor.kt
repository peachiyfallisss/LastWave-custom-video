package com.lastwave.app.data.music

import android.net.Uri
import com.lastwave.app.data.download.awaitDownloadResponse
import kotlinx.coroutines.Job
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.services.youtube.YoutubeJavaScriptPlayerManager
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves the signed/ciphered YouTube media URLs that raw InnerTube player
 * responses no longer reliably expose. Extraction happens locally; no proxy
 * or account is used.
 */
@Singleton
class YouTubeStreamExtractor @Inject constructor(
    http: OkHttpClient,
) {
    private val extractionJob = ThreadLocal<Job?>()
    private val downloader = OkHttpNewPipeDownloader(http, extractionJob)
    // NewPipe's anonymous response contains both audio and video formats.
    // Reuse it for the same exact recording, never for a title/artist match.
    private val infoCache = ExpiringSingleFlightCache<String, StreamInfo>(16, 90_000L)

    @Volatile
    private var initialized = false

    fun invalidateCache(videoId: String) = infoCache.invalidate(videoId)

    private suspend fun streamInfo(videoId: String): StreamInfo = infoCache.get(videoId, expiresAt = { info ->
        (info.audioStreams.map { it.content } + info.videoStreams.map { it.content } + info.videoOnlyStreams.map { it.content })
            .mapNotNull { it.toHttpUrlOrNull()?.queryParameter("expire")?.toLongOrNull()?.times(1_000L) }
            .minOrNull()?.minus(120_000L)
    }) {
        initialize()
        withContext(Dispatchers.IO + extractionJob.asContextElement(currentCoroutineContext()[Job])) {
            StreamInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/watch?v=$videoId")
        }
    }

    suspend fun resolveAudioStream(videoId: String, preferM4a: Boolean = false): YouTubeAudioStream = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val info = try {
            streamInfo(videoId)
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            throw IOException("YouTube stream extraction failed for $videoId", error)
        }
        val stream = if (preferM4a) {
            info.audioStreams
                .filter { it.format?.mimeType?.contains("mp4") == true || it.format?.mimeType?.contains("m4a") == true }
                .maxByOrNull { maxOf(it.averageBitrate, it.bitrate) }
                ?: info.audioStreams.maxByOrNull { maxOf(it.averageBitrate, it.bitrate) }
        } else {
            info.audioStreams.maxByOrNull { maxOf(it.averageBitrate, it.bitrate) }
        } ?: throw IOException("YouTube returned no playable audio stream for $videoId")
        val reportedBitrate = maxOf(stream.averageBitrate, stream.bitrate)
        val result = YouTubeAudioStream(
            videoId = videoId,
            url = stream.content,
            itag = stream.itag.takeIf { it >= 0 },
            mimeType = stream.format?.mimeType,
            codec = stream.codec?.takeIf(String::isNotBlank),
            // NewPipe reports kbps while raw InnerTube formats report bps;
            // normalize both providers to bps for one truthful UI value.
            bitrate = if (reportedBitrate in 1..9_999) reportedBitrate * 1_000 else reportedBitrate,
            sampleRateHz = stream.itagItem?.sampleRate?.takeIf { it > 0 },
            durationMs = stream.itagItem?.approxDurationMs?.takeIf { it > 0 }
                ?: info.duration.takeIf { it > 0 }?.times(1_000L),
            contentLength = stream.itagItem?.contentLength?.takeIf { it > 0 },
            isAdaptive = true,
            clientProfile = NEWPIPE_CLIENT_PROFILE,
            authScope = ANONYMOUS_AUTH_SCOPE,
            requestHeaders = mapOf(
                "User-Agent" to YOUTUBE_WEB_USER_AGENT,
                "Origin" to YOUTUBE_ORIGIN,
                "Referer" to "$YOUTUBE_ORIGIN/watch?v=$videoId",
            ),
            expiresAtEpochMs = stream.content.toHttpUrlOrNull()
                ?.queryParameter("expire")
                ?.toLongOrNull()
                ?.times(1_000L)
                ?: now + UNKNOWN_EXPIRY_TTL_MS,
        )
        result
    }

    /** Resolve the chosen video's own soundtrack; never search for another recording. */
    suspend fun resolveVideoStream(videoId: String, cacheKey: String): com.lastwave.app.playback.MusicPlayer.ResolvedStream =
        withContext(Dispatchers.IO) {
            val info = streamInfo(videoId)
            val videos = (info.videoStreams + info.videoOnlyStreams).filter { it.isUrl && it.content.startsWith("https://") }
            val video = videos.filter { it.height in 1..1080 }.maxByOrNull { it.height }
                ?: videos.maxByOrNull { Regex("[0-9]+").find(it.resolution)?.value?.toIntOrNull() ?: 0 }
                ?: throw IOException("YouTube returned no playable video. Choose another link or a local file.")
            val headers = mapOf("User-Agent" to YOUTUBE_WEB_USER_AGENT, "Referer" to "$YOUTUBE_ORIGIN/watch?v=$videoId")
            val duration = info.duration.takeIf { it > 0 }?.times(1_000L)
            val audio = if (video.isVideoOnly) {
                val selected = info.audioStreams.filter { it.isUrl }.maxByOrNull { maxOf(it.averageBitrate, it.bitrate) }
                    ?: throw IOException("YouTube returned no video soundtrack.")
                com.lastwave.app.playback.MusicPlayer.ResolvedStream(
                    url = selected.content, mimeType = selected.format?.mimeType ?: "audio/mp4",
                    bitrateKbps = maxOf(selected.averageBitrate, selected.bitrate).takeIf { it > 0 },
                    audioCodec = selected.codec, cacheKey = "$cacheKey:audio", requestHeaders = headers, durationMs = duration,
                    expiresAtEpochMs = selected.content.toHttpUrlOrNull()?.queryParameter("expire")?.toLongOrNull()?.times(1_000L),
                )
            } else null
            com.lastwave.app.playback.MusicPlayer.ResolvedStream(
                url = video.content, mimeType = video.format?.mimeType ?: "video/mp4",
                bitrateKbps = audio?.bitrateKbps, audioCodec = audio?.audioCodec,
                cacheKey = cacheKey, requestHeaders = headers, durationMs = duration, companionAudio = audio,
                videoHeight = video.height.takeIf { it > 0 } ?: Regex("[0-9]+").find(video.resolution)?.value?.toIntOrNull(),
                expiresAtEpochMs = video.content.toHttpUrlOrNull()?.queryParameter("expire")?.toLongOrNull()?.times(1_000L),
            )
        }

    suspend fun getSignatureTimestamp(videoId: String): Int? = withContext(Dispatchers.IO) {
        initialize()
        try {
            YoutubeJavaScriptPlayerManager.getSignatureTimestamp(videoId)
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
    }

    fun decipherStreamUrl(videoId: String, signatureCipher: String): String? {
        initialize()
        val parameters = "https://cipher.invalid/?$signatureCipher".toHttpUrlOrNull() ?: return null
        var resolvedUrl = parameters.queryParameter("url") ?: return null
        val encryptedSignature = parameters.queryParameter("s")
        if (!encryptedSignature.isNullOrBlank()) {
            val signature = runCatching {
                YoutubeJavaScriptPlayerManager.deobfuscateSignature(videoId, encryptedSignature)
            }.getOrNull() ?: return null
            if (resolvedUrl.toHttpUrlOrNull() == null) return null
            resolvedUrl = appendQueryParameterPreservingUrl(
                url = resolvedUrl,
                name = parameters.queryParameter("sp") ?: "signature",
                value = signature,
            )
        }
        return runCatching {
            YoutubeJavaScriptPlayerManager.getUrlWithThrottlingParameterDeobfuscated(videoId, resolvedUrl)
        }.getOrDefault(resolvedUrl)
    }

    fun deobfuscateThrottlingParameter(videoId: String, url: String): String {
        initialize()
        return runCatching {
            YoutubeJavaScriptPlayerManager.getUrlWithThrottlingParameterDeobfuscated(videoId, url)
        }.getOrDefault(url)
    }

    /** A rejected signed URL usually means NewPipe's cached player script is
     * stale. Clear that state locally so playback can recover without an app
     * force-stop. */
    fun invalidatePlayerState(videoId: String) {
        invalidateCache(videoId)
        runCatching { YoutubeJavaScriptPlayerManager.clearAllCaches() }
    }

    fun preWarm() {
        initialize()
    }

    private fun appendQueryParameterPreservingUrl(url: String, name: String, value: String): String {
        val fragmentIndex = url.indexOf('#').takeIf { it >= 0 } ?: url.length
        val base = url.substring(0, fragmentIndex)
        val fragment = url.substring(fragmentIndex)
        val separator = when {
            base.endsWith('?') || base.endsWith('&') -> ""
            '?' in base -> "&"
            else -> "?"
        }
        return "$base$separator${Uri.encode(name)}=${Uri.encode(value)}$fragment"
    }

    private fun initialize() {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            NewPipe.init(downloader)
            initialized = true
        }
    }

    companion object {
        private const val UNKNOWN_EXPIRY_TTL_MS = 5 * 60 * 1000L
        private const val NEWPIPE_CLIENT_PROFILE = "NEWPIPE"
        private const val ANONYMOUS_AUTH_SCOPE = "anonymous"
    }
}

internal const val YOUTUBE_ORIGIN = "https://www.youtube.com"

private class OkHttpNewPipeDownloader(
    private val http: OkHttpClient,
    private val extractionJob: ThreadLocal<Job?>,
) : Downloader() {
    override fun execute(request: Request): Response {
        val headers = request.headers()
        val requestBuilder = okhttp3.Request.Builder()
            .url(request.url())
            .method(request.httpMethod(), request.dataToSend()?.toRequestBody())

        headers.forEach { (name, values) ->
            values.forEach { value -> requestBuilder.addHeader(name, value) }
        }
        if (headers.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
            requestBuilder.header("User-Agent", YOUTUBE_WEB_USER_AGENT)
        }
        if (headers.keys.none { it.equals("Accept-Language", ignoreCase = true) }) {
            requestBuilder.header("Accept-Language", "en-US,en;q=0.9")
        }
        val origin = if (request.url().contains("music.youtube.com", ignoreCase = true)) {
            "https://music.youtube.com"
        } else {
            YOUTUBE_ORIGIN
        }
        if (headers.keys.none { it.equals("Origin", ignoreCase = true) }) {
            requestBuilder.header("Origin", origin)
        }
        if (headers.keys.none { it.equals("Referer", ignoreCase = true) }) {
            requestBuilder.header("Referer", "$origin/")
        }

        val call = http.newCall(requestBuilder.build())
        fun convert(response: okhttp3.Response): Response =
            Response(
                response.code,
                response.message,
                response.headers.names().associateWith(response.headers::values),
                response.body?.string().orEmpty(),
                response.request.url.toString(),
            )
        // Bridge NewPipe's synchronous API to a cancellable/joined HTTP read.
        // A timed-out download extraction must not leave sockets running behind
        // the fallback or cache an abandoned result.
        val parent = extractionJob.get()
        return if (parent != null) runBlocking(parent) {
            awaitDownloadResponse(call) { response, _ -> convert(response) }
        } else call.execute().use(::convert)
    }
}

internal const val YOUTUBE_WEB_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"
