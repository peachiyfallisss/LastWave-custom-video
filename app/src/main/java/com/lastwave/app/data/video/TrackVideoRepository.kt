package com.lastwave.app.data.video

import android.content.Context
import android.net.Uri
import com.lastwave.app.data.music.YouTubeStreamExtractor
import com.lastwave.app.playback.MusicPlayer
import com.lastwave.app.playback.PlayableTrack
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/** Store stable page/document URIs; signed CDN URLs are resolved afresh on every switch. */
@Singleton
class TrackVideoRepository @Inject constructor(
    @ApplicationContext context: Context,
    private val youtube: YouTubeStreamExtractor,
    http: OkHttpClient,
) {
    private val contentResolver = context.contentResolver
    private val preferences = context.getSharedPreferences("lastwave_track_videos", Context.MODE_PRIVATE)
    private val client = http.newBuilder().callTimeout(20, TimeUnit.SECONDS).build()

    fun sourceFor(track: PlayableTrack): String? = preferences.getString(key(track), null)
        ?: track.videoId?.let { "https://www.youtube.com/watch?v=$it" }

    fun customSourceFor(track: PlayableTrack): String? = preferences.getString(key(track), null)

    fun save(track: PlayableTrack, source: String) {
        val uri = Uri.parse(source.trim())
        require(uri.scheme == "content" || uri.scheme == "https") {
            "Choose a video from your device or an HTTPS video link."
        }
        preferences.edit().putString(key(track), source.trim()).apply()
    }

    fun remove(track: PlayableTrack) { preferences.edit().remove(key(track)).apply() }

    suspend fun resolve(source: String): MusicPlayer.ResolvedStream = withContext(Dispatchers.IO) {
        val uri = Uri.parse(source)
        val cacheKey = "video:$source:${System.nanoTime()}"
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
        MusicPlayer.ResolvedStream(url, mime, null, null, key, requestHeaders = headers, durationMs = duration)
}
