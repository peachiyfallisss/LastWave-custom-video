package com.lastwave.app.ui.playlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lastwave.app.data.music.InnerTubeMusicApi
import com.lastwave.app.data.music.YouTubeMusicTrack
import com.lastwave.app.data.video.ConcertSongSegment
import com.lastwave.app.data.video.ConcertVideo
import com.lastwave.app.data.video.ConcertVideoRepository
import com.lastwave.app.data.video.ConcertSongLink
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive

data class ConcertLibraryState(
    val recordings: List<ConcertVideo> = emptyList(),
    val searchResults: List<YouTubeMusicTrack> = emptyList(),
    val isSearching: Boolean = false,
    val isSaving: Boolean = false,
    val message: String? = null,
)

@HiltViewModel
class ConcertLibraryViewModel @Inject constructor(
    private val repository: ConcertVideoRepository,
    private val innerTube: InnerTubeMusicApi,
    val musicPlayer: MusicPlayer,
) : ViewModel() {
    private val _state = MutableStateFlow(ConcertLibraryState())
    val state: StateFlow<ConcertLibraryState> = _state.asStateFlow()
    private var searchJob: Job? = null

    init { refresh() }

    fun refresh() { viewModelScope.launch {
        try { reload() } catch (e: CancellationException) { throw e }
        catch (e: Exception) { _state.update { it.copy(message = "Could not load recordings. Existing data was not changed.") } }
    } }

    private suspend fun reload() {
        val recordings = withContext(Dispatchers.IO) { repository.all() }
        _state.update { it.copy(recordings = recordings) }
    }

    fun saveRecording(title: String, source: String, existingId: String? = null, onSaved: () -> Unit = {}) = runAction {
        withContext(Dispatchers.IO) { repository.save(title, source, existingId) }
        reload()
        onSaved()
        if (existingId == null) "Concert video added." else "Recording updated."
    }

    fun deleteRecording(id: String) = runAction {
        withContext(Dispatchers.IO) { repository.delete(id) }
        reload()
        "Concert video deleted."
    }

    fun searchSongs(query: String) {
        searchJob?.cancel()
        if (query.isBlank()) {
            _state.update { it.copy(searchResults = emptyList(), isSearching = false) }
            return
        }
        searchJob = viewModelScope.launch {
            _state.update { it.copy(isSearching = true, searchResults = emptyList(), message = null) }
            val results = runCatching { innerTube.searchSongs(query.trim(), limit = 12, prefetchStreams = false) }
            coroutineContext.ensureActive()
            _state.update {
                it.copy(
                    isSearching = false,
                    searchResults = results.getOrDefault(emptyList()),
                    message = results.exceptionOrNull()?.message ?: if (results.getOrNull().isNullOrEmpty()) "No YouTube Music songs found." else null,
                )
            }
        }
    }

    fun addSegment(concertId: String, track: YouTubeMusicTrack, startText: String, endText: String,
        existingId: String? = null, onSaved: () -> Unit = {}) = runAction {
        val start = parseConcertTimestampMs(startText) ?: error("Enter a valid start time (m:ss or h:mm:ss).")
        val end = parseConcertTimestampMs(endText) ?: error("Enter a valid end time (m:ss or h:mm:ss).")
        withContext(Dispatchers.IO) { repository.addSegment(
            concertId,
            ConcertSongSegment(
                id = existingId ?: java.util.UUID.randomUUID().toString(),
                title = track.title,
                artist = track.artist,
                youtubeVideoId = track.videoId,
                artworkUrl = track.artworkUrl,
                startMs = start,
                endMs = end,
            ),
        ) } ?: error("Concert recording no longer exists.")
        reload()
        _state.update { it.copy(searchResults = emptyList()) }
        onSaved()
        "Song segment linked."
    }

    fun removeSegment(concertId: String, segmentId: String) = runAction {
        withContext(Dispatchers.IO) { repository.removeSegment(concertId, segmentId) } ?: error("Concert recording no longer exists.")
        reload()
        "Song link removed."
    }

    fun linkWholeVideo(concertId: String, track: YouTubeMusicTrack?, onSaved: () -> Unit = {}) = runAction {
        withContext(Dispatchers.IO) { repository.linkWholeVideo(concertId, track?.let { ConcertSongLink(it.title, it.artist, it.videoId, it.artworkUrl) }) }
            ?: error("Concert recording no longer exists.")
        reload()
        onSaved()
        if (track == null) "Whole-video link removed." else "Entire recording linked. Play full video to use this song identity."
    }

    fun playFull(concert: ConcertVideo) {
        val linked = concert.wholeVideoSong
        musicPlayer.play(
            PlayableTrack(
                title = linked?.title ?: concert.title,
                artist = linked?.artist ?: "Concert recording",
                videoId = linked?.youtubeVideoId,
                artworkUrl = linked?.artworkUrl,
                preserveOriginalRecording = linked != null,
                videoSourceUrl = concert.source,
                concertId = concert.id,
                concertSegments = concert.segments,
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

    fun previewSegment(concert: ConcertVideo, segment: ConcertSongSegment) {
        musicPlayer.play(PlayableTrack(segment.title, segment.artist, artworkUrl = segment.artworkUrl,
            videoId = segment.youtubeVideoId, preserveOriginalRecording = true, videoSourceUrl = concert.source,
            videoSegmentStartMs = segment.startMs, videoSegmentEndMs = segment.endMs),
            sourceLabel = concert.title, startRadio = false)
    }

    fun sourcePosition(concert: ConcertVideo): Long? {
        val mode = musicPlayer.videoState.value
        if (!mode.enabled || mode.source != concert.source) return null
        return musicPlayer.progressState.value.positionMs + (mode.segmentStartMs ?: 0L)
    }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    private fun runAction(action: suspend () -> String) {
        if (_state.value.isSaving) return
        _state.update { it.copy(isSaving = true, message = null) }
        viewModelScope.launch {
            val message = runCatching { action() }.fold(
                onSuccess = { it },
                onFailure = { if (it is CancellationException) throw it; it.message ?: "Could not save this change." },
            )
            _state.update { it.copy(message = message, isSaving = false) }
        }
    }
}
