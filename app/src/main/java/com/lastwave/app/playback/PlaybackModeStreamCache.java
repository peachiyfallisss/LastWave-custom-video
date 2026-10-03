package com.lastwave.app.playback;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Retains each recording's audio and each explicitly selected video independently. */
public final class PlaybackModeStreamCache<T> {
    private final int capacity;
    private final Map<Key, Entry<T>> entries = new LinkedHashMap<>(16, 0.75f, true);

    public PlaybackModeStreamCache(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("capacity must be positive");
        this.capacity = capacity;
    }

    public synchronized T get(String mediaId, String videoSource, long nowMs) {
        Key key = new Key(mediaId, videoSource);
        Entry<T> entry = entries.get(key);
        if (entry == null) return null;
        if (entry.expiresAtMs <= nowMs) { entries.remove(key); return null; }
        return entry.value;
    }

    public synchronized void put(String mediaId, String videoSource, T value, long expiresAtMs) {
        entries.put(new Key(mediaId, videoSource), new Entry<>(value, expiresAtMs));
        while (entries.size() > capacity) {
            Iterator<Key> oldest = entries.keySet().iterator();
            oldest.next(); oldest.remove();
        }
    }

    public synchronized void remove(String mediaId, String videoSource) {
        entries.remove(new Key(mediaId, videoSource));
    }
    public synchronized void clear() { entries.clear(); }

    private static final class Entry<T> {
        final T value;
        final long expiresAtMs;
        Entry(T value, long expiresAtMs) { this.value = value; this.expiresAtMs = expiresAtMs; }
    }
    private static final class Key {
        final String mediaId;
        final String source;
        Key(String mediaId, String source) { this.mediaId = mediaId; this.source = source; }
        @Override public int hashCode() { return Objects.hash(mediaId, source); }
        @Override public boolean equals(Object other) {
            if (!(other instanceof Key)) return false;
            Key key = (Key) other;
            return Objects.equals(mediaId, key.mediaId) && Objects.equals(source, key.source);
        }
    }
}
