package com.lastwave.app.data.lyrics

import org.junit.Assert.*
import org.junit.Test

class CustomLyricsTest {
    @Test fun plainLyricsRemainUsableOffline() {
        val result = CustomLyricsDocument("First\nSecond").result()
        assertFalse(result.isSynced)
        assertEquals("First\nSecond", result.plainLyrics)
        assertTrue(result.isCustom)
    }
    @Test fun positiveOffsetDelaysLinesAndWords() {
        val result = CustomLyricsDocument("[00:10.000]<00:10.000>one <00:11.000>two", offsetMs = 2000).result()
        assertEquals(12000L, result.lines.single().timeMs)
        assertEquals(listOf(12000L, 13000L), result.lines.single().syllables.map { it.timeMs })
    }
    @Test fun negativeOffsetClampsAtZero() {
        assertEquals(0L, CustomLyricsDocument("[00:01.000]line", offsetMs = -2000).result().lines.single().timeMs)
    }
    @Test fun translationDoesNotChangeOriginalTiming() {
        val result = CustomLyricsDocument("[00:10.000]one\n[00:20.000]two", translations = listOf("um", "dois")).result()
        assertEquals(listOf(10000L, 20000L), result.lines.map { it.timeMs })
        assertEquals(listOf("um", "dois"), result.lines.map { it.translation })
    }
    @Test fun blankTranslationRowsKeepAlignment() {
        val result = CustomLyricsDocument("[00:10.000]one\n[00:20.000]two", translations = listOf("", "dois")).result()
        assertNull(result.lines[0].translation)
        assertEquals("dois", result.lines[1].translation)
    }
    @Test fun translationCountMismatchIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { CustomLyricsDocument("[00:10.000]one\n[00:20.000]two", translations = listOf("um")).result() }
    }
    @Test fun malformedLineIsNotSilentlyDropped() {
        assertThrows(IllegalArgumentException::class.java) { CustomLyricsDocument("[00:10.000]one\nforgot timestamp").result() }
    }
    @Test fun invalidSecondsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { CustomLyricsDocument("[00:99.000]one").result() }
    }
    @Test fun longConcertAndSingleDecimalTimestampsWork() {
        assertEquals(6_001_500L, CustomLyricsDocument("[100:01.5]one").result().lines.single().timeMs)
    }
    @Test fun offsetsCannotOverflow() {
        assertThrows(IllegalArgumentException::class.java) { CustomLyricsDocument("one", offsetMs = Long.MAX_VALUE).result() }
    }
    @Test fun shiftedTimelineMustRemainEditable() {
        assertThrows(IllegalArgumentException::class.java) { CustomLyricsDocument("[1440:00.000]one", offsetMs = 1000).result() }
    }
    @Test fun recordingKeysSeparateSourcesAndSegments() {
        fun key(source: String?, start: Long? = null) = customLyricsKey("abcdefghijk", "Song", "Artist", null, source, start, 10000)
        assertNotEquals(key(null), key("content://concert"))
        assertNotEquals(key("content://concert", 0), key("content://concert", 1000))
        assertEquals(key("content://concert", 0), key("content://concert", 0))
    }
    @Test fun localFilesWithMatchingTitlesHaveDifferentKeys() {
        assertNotEquals(customLyricsKey(null, "Song", "Artist", "/one"), customLyricsKey(null, "Song", "Artist", "/two"))
    }
    @Test fun lineTapSyncDoesNotInventTiming() {
        val session = LyricSyncSession.start("first\nsecond", false).stamp(1000)
        assertFalse(session.complete)
        assertThrows(IllegalArgumentException::class.java) { session.lrc() }
        assertEquals("[00:01.000]first\n[00:03.000]second", session.stamp(3000).lrc())
    }
    @Test fun wordTapSyncPreservesWhitespaceAndPunctuation() {
        val session = LyricSyncSession.start("Hello,  world!", true).stamp(1000).stamp(2500)
        assertEquals("[00:01.000]<00:01.000>Hello,  <00:02.500>world!", session.lrc())
        assertTrue(CustomLyricsDocument(session.lrc()).result().isWordSynced)
    }
    @Test fun tapSyncRejectsBackwardsSeeks() {
        val session = LyricSyncSession.start("one\ntwo", false).stamp(2000)
        assertThrows(IllegalArgumentException::class.java) { session.stamp(1000) }
    }
    @Test fun providerWordTimingSurvivesExport() {
        val lines = listOf(LyricLine(1000, text = "hello world", syllables = listOf(LyricSyllable(1000, 500, "hello"), LyricSyllable(2000, 500, "world"))))
        val parsed = CustomLyricsDocument(editableLyrics(lines, null)).result()
        assertEquals(listOf(1000L, 2000L), parsed.lines.single().syllables.map { it.timeMs })
    }
    @Test fun plainTranslationsAreBilingual() {
        assertEquals("one\num\n\ntwo\ndois", CustomLyricsDocument("one\ntwo", translations = listOf("um", "dois")).result().plainLyrics)
    }
    @Test fun embeddedLrcOffsetAlsoMovesWordTiming() {
        val result = CustomLyricsDocument("[offset:2000]\n[00:10.000]<00:10.000>one <00:11.000>two").result()
        assertEquals(12000L, result.lines.single().timeMs)
        assertEquals(12000L, result.lines.single().syllables.first().timeMs)
    }
    @Test fun invalidEnhancedTimestampIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { CustomLyricsDocument("[00:10.000]<00:99.000>one").result() }
    }
    @Test fun translationBatchingKeepsBlankRowIndices() {
        assertEquals(listOf(listOf(0, 2)), lyricTranslationBatches(listOf("one", "", "two")))
    }
    @Test fun translationBatchingIsBoundedByTextNotJustRows() {
        assertEquals(listOf(listOf(0), listOf(1)), lyricTranslationBatches(listOf("a".repeat(3000), "b".repeat(3000))))
    }
    @Test fun translationBatchingLimitsStringCount() {
        assertEquals(listOf(50, 1), lyricTranslationBatches(List(51) { "x" }).map { it.size })
    }
    @Test fun translatedEntitiesPreserveNonBmpCharacters() {
        assertEquals("It's 🌊 & waves", decodeLyricTranslation("It&#39;s &#x1F30A; &amp; waves"))
    }
}
