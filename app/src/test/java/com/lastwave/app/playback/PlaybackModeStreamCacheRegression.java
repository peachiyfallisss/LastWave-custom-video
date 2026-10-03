package com.lastwave.app.playback;

public final class PlaybackModeStreamCacheRegression {
    public static void verify() {
        PlaybackModeStreamCache<String> cache = new PlaybackModeStreamCache<>(3);
        cache.put("song", null, "original-audio", 1000);
        cache.put("song", "video-a", "video-audio", 1000);
        expect("original-audio", cache.get("song", null, 100));
        expect("video-audio", cache.get("song", "video-a", 100));
        expect(null, cache.get("song", "video-b", 100)); // choosing a different video cannot reuse the old one
        expect(null, cache.get("other-song", null, 100));
        expect(null, cache.get("song", "video-a", 1000)); // signed streams must expire
        expect("original-audio", cache.get("song", null, 999));
        cache.put("second", null, "second-audio", 5000);
        cache.put("third", null, "third-audio", 5000);
        cache.get("song", null, 999); // the source we just used should survive eviction
        cache.put("fourth", null, "fourth-audio", 5000);
        expect(null, cache.get("second", null, 999));
        expect("original-audio", cache.get("song", null, 999));
        cache.remove("song", null);
        expect(null, cache.get("song", null, 999));
        cache.clear();
        expect(null, cache.get("third", null, 999));
        cache.put("local", "content://video/1", "local-video", Long.MAX_VALUE);
        expect("local-video", cache.get("local", "content://video/1", 999_999));
    }
    private static void expect(String expected, String actual) {
        if (!java.util.Objects.equals(expected, actual)) throw new AssertionError("Expected " + expected + ", got " + actual);
    }
    public static void main(String[] args) {
        verify();
        System.out.println("PASS: 11 playback mode cache regression checks");
    }
}
