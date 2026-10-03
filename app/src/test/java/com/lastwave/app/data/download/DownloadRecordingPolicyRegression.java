package com.lastwave.app.data.download;

public final class DownloadRecordingPolicyRegression {
    private static int checks;
    public static void verify() {
        checks = 0;
        test(true, DownloadRecordingPolicy.hasSelectedRecording("liveVideo01"));
        test(false, DownloadRecordingPolicy.hasSelectedRecording(" "));
        test(false, DownloadRecordingPolicy.hasSelectedRecording(null));
        test(true, DownloadRecordingPolicy.mayReuseFile("liveVideo01", "liveVideo01"));
        test(false, DownloadRecordingPolicy.mayReuseFile("liveVideo01", "studioVid01"));
        test(false, DownloadRecordingPolicy.mayReuseFile("liveVideo01", null));
        test(true, DownloadRecordingPolicy.mayReuseFile(null, null));
        candidate(true, "liveVideo01", "liveVideo01", "The Mermaid's Song", null, "The Mermaid's Song", null);
        candidate(false, "liveVideo01", "studioVid01", "The Mermaid's Song", null, "The Mermaid's Song", null);
        candidate(false, "adoConcert1", "adoStudio01", "Monstruo", null, "Monstruo", null);
        candidate(false, null, "studioVid01", "Song (LIVE)", null, "Song", "Studio Album");
        candidate(true, null, "liveVideo01", "Song (LIVE)", null, "Song", "Live at Budokan");
        candidate(false, null, "studioVid01", "Song", "Live", "Song", "Studio Album");
        test(true, DownloadRecordingPolicy.preservePlayback("liveVideo01", true, "Monstruo", null, "Ado"));
        test(true, DownloadRecordingPolicy.preservePlayback("liveVideo01", false, "Song (Live)", null, "Ado"));
        test(false, DownloadRecordingPolicy.preservePlayback("studioVid01", false, "Song", null, "Ado"));
        test(false, DownloadRecordingPolicy.preservePlayback(null, true, "Song (Live)", null, "Ado"));
    }
    private static void candidate(boolean expected, String requested, String candidate,
                                  String title, String album, String candidateTitle, String candidateAlbum) {
        test(expected, DownloadRecordingPolicy.mayTryCandidate(requested, candidate,
            title, album, candidateTitle, candidateAlbum, "Artist"));
    }
    private static void test(boolean expected, boolean actual) {
        if (actual != expected) throw new AssertionError("Recording policy check " + (checks + 1));
        checks++;
    }
    public static void main(String[] args) {
        verify();
        System.out.println("PASS: " + checks + " exact recording policy regression checks");
    }
}
