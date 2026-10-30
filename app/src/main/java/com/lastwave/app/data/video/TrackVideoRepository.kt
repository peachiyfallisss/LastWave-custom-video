package com.lastwave.app.data.video

import android.content.Context
import android.net.Uri
import com.lastwave.app.data.download.DownloadDiagnostics
import com.lastwave.app.data.download.DownloadRequestTag
import com.lastwave.app.data.download.DownloadTrace
import com.lastwave.app.data.download.awaitDownloadResponse
import com.lastwave.app.data.music.YouTubeStreamExtractor
import com.lastwave.app.playback.MusicPlayer
import com.lastwave.app.playback.PlayableTrack
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.io.File
import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/** Time window (on the selected video source) associated with one song. */
data class VideoSegmentRange(val startMs: Long, val endMs: Long) {
    init { require(startMs >= 0L && endMs > startMs) }
    val durationMs: Long get() = endMs - startMs
}

/** Store stable page/document URIs; signed CDN URLs are resolved ahead of playback and refreshed when needed. */
@Singleton
class TrackVideoRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val youtube: YouTubeStreamExtractor,
    http: OkHttpClient,
    private val downloadDiagnostics: DownloadDiagnostics = DownloadDiagnostics(),
) {
    private val contentResolver = context.contentResolver
    private val preferences = context.getSharedPreferences("lastwave_track_videos", Context.MODE_PRIVATE)
    private val client = http.newBuilder().callTimeout(20, TimeUnit.SECONDS).build()

    private val offlineRoot = File(context.filesDir, "offline-videos")
    private val downloadClient = http.newBuilder()
        .eventListenerFactory(downloadDiagnostics.listenerFactory)
        .addNetworkInterceptor(downloadDiagnostics.networkInterceptor)
        .callTimeout(30, TimeUnit.MINUTES)
        .readTimeout(30, TimeUnit.SECONDS).build()

    private fun identityKey(track: PlayableTrack): String = preferences.getString("alias:${key(track)}", null) ?: key(track)
    private fun selectedSource(track: PlayableTrack): String? = customSourceFor(track)
        ?: track.videoId?.let { "https://www.youtube.com/watch?v=$it" }
        ?: preferences.getString("original:${identityKey(track)}", null)
        ?: offlineManifest(identityKey(track))?.optString("source")

    fun sourceFor(track: PlayableTrack): String? {
        val source = selectedSource(track)
        val saved = offlineManifest(identityKey(track))?.takeIf { it.optString("source") == source }
        return saved?.let { Uri.fromFile(File(it.getString("video"))).toString() } ?: source
    }

    fun hasOfflineVideo(track: PlayableTrack): Boolean =
        offlineManifest(identityKey(track))?.optString("source") == selectedSource(track) &&
            offlineManifest(identityKey(track)) != null

    fun customSourceFor(track: PlayableTrack): String? = preferences.getString(identityKey(track), null)

    /** Segment boundaries are optional; no range means the full selected video is used. */
    fun segmentFor(track: PlayableTrack): VideoSegmentRange? {
        val identity = identityKey(track)
        val start = preferences.getLong("segment-start:$identity", -1L)
        val end = preferences.getLong("segment-end:$identity", -1L)
        return if (start >= 0L && end > start) VideoSegmentRange(start, end) else null
    }

    private fun offlineManifest(identity: String): JSONObject? = runCatching {
        val raw = preferences.getString("offline:$identity", null) ?: return@runCatching null
        JSONObject(raw).takeIf { saved ->
            listOf(saved.getString("video"), saved.optString("audio")).filter { it.isNotBlank() }
                .all { File(it).let { file -> file.isFile && file.length() > 0 && file.canonicalPath.startsWith(offlineRoot.canonicalPath + "/") } }
        }
    }.getOrNull()

    /** Resolve ahead of transfer admission. No files exist until transfer() starts. */
    suspend fun prepareDownload(track: PlayableTrack, fallbackVideoId: String? = null): PreparedDownload = withContext(Dispatchers.IO) {
        val identity = identityKey(track)
        val source = selectedSource(track) ?: fallbackVideoId?.let { "https://www.youtube.com/watch?v=$it" }
            ?: throw IOException("No video is selected for this song.")
        val resolved = if (hasOfflineVideo(track)) null else resolve(source)
        if (resolved != null && (resolved.mimeType.contains("mpegurl", true) || resolved.mimeType.contains("dash", true))) {
            throw IOException("Offline video needs a YouTube, Bilibili, MP4/WebM, or device video; manifest links are playback only.")
        }
        PreparedDownload(track, fallbackVideoId, identity, source, resolved)
    }

    /** Owned by the caller until close(). Publication happens only after audio is saved. */
    inner class PreparedDownload internal constructor(
        private val track: PlayableTrack,
        private val fallbackVideoId: String?,
        private val identity: String,
        private val source: String,
        private val resolved: MusicPlayer.ResolvedStream?,
    ) : AutoCloseable {
        internal var diagnosticId: Long? = null
        internal val videoHeight: Int? get() = resolved?.videoHeight
        internal var videoFileBytes: Long = 0L
            private set
        internal var soundtrackFileBytes: Long = 0L
            private set
        private var folder: File? = null
        private var transferred = resolved == null
        private var published = false

        suspend fun transfer(onProgress: (Int) -> Unit) = withContext(Dispatchers.IO) {
            check(!published && folder == null) { "Video transfer already started" }
            val stream = resolved ?: return@withContext
            val destination = File(offlineRoot, UUID.randomUUID().toString())
            // Record ownership before creating files, including on cancellation.
            folder = destination
            if (!destination.mkdirs()) throw IOException("Cannot create offline video folder.")
            // Both are this video's own streams, never a substituted studio soundtrack.
            coroutineScope {
                val progressLock = Any()
                var videoPercent = 0
                var audioPercent = 0
                var reported = -1
                fun progress(video: Int? = null, audio: Int? = null) = synchronized(progressLock) {
                    video?.let { videoPercent = maxOf(videoPercent, it) }
                    audio?.let { audioPercent = maxOf(audioPercent, it) }
                    val percent = if (stream.companionAudio == null) videoPercent else (videoPercent * 9 + audioPercent) / 10
                    if (percent > reported) { reported = percent; onProgress(percent) }
                }
                val soundtrack = stream.companionAudio?.let { part -> async {
                    copyMedia(part, File(destination, "audio"), diagnosticId?.let { DownloadRequestTag(it, DownloadTrace.Role.VIDEO_AUDIO) }) { progress(audio = it) }
                } }
                copyMedia(stream, File(destination, "video"), diagnosticId?.let { DownloadRequestTag(it, DownloadTrace.Role.VIDEO) }) { progress(video = it) }
                soundtrack?.await()
            }
            videoFileBytes = File(destination, "video").length()
            soundtrackFileBytes = File(destination, "audio").takeIf { it.isFile }?.length() ?: 0L
            currentCoroutineContext().ensureActive()
            transferred = true
        }

        fun publish(audioPaths: List<String>) {
            check(transferred && !published) { "Video is not ready to publish" }
            val old = offlineManifest(identity)
            val aliases = preferences.edit()
            folder?.let { destination ->
                val stream = checkNotNull(resolved)
                val video = File(destination, "video")
                val audio = stream.companionAudio?.let { File(destination, "audio") }
                val saved = JSONObject().put("source", source).put("video", video.absolutePath)
                    .put("mime", stream.mimeType).put("duration", stream.durationMs ?: 0L)
                    .put("audio", audio?.absolutePath.orEmpty()).put("audioMime", stream.companionAudio?.mimeType.orEmpty())
                File(destination, "manifest.json").writeText(saved.toString())
                aliases.putString("offline:$identity", saved.toString())
            }
            aliases.apply {
                (track.videoId ?: fallbackVideoId)?.let { putString("original:$identity", "https://www.youtube.com/watch?v=$it") }
                audioPaths.filter { it.isNotBlank() }.forEach { path ->
                    putString("alias:${key(track.copy(videoId = null, playbackUrl = path))}", identity)
                    if (path.startsWith("/")) putString("alias:${key(track.copy(videoId = null, playbackUrl = Uri.fromFile(File(path)).toString()))}", identity)
                }
            }
            if (!aliases.commit()) throw IOException("Cannot link offline video to audio.")
            published = true
            if (folder != null) old?.let { File(it.getString("video")).parentFile?.deleteRecursively() }
        }

        /** Call only after transfer has completed or has been cancelled and joined. */
        override fun close() { if (!published) folder?.deleteRecursively() }
    }

    /** Save the selected rendition, including its separate soundtrack when adaptive. */
    suspend fun downloadVideo(track: PlayableTrack, audioPaths: List<String>, fallbackVideoId: String? = null, onProgress: (Int) -> Unit) = withContext(Dispatchers.IO) {
        val pending = prepareDownload(track, fallbackVideoId)
        try {
            pending.transfer(onProgress)
            currentCoroutineContext().ensureActive()
            pending.publish(audioPaths)
        } finally { pending.close() }
    }

    private suspend fun copyMedia(part: MusicPlayer.ResolvedStream, destination: File, tag: DownloadRequestTag?, progress: (Int) -> Unit) {
        val uri = Uri.parse(part.url)
        if (uri.scheme == "content" || uri.scheme == "file") {
            val input = if (uri.scheme == "content") contentResolver.openInputStream(uri) else File(uri.path!!).inputStream()
            (input ?: throw IOException("Cannot read device video.")).use { source ->
                destination.outputStream().use { out ->
                    val buffer = ByteArray(128 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val size = source.read(buffer)
                        if (size < 0) break
                        out.write(buffer, 0, size)
                    }
                }
            }
            if (destination.length() == 0L) throw IOException("Empty video.")
            progress(100)
            return
        }
        val request = Request.Builder().url(part.url).tag(DownloadRequestTag::class.java, tag).apply { part.requestHeaders.forEach { (key, value) -> header(key, value) } }.build()
        val call = downloadClient.newCall(request)
        awaitDownloadResponse(call) { response, isActive ->
            if (!response.isSuccessful) throw IOException("Video download failed (HTTP ${response.code}).")
            if (response.code == 206) {
                val range = Regex("bytes 0-([0-9]+)/([0-9]+)").matchEntire(response.header("Content-Range").orEmpty())
                if (range == null || range.groupValues[1].toLong() + 1 != range.groupValues[2].toLong())
                    throw IOException("Video server returned only part of the file.")
            }
            val body = response.body ?: throw IOException("Empty video response.")
            if (body.contentType()?.type == "text") throw IOException("Video server returned a page instead of media.")
            val expected = body.contentLength()
            var count = 0L
            body.byteStream().use { input -> destination.outputStream().use { out ->
                val buffer = ByteArray(128 * 1024)
                while (isActive()) {
                    val size = input.read(buffer)
                    if (size < 0) break
                    out.write(buffer, 0, size)
                    count += size
                    if (expected > 0) progress((count * 100 / expected).toInt().coerceIn(0, 100))
                }
            } }
            if (isActive() && (count == 0L || (expected > 0 && count != expected))) throw IOException("Video download incomplete.")
        }
    }

    fun deleteOfflineVideo(audioPath: String) {
        val alias = "alias:${key(PlayableTrack("", "", playbackUrl = audioPath))}"
        val identity = preferences.getString(alias, null) ?: return
        offlineManifest(identity)?.let { File(it.getString("video")).parentFile?.deleteRecursively() }
        preferences.edit().apply {
            remove("offline:$identity")
            preferences.all.filter { it.key.startsWith("alias:") && it.value == identity }.forEach { remove(it.key) }
        }.commit()
    }

    fun clearOfflineVideos() {
        offlineRoot.deleteRecursively()
        preferences.edit().apply {
            preferences.all.keys.filter { it.startsWith("offline:") || it.startsWith("alias:") }.forEach { remove(it) }
        }.commit()
    }

    fun save(track: PlayableTrack, source: String, segmentStartMs: Long? = null, segmentEndMs: Long? = null) {
        val uri = Uri.parse(source.trim())
        require(uri.scheme == "content" || uri.scheme == "https") {
            "Choose a video from your device or an HTTPS video link."
        }
        require((segmentStartMs == null) == (segmentEndMs == null)) { "Enter both a segment start and end time, or leave both blank." }
        if (segmentStartMs != null && segmentEndMs != null) {
            require(segmentStartMs >= 0L && segmentEndMs > segmentStartMs) { "The segment end must be after its start." }
        }
        val identity = identityKey(track)
        preferences.edit().apply {
            putString(identity, source.trim())
            if (segmentStartMs != null && segmentEndMs != null) {
                putLong("segment-start:$identity", segmentStartMs)
                putLong("segment-end:$identity", segmentEndMs)
            } else {
                remove("segment-start:$identity")
                remove("segment-end:$identity")
            }
        }.apply()
    }

    fun remove(track: PlayableTrack) {
        val identity = identityKey(track)
        preferences.edit().remove(identity).remove("segment-start:$identity").remove("segment-end:$identity").apply()
    }

    suspend fun resolve(source: String): MusicPlayer.ResolvedStream = withContext(Dispatchers.IO) {
        val uri = Uri.parse(source)
        val cacheKey = "video:$source:${System.nanoTime()}"
        if (uri.scheme == "file") {
            val file = File(uri.path ?: throw IOException("Invalid offline video."))
            require(file.canonicalPath.startsWith(offlineRoot.canonicalPath + "/")) { "Invalid offline video path." }
            val saved = JSONObject(File(file.parentFile, "manifest.json").readText())
            val audio = saved.optString("audio").takeIf { it.isNotBlank() }?.let {
                val soundtrack = File(it)
                require(soundtrack.canonicalPath.startsWith(offlineRoot.canonicalPath + "/") && soundtrack.isFile) { "Offline soundtrack missing." }
                stream(Uri.fromFile(soundtrack).toString(), saved.getString("audioMime"), "$cacheKey:audio")
            }
            return@withContext stream(source, saved.getString("mime"), cacheKey, duration = saved.optLong("duration").takeIf { it > 0 })
                .copy(companionAudio = audio)
        }
        if (uri.scheme == "content") return@withContext stream(source, contentResolver.getType(uri) ?: "video/mp4", cacheKey)
        val url = source.toHttpUrlOrNull() ?: throw IOException("Invalid video link")
        require(url.scheme == "https") { "Video links must use HTTPS." }
        when (url.host.lowercase()) {
            "youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com", "youtu.be" -> {
                val id = if (url.host == "youtu.be") url.pathSegments.firstOrNull()
                    else url.queryParameter("v") ?: url.pathSegments.lastOrNull()
                require(id?.matches(Regex("[A-Za-z0-9_-]{11}")) == true) { "Paste a YouTube video link." }
                youtube.resolveVideoStream(id!!, cacheKey)
            }
            "b23.tv" -> {
                val response = client.newCall(Request.Builder().url(url).get().build()).execute()
                val target = response.use { it.request.url.toString() }
                val targetHost = target.toHttpUrl().host
                require(targetHost == "bilibili.com" || targetHost.endsWith(".bilibili.com")) { "Invalid Bilibili short link." }
                resolveBilibili(target, cacheKey)
            }
            "bilibili.com", "www.bilibili.com", "m.bilibili.com" -> resolveBilibili(source, cacheKey)
            else -> {
                val path = url.encodedPath.lowercase()
                val mime = when {
                    path.endsWith(".m3u8") -> "application/x-mpegURL"
                    path.endsWith(".mpd") -> "application/dash+xml"
                    path.endsWith(".webm") -> "video/webm"
                    path.endsWith(".mp4") || path.endsWith(".m4v") -> "video/mp4"
                    else -> throw IOException("Use a YouTube, Bilibili, or direct MP4/WebM/HLS/DASH video link.")
                }
                stream(source, mime, cacheKey)
            }
        }
    }

    private fun resolveBilibili(source: String, cacheKey: String): MusicPlayer.ResolvedStream {
        val url = source.toHttpUrl()
        val bvid = url.pathSegments.firstOrNull { it.matches(Regex("BV[A-Za-z0-9]+")) }
        val aid = url.pathSegments.firstOrNull { it.matches(Regex("av[0-9]+")) }?.removePrefix("av")
        require(bvid != null || aid != null) { "Paste a Bilibili /video/BV… or /video/av… link." }
        val headers = mapOf("Referer" to "https://www.bilibili.com/", "User-Agent" to "Mozilla/5.0")
        val viewUrl = "https://api.bilibili.com/x/web-interface/view".toHttpUrl().newBuilder()
            .addQueryParameter(if (bvid != null) "bvid" else "aid", bvid ?: aid).build()
        val view = fetchJson(viewUrl.toString(), headers)
        val pages = view.getJSONArray("pages")
        val requestedPage = (url.queryParameter("p")?.toIntOrNull() ?: 1).coerceAtLeast(1)
        val page = (0 until pages.length()).map { pages.getJSONObject(it) }
            .firstOrNull { it.optInt("page") == requestedPage } ?: throw IOException("Bilibili part not found.")
        val playUrl = "https://api.bilibili.com/x/player/playurl".toHttpUrl().newBuilder()
            .addQueryParameter("bvid", view.getString("bvid"))
            .addQueryParameter("cid", page.getLong("cid").toString())
            .addQueryParameter("qn", "64").addQueryParameter("fnval", "16")
            .addQueryParameter("fourk", "0").build()
        val data = fetchJson(playUrl.toString(), headers)
        val duration = data.optLong("timelength").takeIf { it > 0 }
        val dash = data.optJSONObject("dash")
        if (dash != null) {
            val videos = objects(dash.optJSONArray("video"))
                .filter { it.optInt("codecid") == 7 } // broadly supported AVC
            val video = videos.filter { it.optInt("height") <= 1080 }.maxByOrNull { it.optInt("height") }
                ?: videos.minByOrNull { it.optInt("height") }
                ?: throw IOException("No supported Bilibili video stream.")
            val audio = objects(dash.optJSONArray("audio")).maxByOrNull { it.optLong("bandwidth") }
                ?: throw IOException("Bilibili returned no soundtrack.")
            val audioPart = stream(cdnUrl(audio), audio.optString("mimeType", "audio/mp4"), "$cacheKey:audio", headers, duration)
            return stream(cdnUrl(video), video.optString("mimeType", "video/mp4"), cacheKey, headers, duration)
                .copy(companionAudio = audioPart)
        }
        val parts = objects(data.optJSONArray("durl"))
        if (parts.size != 1) throw IOException("This Bilibili video requires segmented playback or sign-in. Choose another source or a local file.")
        return stream(parts.single().getString("url"), "video/mp4", cacheKey, headers, duration)
    }

    private fun fetchJson(url: String, headers: Map<String, String>): JSONObject {
        val request = Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Bilibili request failed (${response.code}).")
            val root = JSONObject(response.body?.string() ?: throw IOException("Empty Bilibili response."))
            if (root.optInt("code", -1) != 0) throw IOException("Bilibili: ${root.optString("message", "video unavailable")}")
            root.getJSONObject("data")
        }
    }

    private fun cdnUrl(item: JSONObject): String = item.optString("baseUrl").ifBlank { item.optString("base_url") }
        .takeIf { it.startsWith("https://") } ?: throw IOException("No HTTPS Bilibili stream.")

    private fun objects(array: JSONArray?): List<JSONObject> =
        if (array == null) emptyList() else (0 until array.length()).map { array.getJSONObject(it) }

    private fun key(track: PlayableTrack): String {
        val identity = track.videoId?.let { "yt:$it" } ?: track.playbackUrl?.let { "local:$it" }
            ?: "song:${track.artist}\u0000${track.title}\u0000${track.album.orEmpty()}"
        return java.security.MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun stream(url: String, mime: String, key: String, headers: Map<String, String> = emptyMap(), duration: Long? = null) =
        MusicPlayer.ResolvedStream(url, mime, null, null, key, requestHeaders = headers, durationMs = duration,
            expiresAtEpochMs = url.toHttpUrlOrNull()?.queryParameter("deadline")?.toLongOrNull()?.times(1_000L))
}
