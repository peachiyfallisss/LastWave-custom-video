package com.lastwave.app.data.lossless;

/** Runs without the Android SDK; the Gradle JUnit wrapper runs the same cases. */
public final class RecordingIdentityRegression {
    private static int checks;
    public static void verify() {
        checks = 0;
        check(false, "Song (Live)", null, "Song", null, "Artist");
        check(false, "Song", null, "Song (Live)", null, "Artist");
        check(false, "Song - Live at Budokan", null, "Song", null, "Artist");
        check(false, "Song", "Live at Budokan", "Song", "Studio Album", "Artist");
        check(false, "Song", "Studio Album", "Song", "Live", "Artist");
        check(true, "Song (Live)", null, "Song", "Live at Budokan", "Artist");
        check(true, "Song - Live", null, "Song (LIVE)", null, "Artist");
        check(true, "Song (Official Music Video)", null, "Song", null, "Artist");
        check(true, "Song [Official Audio]", null, "Song", null, "Artist");
        check(true, "Live Forever", null, "Live Forever", null, "Artist");
        check(true, "Artist - Song", null, "Song", null, "Artist");
        check(false, "Song (Acoustic)", null, "Song", null, "Artist");
        check(false, "Song", null, "Song (Instrumental)", null, "Artist");
        check(false, "Song (Remix)", null, "Song (Radio Edit)", null, "Artist");
        check(false, "Song (2011 Remastered)", null, "Song", null, "Artist");
        check(false, "Song", null, "Song - Clean Version", null, "Artist");
        check(false, "Song", null, "Song (Slowed + Reverb)", null, "Artist");
        check(false, "Song (Sped-Up)", null, "Song", null, "Artist");
        check(false, "Song", null, "Song (Cover)", null, "Artist");
        check(false, "Song", null, "Song (Demo)", null, "Artist");
        check(false, "Song", null, "Song (Extended Mix)", null, "Artist");
        check(false, "うっせぇわ (ライブ版)", null, "うっせぇわ", null, "Ado");
        check(false, "うっせぇわ (アコースティック)", null, "うっせぇわ", null, "Ado");
        check(false, "歌曲 (现场版)", null, "歌曲", null, "歌手");
        check(false, "Música (Ao Vivo)", null, "Música", null, "Artista");
        check(true, "Song (Live Acoustic)", null, "Song (Acoustic Live)", null, "Artist");
        if (!RecordingIdentity.variants("Song - Ｌｉｖｅ", null, "Artist").contains("live")) {
            throw new AssertionError("Full-width recording marker lost");
        }
        checks++;
    }
    private static void check(boolean expected, String title, String album, String candidate, String candidateAlbum, String artist) {
        boolean actual = RecordingIdentity.sameVersion(title, album, candidate, candidateAlbum, artist);
        if (actual != expected) throw new AssertionError(title + " / " + album + " => " + candidate + " / " + candidateAlbum
            + ": expected " + expected + ", got " + actual);
        checks++;
    }
    public static void main(String[] args) {
        verify();
        System.out.println("PASS: " + checks + " recording identity regression checks");
    }
}
