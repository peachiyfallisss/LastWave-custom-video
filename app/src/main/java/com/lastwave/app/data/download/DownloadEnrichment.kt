package com.lastwave.app.data.download

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.withTimeoutOrNull

/** Optional artwork/lyrics may use spare transfer time, never hold the queue indefinitely. */
suspend fun <T> awaitOptionalDownloadResult(lookup: Deferred<T>?, budgetMs: Long): T? {
    if (lookup == null) return null
    return try { withTimeoutOrNull(budgetMs) { lookup.await() } }
    finally { if (!lookup.isCompleted) lookup.cancel() }
}
