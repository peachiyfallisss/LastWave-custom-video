package com.lastwave.app.data.download;

import com.lastwave.app.data.lossless.RecordingIdentity;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** A selected YouTube ID identifies the recording, even when its title omits Live. */
public final class DownloadRecordingPolicy {
    private DownloadRecordingPolicy() {}

    public static boolean hasSelectedRecording(String videoId) {
        return videoId != null && !videoId.trim().isEmpty();
    }

    /** Keeps recordings and names that sanitize alike in distinct public files. */
    public static String filenameSuffix(String downloadKey, String videoId) {
        String identity = hasSelectedRecording(videoId) ? "video:" + videoId.trim() : "track:" + downloadKey;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8));
            StringBuilder suffix = new StringBuilder(" [");
            for (int i = 0; i < 8; i++) {
                suffix.append(Character.forDigit((digest[i] >>> 4) & 15, 16));
                suffix.append(Character.forDigit(digest[i] & 15, 16));
            }
            return suffix.append(']').toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
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
