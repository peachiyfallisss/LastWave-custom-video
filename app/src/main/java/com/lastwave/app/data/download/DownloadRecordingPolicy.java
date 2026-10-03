package com.lastwave.app.data.download;

import com.lastwave.app.data.lossless.RecordingIdentity;

/** A selected YouTube ID identifies the recording, even when its title omits Live. */
public final class DownloadRecordingPolicy {
    private DownloadRecordingPolicy() {}

    public static boolean hasSelectedRecording(String videoId) {
        return videoId != null && !videoId.trim().isEmpty();
    }

    public static boolean mayReuseFile(String requestedVideoId, String recordedVideoId) {
        return !hasSelectedRecording(requestedVideoId) || requestedVideoId.trim().equals(recordedVideoId);
    }

    public static boolean preservePlayback(String videoId, boolean preserveOriginal,
                                           String title, String album, String artist) {
        return hasSelectedRecording(videoId) && (preserveOriginal ||
            !RecordingIdentity.variants(title, album, artist).isEmpty());
    }

    public static boolean mayTryCandidate(String requestedVideoId, String candidateVideoId,
                                         String title, String album, String candidateTitle,
                                         String candidateAlbum, String artist) {
        if (hasSelectedRecording(requestedVideoId)) return requestedVideoId.trim().equals(candidateVideoId);
        return RecordingIdentity.sameVersion(title, album, candidateTitle, candidateAlbum, artist);
    }
}
