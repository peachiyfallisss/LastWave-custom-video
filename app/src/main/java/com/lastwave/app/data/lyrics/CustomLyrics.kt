package com.lastwave.app.data.lyrics

import java.security.MessageDigest
import kotlinx.serialization.Serializable

/** The selected recording, not just the song name. Segment times are source-relative. */
fun customLyricsKey(
    videoId: String?, title: String, artist: String, playbackUrl: String?,
    videoSource: String? = null, startMs: Long? = null, endMs: Long? = null,
): String {
    val identity = listOf(
        videoId?.takeIf { it.isNotBlank() } ?: playbackUrl?.takeIf { it.isNotBlank() }
            ?: listOf(artist.trim().lowercase(), title.trim().lowercase()).joinToString("\u0000"),
        videoSource.orEmpty(), startMs?.toString().orEmpty(), endMs?.toString().orEmpty(),
    ).joinToString("\u0000")
    return MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

@Serializable
data class CustomLyricsDocument(
    val lyrics: String,
    /** Positive delays the lyrics; unlike the global display correction this belongs to one recording. */
    val offsetMs: Long = 0L,
    val translations: List<String> = emptyList(),
    val translationLanguage: String = "",
) {
    fun result(): LyricsResult.Success {
        require(lyrics.isNotBlank() && lyrics.length <= MAX_TEXT_LENGTH) { "Enter lyrics (up to 200,000 characters)." }
        require(offsetMs in -MAX_TIME_MS..MAX_TIME_MS) { "Offset must be within 24 hours." }
        val embeddedOffset = EMBEDDED_OFFSET.findAll(lyrics).lastOrNull()?.groupValues?.get(1)?.let {
            it.toLongOrNull()?.takeIf { value -> value in -MAX_TIME_MS..MAX_TIME_MS }
                ?: throw IllegalArgumentException("LRC offset must be within 24 hours.")
        } ?: 0L
        val body = lyrics.replace(EMBEDDED_OFFSET, "")
        val lines = LyricsRepository.parseLrc(body)
        if (lyrics.contains(TIME_LIKE)) {
            require(lines.isNotEmpty()) { "Use LRC timestamps such as [01:23.450]." }
            lyrics.lineSequence().filter { it.isNotBlank() }.forEach { row ->
                require(LINE_STAMP.find(row.trim())?.range?.first == 0 || row.trim().matches(METADATA)) {
                    "Every synchronized line needs a timestamp such as [01:23.450]."
                }
            }
            require(!TIME_LIKE.containsMatchIn(body.replace(LINE_STAMP, "").replace(WORD_STAMP, ""))) {
                "A timestamp is invalid. Use minutes:seconds.milliseconds with seconds from 00 to 59."
            }
        }
        require(lines.all { it.timeMs in 0..MAX_TIME_MS && it.syllables.all { word -> word.timeMs in 0..MAX_TIME_MS } }) {
            "Lyric timestamps must be within 24 hours."
        }
        val plainRows = lyrics.trim().lines()
        val count = if (lines.isEmpty()) plainRows.size else lines.size
        require(translations.isEmpty() || translations.size == count) { "Provide exactly one translation per lyric line ($count lines)." }
        require(translations.sumOf { it.length } <= MAX_TEXT_LENGTH) { "Translation is too long." }
        val shifted = lines.mapIndexed { index, line ->
            line.copy(
                timeMs = (line.timeMs + offsetMs + embeddedOffset).coerceAtLeast(0L),
                syllables = line.syllables.map { it.copy(timeMs = (it.timeMs + offsetMs + embeddedOffset).coerceAtLeast(0L)) },
                translation = translations.getOrNull(index)?.takeIf { it.isNotBlank() },
            )
        }
        require(shifted.all { it.timeMs <= MAX_TIME_MS && it.syllables.all { word -> word.timeMs <= MAX_TIME_MS } }) {
            "The shifted lyric timing exceeds 24 hours. Reduce the recording offset."
        }
        return LyricsResult.Success(
            lines = shifted,
            isSynced = lines.isNotEmpty(),
            isWordSynced = lines.any { it.hasSyllables },
            plainLyrics = if (lines.isEmpty() && translations.isNotEmpty()) plainRows.mapIndexed { i, row ->
                listOf(row, translations[i]).filter { it.isNotBlank() }.joinToString("\n")
            }.joinToString("\n\n") else plainText(),
            source = "Custom lyrics" + translationLanguage.takeIf { translations.isNotEmpty() && it.isNotBlank() }?.let { " · $it" }.orEmpty(),
            isCustom = true,
        )
    }

    fun plainText(): String = lyrics.lines().filterNot { it.trim().matches(METADATA) }
        .joinToString("\n") { it.replace(LINE_STAMP, "").replace(WORD_STAMP, "").trim() }.trim()

    companion object {
        const val MAX_TEXT_LENGTH = 200_000
        const val MAX_TIME_MS = 86_400_000L
        private val TIME_LIKE = Regex("[\\[<]\\d+:")
        private val EMBEDDED_OFFSET = Regex("\\[offset:\\s*([+-]?\\d+)\\s*]", RegexOption.IGNORE_CASE)
        private val LINE_STAMP = Regex("\\[\\d{1,4}:[0-5]\\d(?:[.:]\\d{1,3})?]")
        private val WORD_STAMP = Regex("<\\d{1,4}:[0-5]\\d(?:[.:]\\d{1,3})?>")
        private val METADATA = Regex("\\[(?:ar|ti|al|by|re|ve|length|offset):[^]]*]", RegexOption.IGNORE_CASE)
    }
}

fun formatLyricTimestamp(timeMs: Long): String {
    require(timeMs in 0..CustomLyricsDocument.MAX_TIME_MS)
    return "%02d:%02d.%03d".format(java.util.Locale.ROOT, timeMs / 60_000, timeMs / 1_000 % 60, timeMs % 1_000)
}

/** Export provider lyrics without throwing away existing word timing or authored spacing. */
fun editableLyrics(lines: List<LyricLine>, plain: String?): String = if (lines.isEmpty()) plain.orEmpty() else
    lines.joinToString("\n") { line ->
        val body = if (line.syllables.isEmpty()) line.text else line.syllables.mapIndexed { index, word ->
            (if (index > 0 && !word.appendToPrevious) " " else "") + "<${formatLyricTimestamp(word.timeMs)}>" + word.text
        }.joinToString("")
        "[${formatLyricTimestamp(line.timeMs)}]$body"
    }

/** Tap-to-sync session. Nothing is committed until all lines/words have been stamped. */
data class LyricSyncSession(val rows: List<List<String>>, val wordMode: Boolean, val stamps: List<Long> = emptyList()) {
    val total: Int get() = rows.sumOf { it.size }
    val complete: Boolean get() = stamps.size == total
    val next: String? get() = rows.flatten().getOrNull(stamps.size)
    fun stamp(positionMs: Long): LyricSyncSession {
        require(!complete) { "All lyrics are already synchronized." }
        require(positionMs in 0..CustomLyricsDocument.MAX_TIME_MS && (stamps.lastOrNull()?.let { positionMs > it } != false)) {
            "Seek after the previous timestamp, or undo it before stamping again."
        }
        return copy(stamps = stamps + positionMs)
    }
    fun lrc(): String {
        require(complete) { "Stamp every line or word before applying." }
        var index = 0
        return rows.joinToString("\n") { row ->
            val start = stamps[index]
            val body = row.joinToString("") { token ->
                val time = stamps[index++]
                if (wordMode) "<${formatLyricTimestamp(time)}>$token" else token
            }
            "[${formatLyricTimestamp(start)}]$body"
        }
    }
    companion object {
        fun start(text: String, wordMode: Boolean): LyricSyncSession {
            val plain = CustomLyricsDocument(text).plainText()
            val rows = plain.lines().filter { it.isNotBlank() }.map { row ->
                if (wordMode) Regex("\\S+\\s*").findAll(row).map { it.value }.toList() else listOf(row)
            }
            require(rows.isNotEmpty()) { "Enter lyrics before synchronizing." }
            return LyricSyncSession(rows, wordMode)
        }
    }
}
