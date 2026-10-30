package com.lastwave.app.data.download

import java.util.Locale

/** Per-job measurements only. No track names, URLs, cookies, or signed parameters. */
internal class DownloadTrace(private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L }) {
    enum class Role { AUDIO, VIDEO, VIDEO_AUDIO }
    enum class Phase { PREPARE_METADATA, AUDIO_RESOLVE, VIDEO_RESOLVE, TRANSFER_SLOT_WAIT, FINISH_SLOT_WAIT, FINISHING }
    internal class Stats {
        var firstStart: Long? = null
        var lastEnd: Long? = null
        var bytes = 0L
        var calls = 0
        var ended = 0
        var failed = 0
        var cancelled = 0
        var firstByteMaxMs = 0L
        var headerWaitMaxMs = 0L
        var setupMaxMs = 0L
        var ranged = 0
        var maxRangeBytes = 0L
        var googleVideo = 0
        var withN = 0
    }
    private class Track(val start: Long) {
        var worker: Long? = null
        var end: Long? = null
        var outcome = "running"
        var profile = "unknown"
        var bitrate = 0
        var videoHeight = 0
        var logicalAudio = 0L
        var logicalVideo = 0L
        var logicalVideoAudio = 0L
        val starts = mutableMapOf<Phase, Long>()
        val phaseMs = mutableMapOf<Phase, Long>()
        val stats = mutableMapOf<Role, Stats>()
    }
    private val lock = Any()
    private var nextId = 0L
    private val tracks = linkedMapOf<Long, Track>()
    private val completed = ArrayDeque<Long>()

    fun begin(): Long = synchronized(lock) { (++nextId).also { tracks[it] = Track(nowMs()) } }
    fun worker(id: Long) = synchronized(lock) { tracks[id]?.worker = nowMs(); startPhase(id, Phase.PREPARE_METADATA) }
    fun source(id: Long, profile: String, bitrate: Int) = synchronized(lock) {
        tracks[id]?.apply {
            // Profile names come from extractor constants; never accept arbitrary text in reports.
            this.profile = profile.replace(Regex("[^A-Za-z0-9_:.-]"), "").take(64)
            this.bitrate = bitrate.coerceAtLeast(0)
        }; Unit
    }
    fun video(id: Long, height: Int) = synchronized(lock) { tracks[id]?.videoHeight = height.coerceAtLeast(0) }
    fun logicalMedia(id: Long, audio: Long, video: Long, videoAudio: Long) = synchronized(lock) {
        tracks[id]?.apply { logicalAudio = audio; logicalVideo = video; logicalVideoAudio = videoAudio }; Unit
    }
    fun startPhase(id: Long, phase: Phase) = synchronized(lock) {
        tracks[id]?.starts?.putIfAbsent(phase, nowMs()); Unit
    }
    fun endPhase(id: Long, phase: Phase) = synchronized(lock) {
        tracks[id]?.let { track -> track.starts.remove(phase)?.let { start ->
            track.phaseMs[phase] = track.phaseMs.getOrDefault(phase, 0L) + (nowMs() - start).coerceAtLeast(0)
        } }; Unit
    }
    fun finish(id: Long, outcome: String) = synchronized(lock) {
        val track = tracks[id] ?: return@synchronized
        if (track.end != null) return@synchronized
        track.end = nowMs()
        track.outcome = when (outcome) { "success", "error", "cancelled" -> outcome; else -> "unknown" }
        track.starts.keys.toList().forEach { endPhase(id, it) }
        completed.addLast(id)
        while (completed.size > 20) tracks.remove(completed.removeFirst())
    }

    fun call(id: Long, role: Role, rangedBytes: Long?, googleVideo: Boolean, hasN: Boolean, hasRange: Boolean = rangedBytes != null): Call = synchronized(lock) {
        val stats = tracks[id]?.stats?.getOrPut(role) { Stats() }
        val start = nowMs()
        stats?.apply {
            calls++; firstStart = firstStart?.coerceAtMost(start) ?: start
            if (hasRange) ranged++
            if (rangedBytes != null) maxRangeBytes = maxOf(maxRangeBytes, rangedBytes)
            if (googleVideo) this.googleVideo++
            if (hasN) withN++
        }
        Call(stats, start)
    }

    inner class Call internal constructor(private val stats: Stats?, private val start: Long) {
        private var sent: Long? = null
        private var firstByte = false
        private var ended = false
        fun sent() = synchronized(lock) {
            sent = nowMs()
            stats?.setupMaxMs = maxOf(stats?.setupMaxMs ?: 0, nowMs() - start)
        }
        fun headers() = synchronized(lock) {
            stats?.headerWaitMaxMs = maxOf(stats?.headerWaitMaxMs ?: 0, nowMs() - (sent ?: start))
        }
        fun bytes(count: Long) = synchronized(lock) {
            if (count <= 0) return@synchronized
            stats?.apply {
                bytes += count
                if (!firstByte) firstByteMaxMs = maxOf(firstByteMaxMs, nowMs() - start)
                // OkHttp may signal callEnd just before a final buffered read returns.
                if (this@Call.ended) lastEnd = maxOf(lastEnd ?: start, nowMs())
            }
            firstByte = true
        }
        fun end(failed: Boolean, cancelled: Boolean) = synchronized(lock) {
            if (ended) return@synchronized
            ended = true
            stats?.apply {
                this.ended++
                if (failed) this.failed++
                if (cancelled) this.cancelled++
                lastEnd = maxOf(lastEnd ?: start, nowMs())
            }
        }
    }

    fun report(): String = synchronized(lock) {
        buildString {
            appendLine("Download timing v1 (this app session; newest 20 completed jobs + active jobs)")
            appendLine("Phase timings can overlap; do not add them as sequential durations.")
            appendLine("bytes=body bytes read, including retries; rate=bytes / overlapping-request wall span, not sum of request times")
            if (tracks.isEmpty()) appendLine("No downloads measured since this app started.")
            tracks.entries.filter { it.value.end != null || it.value.worker != null || it.value.stats.isNotEmpty() }.takeLast(40).forEach { (id, track) ->
                appendLine("job=$id result=${track.outcome} totalMs=${(track.end ?: nowMs()) - track.start} queueMs=${track.worker?.minus(track.start) ?: -1}")
                appendLine("  phasesMs=${track.phaseMs} profile=${track.profile} audioBitrateBps=${track.bitrate} videoHeight=${track.videoHeight}")
                var total = 0L
                track.stats.forEach { (role, stats) ->
                    total += stats.bytes
                    val span = ((if (stats.calls > stats.ended) nowMs() else stats.lastEnd ?: nowMs()) - (stats.firstStart ?: nowMs())).coerceAtLeast(0)
                    val rate = if (span > 0) String.format(Locale.ROOT, "%.2f", stats.bytes * 8.0 / span / 1000.0) else "n/a"
                    appendLine("  $role bytes=${stats.bytes} spanMs=$span effectiveMbitPerSec=$rate calls=${stats.calls} active=${stats.calls - stats.ended} failed=${stats.failed} cancelled=${stats.cancelled}")
                    appendLine("    firstByteMaxMs=${stats.firstByteMaxMs} setupMaxMs=${stats.setupMaxMs} serverWaitMaxMs=${stats.headerWaitMaxMs} rangedCalls=${stats.ranged} maxRangeBytes=${stats.maxRangeBytes} googleVideoCalls=${stats.googleVideo} nPresentCalls=${stats.withN}")
                }
                appendLine("  totalNetworkBodyBytes=$total logicalAudioBytes=${track.logicalAudio} logicalVideoBytes=${track.logicalVideo} logicalVideoAudioBytes=${track.logicalVideoAudio}")
            }
        }
    }
}
