package com.lastwave.app.ui.playlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lastwave.app.data.music.InnerTubeMusicApi
import com.lastwave.app.data.music.YouTubeMusicTrack
import com.lastwave.app.data.video.ConcertSongSegment
import com.lastwave.app.data.video.ConcertVideo
import com.lastwave.app.data.video.ConcertVideoRepository
import com.lastwave.app.data.video.parseConcertTimestampMs
import com.lastwave.app.playback.MusicPlayer
import com.lastwave.app.playback.PlayableTrack
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ConcertLibraryState(
    val recordings: List<ConcertVideo> = emptyList(),
    val searchResults: List<YouTubeMusicTrack> = emptyList(),
    val isSearching: Boolean = false,
    val message: String? = null,
)

@HiltViewModel
class ConcertLibraryViewModel @Inject constructor(
    private val repository: ConcertVideoRepository,
    private val innerTube: InnerTubeMusicApi,
    private val musicPlayer: MusicPlayer,
) : ViewModel() {
    private val _state = MutableStateFlow(ConcertLibraryState())
    val state: StateFlow<ConcertLibraryState> = _state.asStateFlow()

    init { refresh() }

    fun refresh() = _state.update { it.copy(recordings = repository.all()) }

    fun saveRecording(title: String, source: String) = runAction {
        repository.save(title, source)
        refresh()
        "Concert video added."
    }

    fun deleteRecording(id: String) = runAction {
        repository.delete(id)
        refresh()
        "Concert video deleted."
    }

    fun searchSongs(query: String) {
        if (query.isBlank()) return
        viewModelScope.launch {
            _state.update { it.copy(isSearching = true, searchResults = emptyList(), message = null) }
            val results = runCatching { innerTube.searchSongs(query.trim(), limit = 12, prefetchStreams = false) }
            _state.update {
                it.copy(
                    isSearching = false,
                    searchResults = results.getOrDefault(emptyList()),
                    message = results.exceptionOrNull()?.message ?: if (results.getOrNull().isNullOrEmpty()) "No YouTube Music songs found." else null,
                )
            }
        }
    }

    fun addSegment(concertId: String, track: YouTubeMusicTrack, startText: String, endText: String) = runAction {
        val start = parseConcertTimestampMs(startText) ?: error("Enter a valid start time (m:ss or h:mm:ss).")
        val end = parseConcertTimestampMs(endText) ?: error("Enter a valid end time (m:ss or h:mm:ss).")
        repository.addSegment(
            concertId,
            ConcertSongSegment(
                title = track.title,
                artist = track.artist,
                youtubeVideoId = track.videoId,
                artworkUrl = track.artworkUrl,
                startMs = start,
                endMs = end,
            ),
        ) ?: error("Concert recording no longer exists.")
        refresh()
        _state.update { it.copy(searchResults = emptyList()) }
        "Song segment linked."
    }

    fun removeSegment(concertId: String, segmentId: String) = runAction {
        repository.removeSegment(concertId, segmentId) ?: error("Concert recording no longer exists.")
        refresh()
        "Song link removed."
    }

    fun playFull(concert: ConcertVideo) {
        musicPlayer.play(
            PlayableTrack(
                title = concert.title,
                artist = "Concert recording",
                videoSourceUrl = concert.source,
            ),
            sourceLabel = "Concert: ${concert.title}",
            startRadio = false,
        )
    }

    fun playMappedSegments(concert: ConcertVideo) {
        val tracks = concert.segments.sortedBy { it.startMs }.map { segment ->
            PlayableTrack(
                title = segment.title,
                artist = segment.artist,
                artworkUrl = segment.artworkUrl,
                videoId = segment.youtubeVideoId,
                preserveOriginalRecording = true,
                videoSourceUrl = concert.source,
                videoSegmentStartMs = segment.startMs,
                videoSegmentEndMs = segment.endMs,
            )
        }
        if (tracks.isEmpty()) {
            _state.update { it.copy(message = "Add at least one linked song segment first.") }
            return
        }
        musicPlayer.playQueue(tracks, sourceLabel = "Concert: ${concert.title}")
    }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    private inline fun runAction(crossinline action: () -> String) {
        viewModelScope.launch {
            val message = runCatching(action).fold(
                onSuccess = { it },
                onFailure = { it.message ?: "Could not save this change." },
            )
            _state.update { it.copy(message = message) }
        }
    }
}
