package com.lastwave.app.data.video

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** A YouTube song mapped to a time range in one imported concert recording. */
@Serializable
data class ConcertSongSegment(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val artist: String,
    val youtubeVideoId: String,
    val artworkUrl: String? = null,
    val startMs: Long,
    val endMs: Long,
)

/** One full external/device video, with zero or more YouTube song mappings. */
@Serializable
data class ConcertVideo(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val source: String,
    val segments: List<ConcertSongSegment> = emptyList(),
    val createdAtMillis: Long = System.currentTimeMillis(),
)

@Singleton
class ConcertVideoRepository @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val preferences = context.getSharedPreferences("lastwave_concert_videos", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    fun all(): List<ConcertVideo> = runCatching {
        json.decodeFromString<List<ConcertVideo>>(preferences.getString(KEY, null).orEmpty())
            .sortedByDescending(ConcertVideo::createdAtMillis)
    }.getOrDefault(emptyList())

    fun save(title: String, source: String, existingId: String? = null): ConcertVideo {
        val cleanTitle = title.trim()
        val cleanSource = source.trim()
        require(cleanTitle.isNotBlank()) { "Enter a name for this concert." }
        val uri = Uri.parse(cleanSource)
        require(uri.scheme == "content" || (uri.scheme == "https" && uri.host != null)) {
            "Choose a video from your device or paste an HTTPS video link."
        }
        val current = all()
        val existing = current.firstOrNull { it.id == existingId }
        val saved = existing?.copy(title = cleanTitle, source = cleanSource)
            ?: ConcertVideo(title = cleanTitle, source = cleanSource)
        persist(if (existing == null) current + saved else current.map { if (it.id == saved.id) saved else it })
        return saved
    }

    fun addSegment(concertId: String, segment: ConcertSongSegment): ConcertVideo? {
        require(segment.youtubeVideoId.matches(YOUTUBE_ID)) { "Choose a YouTube song result first." }
        require(segment.startMs >= 0L && segment.endMs > segment.startMs) { "The end time must be after the start time." }
        val concert = all().firstOrNull { it.id == concertId } ?: return null
        val updated = concert.copy(segments = (concert.segments.filterNot { it.id == segment.id } + segment).sortedBy { it.startMs })
        persist(all().map { if (it.id == concertId) updated else it })
        return updated
    }

    fun removeSegment(concertId: String, segmentId: String): ConcertVideo? {
        val concert = all().firstOrNull { it.id == concertId } ?: return null
        val updated = concert.copy(segments = concert.segments.filterNot { it.id == segmentId })
        persist(all().map { if (it.id == concertId) updated else it })
        return updated
    }

    fun delete(concertId: String) = persist(all().filterNot { it.id == concertId })

    private fun persist(concerts: List<ConcertVideo>) {
        check(preferences.edit().putString(KEY, json.encodeToString(concerts)).commit()) {
            "Could not save this concert recording."
        }
    }

    private companion object {
        const val KEY = "concerts"
        val YOUTUBE_ID = Regex("[A-Za-z0-9_-]{11}")
    }
}

/** Accepts the familiar m:ss or h:mm:ss form used by concert timestamps. */
fun parseConcertTimestampMs(value: String): Long? {
    val parts = value.trim().split(':')
    if (parts.size !in 2..3 || parts.any { it.isBlank() || it.toLongOrNull() == null }) return null
    val numbers = parts.map(String::toLong)
    if (numbers.any { it < 0L } || numbers.drop(1).any { it >= 60L }) return null
    val seconds = when (numbers.size) {
        2 -> numbers[0] * 60L + numbers[1]
        else -> numbers[0] * 3_600L + numbers[1] * 60L + numbers[2]
    }
    return seconds * 1_000L
}
