@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.lastwave.app.ui.player

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.lastwave.app.data.lyrics.*
import com.lastwave.app.playback.MusicPlayer
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LyricsEditorState(
    val key: String = "",
    val document: CustomLyricsDocument = CustomLyricsDocument(""),
    val loading: Boolean = true,
    val busy: Boolean = false,
    val saved: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class CustomLyricsEditorViewModel @Inject constructor(
    private val repository: CustomLyricsRepository,
    private val lyricsRepository: LyricsRepository,
    private val translator: GoogleLyricsTranslator,
) : ViewModel() {
    private val _state = MutableStateFlow(LyricsEditorState())
    val state = _state.asStateFlow()
    private var generation = 0
    private var work: Job? = null

    fun close() { ++generation; work?.cancel(); _state.update { it.copy(saved = false, loading = true) } }

    fun open(key: String, baseline: LyricsUiState.Success?) {
        val request = ++generation
        work?.cancel()
        _state.value = LyricsEditorState(key = key)
        work = viewModelScope.launch {
            try {
                val document = repository.get(key) ?: CustomLyricsDocument(
                    lyrics = editableLyrics(baseline?.lines.orEmpty(), baseline?.plainLyrics),
                    translations = baseline?.lines?.takeIf { rows -> rows.any { it.translation != null } }
                        ?.map { it.translation.orEmpty() }.orEmpty(),
                )
                if (generation == request) _state.update { it.copy(document = document, loading = false) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (generation == request) _state.update { it.copy(loading = false, error = e.message ?: "Could not read custom lyrics.") }
            }
        }
    }

    fun edit(document: CustomLyricsDocument) = _state.update { it.copy(document = document, error = null) }
    fun error(message: String?) = _state.update { it.copy(error = message) }

    fun useStudioLyrics(title: String, artist: String) = action {
        require(title.isNotBlank() && artist.isNotBlank()) { "Enter the studio song title and artist." }
        val result = lyricsRepository.getLyrics(title.trim(), artist.trim(), forceRefresh = true, wordByWord = true)
        require(result is LyricsResult.Success && !result.isInstrumental) { "No lyrics found. You can paste or write them instead." }
        _state.update { it.copy(document = it.document.copy(lyrics = editableLyrics(result.lines, result.plainLyrics), translations = emptyList())) }
    }

    fun translate(language: String, apiKey: String) = action {
        val document = _state.value.document
        val parsed = document.copy(translations = emptyList()).result()
        val rows = if (parsed.isSynced) parsed.lines.map { it.text } else document.lyrics.trim().lines()
        val translated = translator.translate(rows, language.trim(), apiKey)
        _state.update { it.copy(document = document.copy(translations = translated, translationLanguage = language.trim())) }
    }

    fun save() = action {
        repository.save(_state.value.key, _state.value.document)
        _state.update { it.copy(saved = true) }
    }

    fun reset() = action {
        repository.remove(_state.value.key)
        _state.update { it.copy(saved = true) }
    }

    private fun action(block: suspend () -> Unit) {
        if (_state.value.busy || _state.value.loading) return
        val request = generation
        work = viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (request == generation) _state.update { it.copy(error = e.message ?: "Could not update lyrics.") } }
            finally { if (request == generation) _state.update { it.copy(busy = false) } }
        }
    }
}

@Composable
fun CustomLyricsEditor(
    recordingKey: String,
    title: String,
    artist: String,
    baseline: LyricsUiState.Success?,
    player: MusicPlayer,
    timelineStartMs: Long = 0L,
    timelineDurationMs: Long? = null,
    onSaved: () -> Unit,
    onDismiss: () -> Unit,
    viewModel: CustomLyricsEditorViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var offset by remember(recordingKey) { mutableStateOf("0") }
    var translations by remember(recordingKey) { mutableStateOf("") }
    var language by remember(recordingKey) { mutableStateOf("en") }
    // Never rememberSaveable: credentials must not enter saved-instance state or disk.
    var apiKey by remember(recordingKey) { mutableStateOf("") }
    var studioTitle by remember(recordingKey) { mutableStateOf(title) }
    var studioArtist by remember(recordingKey) { mutableStateOf(artist) }
    var sync by remember(recordingKey) { mutableStateOf<LyricSyncSession?>(null) }
    var confirmReset by remember { mutableStateOf(false) }
    var tab by remember { mutableIntStateOf(0) }
    var reuseExpanded by remember { mutableStateOf(false) }
    var googleExpanded by remember { mutableStateOf(false) }
    var bulkTranslation by remember { mutableStateOf(false) }
    var translationRow by remember { mutableIntStateOf(0) }
    var originalDocument by remember(recordingKey) { mutableStateOf<CustomLyricsDocument?>(null) }
    var confirmDiscard by remember { mutableStateOf(false) }
    val requestDismiss = {
        if (!state.loading && originalDocument != null && state.document != originalDocument) confirmDiscard = true else onDismiss()
    }
    LaunchedEffect(recordingKey) { viewModel.open(recordingKey, baseline) }
    DisposableEffect(recordingKey) { onDispose { viewModel.close() } }
    LaunchedEffect(state.loading) {
        if (!state.loading) { offset = state.document.offsetMs.toString(); if (originalDocument == null) originalDocument = state.document }
    }
    LaunchedEffect(state.document.translations, state.document.translationLanguage) {
        translations = state.document.translations.joinToString("\n")
        if (state.document.translationLanguage.isNotBlank()) language = state.document.translationLanguage
    }
    LaunchedEffect(state.saved, state.key) { if (state.saved && !state.loading && state.key == recordingKey) { onSaved(); onDismiss() } }
    val enabled = !state.loading && !state.busy && sync == null

    Dialog(onDismissRequest = { if (!state.busy) requestDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth().fillMaxHeight(0.95f).padding(12.dp), shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(16.dp).imePadding()) {
                Text("Custom lyrics & translation", style = MaterialTheme.typography.titleLarge)
                Text(title, style = MaterialTheme.typography.bodyMedium)
                Text("Saved only for this recording and segment. Times are relative to the start of the playing song segment.", style = MaterialTheme.typography.bodySmall)
                if (state.loading || state.busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp))
                TabRow(selectedTabIndex = tab) {
                    listOf("Lyrics", "Timing", "Translation").forEachIndexed { index, label ->
                        Tab(selected = tab == index, enabled = !state.busy && (sync == null || index == 1), onClick = { tab = index }, text = { Text(label) })
                    }
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (tab == 0) {
                    OutlinedTextField(
                        value = state.document.lyrics,
                        onValueChange = { if (it.length <= CustomLyricsDocument.MAX_TEXT_LENGTH) viewModel.edit(state.document.copy(lyrics = it)) },
                        enabled = enabled, modifier = Modifier.fillMaxWidth(), minLines = 6,
                        label = { Text("Lyrics: plain text, LRC, or enhanced LRC") },
                        supportingText = { Text("Line: [01:23.450]text · Word: [01:23.450]<01:23.450>first <01:24.100>second") },
                    )
                    TextButton(enabled = enabled, onClick = { reuseExpanded = !reuseExpanded }) { Text("Reuse lyrics from another version") }
                    if (reuseExpanded) {
                        OutlinedTextField(studioTitle, { studioTitle = it }, enabled = enabled, label = { Text("Studio song title") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(studioArtist, { studioArtist = it }, enabled = enabled, label = { Text("Artist") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        TextButton(enabled = enabled, onClick = { viewModel.useStudioLyrics(studioTitle, studioArtist) }) { Text("Find lyrics to reuse") }
                    }
                    }
                    if (tab == 1) {
                    Text("Adjust the whole version, or tap along to build new timing.", style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(value = offset, onValueChange = { value ->
                        offset = value
                        value.toLongOrNull()?.let { viewModel.edit(state.document.copy(offsetMs = it)) }
                    }, enabled = enabled, singleLine = true, label = { Text("Recording offset (ms; + = later, − = earlier)") }, modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(enabled = enabled, onClick = {
                            runCatching { LyricSyncSession.start(state.document.lyrics, false) }.fold({ sync = it }, { viewModel.error(it.message) })
                        }) { Text("Sync lines") }
                        TextButton(enabled = enabled, onClick = {
                            runCatching { LyricSyncSession.start(state.document.lyrics, true) }.fold({ sync = it }, { viewModel.error(it.message) })
                        }) { Text("Sync words") }
                    }
                    sync?.let { session ->
                        Text("${session.stamps.size}/${session.total} · ${session.next ?: "Ready to apply"}")
                        Text("Play/pause or seek below; tap Stamp when the next line/word begins. Words are separated by spaces.", style = MaterialTheme.typography.bodySmall)
                        val progress by player.progressState.collectAsStateWithLifecycle()
                        val position = (progress.positionMs - timelineStartMs).coerceAtLeast(0L)
                        val duration = timelineDurationMs ?: progress.durationMs
                        Text(formatLyricTimestamp(position.coerceIn(0, CustomLyricsDocument.MAX_TIME_MS)))
                        Slider(value = position.toFloat().coerceIn(0f, duration.coerceAtLeast(1L).toFloat()),
                            onValueChange = { player.seekTo(it.toLong() + timelineStartMs) }, valueRange = 0f..duration.coerceAtLeast(1L).toFloat())
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = { player.togglePlayPause() }) { Text("Play/pause") }
                            TextButton(enabled = !session.complete, onClick = {
                                runCatching { session.stamp(player.progressState.value.positionMs - timelineStartMs) }.fold({ sync = it }, { viewModel.error(it.message) })
                            }) { Text("Stamp") }
                            TextButton(enabled = session.stamps.isNotEmpty(), onClick = { sync = session.copy(stamps = session.stamps.dropLast(1)) }) { Text("Undo") }
                        }
                        Row {
                            TextButton(enabled = session.complete, onClick = {
                                viewModel.edit(state.document.copy(lyrics = session.lrc(), offsetMs = 0, translations = emptyList()))
                                offset = "0"; sync = null
                            }) { Text("Apply timing") }
                            TextButton(onClick = { sync = null }) { Text("Cancel sync") }
                        }
                    }
                    }
                    if (tab == 2) {
                    Text("Translations follow the original timestamps.", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(!bulkTranslation, { bulkTranslation = false }, label = { Text("Line by line") })
                        FilterChip(bulkTranslation, { bulkTranslation = true }, label = { Text("Paste all") })
                    }
                    if (bulkTranslation) OutlinedTextField(translations, { value ->
                        translations = value
                        viewModel.edit(state.document.copy(translations = if (value.isBlank()) emptyList() else value.lines()))
                    }, enabled = enabled, label = { Text("Your translation") }, minLines = 4, modifier = Modifier.fillMaxWidth())
                    else {
                        val rows = remember(state.document.lyrics) {
                            val parsed = runCatching { state.document.copy(translations = emptyList()).result() }.getOrNull()
                            if (parsed?.isSynced == true) parsed.lines.map { it.text } else state.document.lyrics.trim().lines().filter { it.isNotBlank() || state.document.lyrics.isNotBlank() }
                        }
                        if (rows.isNotEmpty()) {
                            val row = translationRow.coerceIn(0, rows.lastIndex)
                            Text("Original · ${row + 1}/${rows.size}", style = MaterialTheme.typography.labelLarge)
                            Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.medium) {
                                Text(rows[row].ifBlank { "(empty line)" }, Modifier.fillMaxWidth().padding(16.dp), style = MaterialTheme.typography.titleMedium)
                            }
                            OutlinedTextField(state.document.translations.getOrNull(row).orEmpty(), { value ->
                                val aligned = MutableList(rows.size) { state.document.translations.getOrNull(it).orEmpty() }
                                aligned[row] = value
                                viewModel.edit(state.document.copy(translations = if (aligned.all { it.isBlank() }) emptyList() else aligned))
                            }, enabled = enabled, label = { Text("Translation for this line") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                TextButton(enabled = row > 0, onClick = { translationRow = row - 1 }) { Text("Previous line") }
                                TextButton(enabled = row < rows.lastIndex, onClick = { translationRow = row + 1 }) { Text("Next line") }
                            }
                        } else Text("Add lyrics in the Lyrics tab first.")
                    }
                    OutlinedTextField(language, { language = it }, enabled = enabled, label = { Text("Target language code") }, singleLine = true)
                    TextButton(enabled = enabled, onClick = { googleExpanded = !googleExpanded }) { Text("Generate a translation with Google") }
                    if (googleExpanded) {
                    OutlinedTextField(apiKey, { apiKey = it }, enabled = enabled, label = { Text("Google Cloud Translation API key (not saved)") },
                        visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
                    Text("Translate sends the lyric text to Google Cloud using your key; API charges may apply. Existing translations are replaced only after a complete successful response.", style = MaterialTheme.typography.bodySmall)
                    TextButton(enabled = enabled && apiKey.isNotBlank(), onClick = {
                        viewModel.translate(language, apiKey); apiKey = ""
                    }) { Text("Translate with Google") }
                    }
                    }
                    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(enabled = enabled, onClick = { confirmReset = true }) { Text("Reset") }
                    TextButton(enabled = !state.busy, onClick = requestDismiss) { Text("Cancel") }
                    Button(enabled = enabled, onClick = {
                        val parsedOffset = offset.toLongOrNull()
                        if (parsedOffset == null) viewModel.error("Enter a valid offset in milliseconds.")
                        else {
                            viewModel.edit(state.document.copy(offsetMs = parsedOffset, translationLanguage = language.trim()))
                            viewModel.save()
                        }
                    }) { Text("Save") }
                }
            }
        }
    }
    if (confirmReset) AlertDialog(onDismissRequest = { confirmReset = false }, title = { Text("Remove custom lyrics?") },
        text = { Text("This removes your lyrics, timing, and translation for this recording. Provider lyrics will be used again.") },
        confirmButton = { TextButton(onClick = { confirmReset = false; viewModel.reset() }) { Text("Remove") } },
        dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } })
    if (confirmDiscard) AlertDialog(onDismissRequest = { confirmDiscard = false }, title = { Text("Discard lyric changes?") },
        text = { Text("Your unsaved lyrics, timing, and translations will be lost.") },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Discard") } },
        dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Keep editing") } })
}
