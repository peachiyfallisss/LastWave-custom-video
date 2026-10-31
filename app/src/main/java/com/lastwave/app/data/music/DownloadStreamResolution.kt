package com.lastwave.app.data.music

import kotlinx.coroutines.CancellationException

/** Downloads need an exact recording, not the playback client's entire waterfall first. */
internal suspend fun <T : Any> resolveDownloadSource(
    cached: () -> T?,
    extract: suspend () -> T,
    fallback: suspend () -> T,
): T {
    cached()?.let { return it }
    return try { extract() }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { fallback() }
}
