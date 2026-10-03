package com.lastwave.app.data.download

import java.util.ArrayDeque
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** FIFO permits with a live limit. Lowering it lets current transfers finish. */
internal class DownloadConcurrencyLimiter(initialLimit: Int = DEFAULT_LIMIT) {
    private val lock = Any()
    private val waiting = ArrayDeque<CompletableDeferred<Unit>>()
    private var limit = initialLimit.coerceIn(1, MAX_LIMIT)
    private var active = 0

    suspend fun acquire() {
        val permit = CompletableDeferred<Unit>()
        synchronized(lock) {
            waiting.addLast(permit)
            drain()
        }
        try {
            permit.await()
            currentCoroutineContext().ensureActive()
        } catch (cancelled: CancellationException) {
            synchronized(lock) {
                // Cancellation can race with a grant, before await resumes.
                if (!waiting.remove(permit)) active--
                drain()
            }
            throw cancelled
        }
    }

    fun release() = synchronized(lock) {
        check(active > 0) { "No download permit to release" }
        active--
        drain()
    }

    fun setLimit(value: Int) = synchronized(lock) {
        limit = value.coerceIn(1, MAX_LIMIT)
        drain()
    }

    private fun drain() {
        while (active < limit && waiting.isNotEmpty()) {
            active++
            waiting.removeFirst().complete(Unit)
        }
    }

    companion object {
        const val DEFAULT_LIMIT = 2
        const val MAX_LIMIT = 4
    }
}
