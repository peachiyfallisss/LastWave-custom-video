package com.lastwave.app.playback

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull

/** Neither lookup is cancelled here: callers can keep the preferred one for a later quality upgrade. */
internal suspend fun <T> firstReadyPlaybackSource(
    youtube: Deferred<T?>,
    preferred: Deferred<T?>,
    preferredWaitAfterFailureMs: Long,
): Pair<Boolean, T?> {
    val first = select<Pair<Boolean, T?>> {
        preferred.onAwait { true to it }
        youtube.onAwait { false to it }
    }
    if (first.second != null) return first
    return if (first.first) false to youtube.await()
    else true to withTimeoutOrNull(preferredWaitAfterFailureMs) { preferred.await() }
}
