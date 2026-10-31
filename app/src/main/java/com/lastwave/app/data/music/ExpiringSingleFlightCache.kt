package com.lastwave.app.data.music

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Small, process-local cache. Same-key loads coalesce; unrelated recordings never block each other. */
internal class ExpiringSingleFlightCache<K, V : Any>(
    private val maxEntries: Int,
    private val ttlMs: Long,
    private val now: () -> Long = System::currentTimeMillis,
) {
    init { require(maxEntries > 0 && ttlMs > 0) }
    private data class Entry<V>(val value: V, val expires: Long)
    private class Flight { val mutex = Mutex(); var users = 0; var invalidated = false }
    private val lock = Any()
    private val entries = LinkedHashMap<K, Entry<V>>(16, 0.75f, true)
    private val flights = mutableMapOf<K, Flight>()

    fun invalidate(key: K) = synchronized(lock) {
        entries.remove(key)
        flights.remove(key)?.invalidated = true
        Unit
    }

    suspend fun get(key: K, expiresAt: (V) -> Long? = { null }, load: suspend () -> V): V {
        currentCoroutineContext().ensureActive()
        fun cached(): V? = synchronized(lock) {
            entries[key]?.let { entry ->
                if (entry.expires > now()) entry.value else { entries.remove(key); null }
            }
        }
        cached()?.let { return it }
        val flight = synchronized(lock) { flights.getOrPut(key) { Flight() }.also { it.users++ } }
        try {
            return flight.mutex.withLock {
                cached()?.let { return@withLock it }
                val value = load() // Exceptions/cancellation are never cached.
                currentCoroutineContext().ensureActive()
                val expires = minOf(now() + ttlMs, expiresAt(value) ?: Long.MAX_VALUE)
                synchronized(lock) {
                    // An invalidated in-flight request cannot repopulate stale signed URLs.
                    if (!flight.invalidated && expires > now()) {
                        entries[key] = Entry(value, expires)
                        while (entries.size > maxEntries) entries.remove(entries.keys.first())
                    }
                }
                value
            }
        } finally {
            synchronized(lock) {
                flight.users--
                if (flight.users == 0 && flights[key] === flight) flights.remove(key)
            }
        }
    }
}
