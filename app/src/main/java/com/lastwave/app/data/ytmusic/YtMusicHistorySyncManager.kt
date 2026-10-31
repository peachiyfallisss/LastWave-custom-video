package com.lastwave.app.data.ytmusic

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import android.os.SystemClock
import android.util.Log
import com.lastwave.app.data.music.InnerTubeMusicApi
import com.lastwave.app.data.music.YouTubeMusicTrack
import com.lastwave.app.playback.MusicPlayer
import com.lastwave.app.playback.MusicPlayerState
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.isActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Reports songs genuinely listened to in LastWave to the connected YouTube
 * Music account's watch history, independent of where the audio came from
 * (Qobuz/FLAC, YouTube, or a downloaded file). Playback itself is never
 * touched: matching and submission run on background coroutines, audio keeps
 * playing its original source at its original quality, and every failure is
 * contained here.
 *
 * Protocol (verified against ytmusicapi master `LibraryMixin.add_history_item`
 * and corroborated by ytmapi-rs `AddHistoryItemQuery`): history is registered
 * by GET-ing the per-play `playbackTracking.videostatsPlaybackUrl.baseUrl`
 * taken from an AUTHENTICATED `player` response for the videoId, with
 * `ver=2`, `c=WEB_REMIX` and a random 16-char `cpn`. Merely fetching a song,
 * resolving its stream, or reading history registers nothing.
 *
 * Listening threshold (history-reporting ONLY — never fails, times out, or
 * auto-skips playback): a listen qualifies after genuinely advancing playback
 * of max(30s, min(50% of duration, 4min)); unknown duration falls back to 90s.
 * This mirrors the Last.fm scrobble convention (half the track, capped at 4
 * minutes) with a 30s floor so short tracks still count. Only position
 * movement while `isPlaying && !isBuffering` counts: pauses, buffering,
 * seeks (large jumps are discarded, never credited) and queueing without
 * playing contribute nothing. One submission per qualifying session; a full
 * restart/repeat opens a new session that may qualify again.
 *
 * Account & privacy rules:
 * - Submissions require the "Sync playback to YouTube Music history" switch
 *   (default ON) AND a currently connected account. The pending job captures
 *   the account snapshot at eligibility and aborts if the live connection no
 *   longer equals it — one account's listens are never written to another.
 * - Disabling the switch or disconnecting/switching accounts cancels
 *   in-flight work immediately.
 * - Qualified listens are stored durably and sent when connectivity returns,
 *   including after process restarts. No credentials are stored in the queue.
 *   YouTube accepts no original timestamp: late listens may show at sync time.
 *   Uncertain submission outcomes are not replayed, to avoid duplicates.
 * - Nothing sensitive is logged: no cookies, auth headers, or tracking URLs —
 *   only videoIds and status codes.
 *
 * Known protocol limitation: YouTube's "pause watch history" account setting
 * has no verified InnerTube read endpoint (ytmusicapi exposes none either),
 * so paused-history status cannot be checked reliably. A successful ping
 * does not prove that an entry is visible in the account's history.
 */
@Singleton
class YtMusicHistorySyncManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val musicPlayer: dagger.Lazy<MusicPlayer>,
    private val innerTube: InnerTubeMusicApi,
    private val ytAuth: YtMusicAuthManager,
    private val preferences: YtMusicPreferences,
    private val applicationScope: CoroutineScope,
) {
    @Volatile private var started = false
    private val flushMutex = Mutex()

    // ── Session accounting (single-threaded: only mutated from the state collector) ──
    private var sessionKey: String? = null
    private var sessionEpoch = 0L
    private var lastPositionMs = 0L
    private var listenedMs = 0L
    private var submittedSessionId: String? = null

    private var historyEnabled = true
    private var activeAccount = YtConnection.DISCONNECTED
    private var lastSampleMs = 0L
    private var wasPlaying = false
    private var lastSourceSwitchSerial = 0L
    private var sourceSwitchSettling = false

    /** In-flight submissions, cancelled on disable / disconnect / switch. */
    private val activeSubmissions = ConcurrentHashMap.newKeySet<Job>()

    @Synchronized
    fun start() {
        if (started) return
        started = true
        applicationScope.launch(Dispatchers.IO) {
            if (preferences.pendingHistory.first().isNotEmpty()) YtHistorySyncWorker.schedule(context)
        }
        applicationScope.launch(Dispatchers.Main.immediate) {
            // Hydration is not an account switch; do not cancel a worker started by Android.
            activeAccount = ytAuth.awaitLoadedConnection()
            historyEnabled = preferences.historySyncEnabled.first()
            combine(musicPlayer.get().state, ytAuth.connection, preferences.historySyncEnabled) { state, account, enabled ->
                Triple(state, account, enabled)
            }.collect { (state, account, enabled) ->
                if (account != activeAccount || enabled != historyEnabled) {
                    val selectionChanged = QueuedHistoryListen.scope(account) != QueuedHistoryListen.scope(activeAccount)
                    if (selectionChanged || enabled != historyEnabled) {
                        cancelActive("account selection or preference change")
                        sessionKey = null
                    }
                    activeAccount = account
                    historyEnabled = enabled
                }
                onPlayerState(com.lastwave.app.playback.concertListeningState(state))
            }
        }
    }

    private fun onPlayerState(s: MusicPlayerState) {
        val now = SystemClock.elapsedRealtime()
        val elapsed = (now - lastSampleMs).coerceAtLeast(0L)
        lastSampleMs = now
        val advancing = s.isPlaying && !s.isBuffering
        val previouslyPlaying = wasPlaying
        wasPlaying = advancing
        val track = s.current
        val key = track?.let { "${s.currentIndex}|${s.listeningSessionKey}|${it.title}|${it.artist}|${it.album}|${it.videoId}|${it.playbackUrl}" }
        val switched = s.sourceSwitchSerial != lastSourceSwitchSerial
        if (switched) {
            lastSourceSwitchSerial = s.sourceSwitchSerial
            sourceSwitchSettling = true
        }
        val restarted = !sourceSwitchSettling && s.positionMs < RESTART_POSITION_MS && lastPositionMs > RESTART_FLOOR_MS
        val delta = s.positionMs - lastPositionMs
        // Source replacement may briefly report 0 or clamp to a shorter video. It is still the same listen.
        if (!switched && sourceSwitchSettling && advancing && delta > 0L) sourceSwitchSettling = false
        lastPositionMs = s.positionMs

        // Check restarts before the submitted-session guard so repeat-one can qualify again.
        if (key == null || key != sessionKey || restarted) {
            sessionKey = key
            sessionEpoch++
            listenedMs = 0L
            submittedSessionId = null
            sourceSwitchSettling = false
            return
        }
        if (!historyEnabled || !activeAccount.isConnected) return
        val sessionId = "$sessionEpoch|$key"
        if (submittedSessionId == sessionId) return

        // Credit real elapsed listening, not a seek's position jump or time spent paused.
        if (advancing && previouslyPlaying && delta in 1..MAX_POSITION_DELTA_MS &&
            delta <= elapsed + POSITION_JITTER_MS
        ) {
            listenedMs += minOf(delta, elapsed)
        }
        if (track == null || !advancing || listenedMs < requiredListenMs(s.durationMs)) return
        submittedSessionId = sessionId
        Log.d(TAG, "Listen qualified after ${listenedMs / 1000}s; preparing history submission")
        launchSubmit(
            QueuedHistoryListen(
                title = track.title,
                artist = track.artist,
                album = track.album,
                videoId = track.videoId ?: track.playbackUrl?.let {
                    context.getSharedPreferences("lastwave_download_sources", Context.MODE_PRIVATE).getString(it, null)
                },
                // An attached video may include an intro/outro; catalog matching uses the original song duration.
                durationMs = track.durationMs?.takeIf { it > 0L } ?: s.durationMs,
                accountScope = QueuedHistoryListen.scope(activeAccount),
            ),
        )
    }

    private fun launchSubmit(pending: QueuedHistoryListen) {
        val job = applicationScope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            try {
                if (preferences.enqueueHistory(pending)) YtHistorySyncWorker.schedule(context)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (e: Exception) {
                Log.w(TAG, "History sync failed for session ${pending.id}: ${e.javaClass.simpleName}")
            }
        }
        activeSubmissions.add(job)
        job.invokeOnCompletion { activeSubmissions.remove(job) }
        job.start()
    }

    private fun cancelActive(reason: String) {
        if (activeSubmissions.isEmpty()) return
        Log.d(TAG, "Cancelling ${activeSubmissions.size} pending history submission(s): $reason")
        activeSubmissions.forEach { it.cancel() }
    }

    /** Returns false for deferred work; WorkManager retries when the network is available. */
    suspend fun flushPendingHistory(): Boolean {
        val job = currentCoroutineContext()[Job]
        if (job != null) activeSubmissions.add(job)
        return try { drainPendingHistory() } finally { if (job != null) activeSubmissions.remove(job) }
    }

    private suspend fun drainPendingHistory(): Boolean = flushMutex.withLock {
        val network = context.getSystemService(ConnectivityManager::class.java)
        val capabilities = network?.getNetworkCapabilities(network.activeNetwork)
        if (capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) != true) {
            return@withLock preferences.pendingHistory.first().isEmpty()
        }
        for (pending in preferences.pendingHistory.first().sortedBy { it.listenedAtMillis }) {
            val account = ytAuth.awaitLoadedConnection()
            if (!preferences.historySyncEnabled.first() || !account.isConnected ||
                QueuedHistoryListen.scope(account) != pending.accountScope
            ) {
                preferences.claimHistory(pending) // Discard an obsolete account's item; never submit it.
                continue
            }
            try {
                val videoId = pending.videoId?.takeIf { VIDEO_ID.matches(it) } ?: resolveStrictVideoId(pending)
                if (videoId == null || pending.title.isBlank()) {
                    preferences.claimHistory(pending)
                    continue
                }
                // Resolve fresh authenticated tracking data only once online.
                val tracking = innerTube.fetchHistoryTrackingUrl(videoId, account)
                    ?: return@withLock false
                // Final atomic privacy gate plus durable claim, before any network write.
                if (!preferences.claimHistory(pending)) continue
                val code = innerTube.submitHistoryPlayback(tracking, pending.id.replace("-", "").take(CPN_LENGTH), account)
                if (code == 408 || code == 429 || code in 500..599) {
                    preferences.enqueueHistory(pending) // Explicit failure: safe to retry the same listen.
                    return@withLock false
                }
                Log.d(TAG, "History submission HTTP $code for $videoId; remote visibility not verified")
            } catch (cancelled: CancellationException) {
                // InnerTube also uses cancellation to reject a stale credential snapshot.
                // Keep remaining listens retryable; real worker cancellation still propagates.
                if (!currentCoroutineContext().isActive) throw cancelled
                return@withLock false
            } catch (http: InnerTubeMusicApi.InnerTubeHttpException) {
                if (http.responseCode == 401 || http.responseCode == 403) {
                    preferences.claimHistory(pending)
                } else return@withLock false
            } catch (_: IOException) {
                // Before claim: still queued. After claim: outcome uncertain, do not replay.
                return@withLock preferences.pendingHistory.first().isEmpty()
            }
        }
        preferences.pendingHistory.first().isEmpty()
    }

    /**
     * Strict YT match for tracks with no trusted videoId: reuses
     * [InnerTubeMusicApi.findBestMatchOrNull] (title ≥72 / artist ≥50
     * similarity, unexpected remix/live-style variants penalised), then
     * additionally requires duration agreement and exact version-marker
     * parity so originals, remixes, live recordings, covers and sped-up
     * versions are never confused. Returns null when ambiguous.
     */
    private suspend fun resolveStrictVideoId(pending: QueuedHistoryListen): String? {
        if (pending.artist.isBlank()) return null
        val candidate: YouTubeMusicTrack = try {
            innerTube.findBestMatchOrNull(pending.title, pending.artist, prefetchStreams = false)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: IOException) {
            throw failure
        } catch (failure: Exception) {
            throw IOException("History matching unavailable", failure)
        } ?: return null
        if (candidate.videoId.isBlank()) return null
        if (versionMarkers(candidate.title) != versionMarkers(pending.title)) return null
        val candidateMs = candidate.durationSeconds?.times(1000L)
        if (pending.durationMs > 0 && candidateMs != null && candidateMs > 0) {
            val tolerance = maxOf(DURATION_TOLERANCE_MS, pending.durationMs * DURATION_TOLERANCE_RATIO_NUM / 100)
            if (abs(candidateMs - pending.durationMs) > tolerance) return null
        }
        return candidate.videoId
    }

    private fun versionMarkers(title: String): Set<String> {
        val lower = title.lowercase()
        val tokens = lower.split(VERSION_SPLIT).filter { it.isNotBlank() }.toSet()
        return buildSet {
            for (marker in VERSION_MARKERS) {
                if (marker in tokens) add(if (marker == "remastered") "remaster" else marker)
            }
            if ("nightcore" in lower) add("nightcore")
            if ("sped" in tokens || "sped-up" in lower || "sped up" in lower || "speed up" in lower) add("sped")
        }
    }

    private companion object {
        const val TAG = "YtHistorySync"

        // Listening threshold: max(30s, min(50% of duration, 4min)); 90s when
        // duration is unknown. History-reporting only.
        const val MIN_LISTEN_THRESHOLD_MS = 30_000L
        const val MAX_LISTEN_THRESHOLD_MS = 240_000L
        const val FALLBACK_LISTEN_THRESHOLD_MS = 90_000L

        /** Position jumps larger than this are seeks, never listening time. */
        const val MAX_POSITION_DELTA_MS = 2_500L
        const val POSITION_JITTER_MS = 250L
        val VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")

        /** Position reset below this (from far in) = restart/repeat → new session. */
        const val RESTART_POSITION_MS = 3_000L
        const val RESTART_FLOOR_MS = 10_000L

        /** Track-length agreement for strict matches: 7s or 5%, whichever larger. */
        const val DURATION_TOLERANCE_MS = 7_000L
        const val DURATION_TOLERANCE_RATIO_NUM = 5L

        const val CPN_LENGTH = 16
        val VERSION_SPLIT = Regex("[^a-z0-9]+")
        val VERSION_MARKERS = setOf(
            "live", "remix", "karaoke", "cover", "instrumental", "slowed",
            "acoustic", "demo", "edit", "remaster", "remastered", "mono",
            "stereo", "unplugged", "stripped",
        )

        fun requiredListenMs(durationMs: Long): Long {
            val need = if (durationMs > 0) {
                minOf(durationMs / 2, MAX_LISTEN_THRESHOLD_MS)
            } else {
                FALLBACK_LISTEN_THRESHOLD_MS
            }
            return maxOf(need, MIN_LISTEN_THRESHOLD_MS)
        }
    }
}
