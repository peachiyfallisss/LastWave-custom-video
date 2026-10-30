package com.lastwave.app.data.download

/** Bounded lookahead, network admission, and disk finishing are independent stages.
 * A slow tagger cannot hold a network permit. Backpressure still bounds temporary
 * files: at most transferLimit + LOOKAHEAD pre-finish jobs and FINISHERS finishers.
 * Leases are owned by one coroutine; child transfers must be joined before finishing.
 */
internal class DownloadPipeline(
    initialLimit: Int = DownloadConcurrencyLimiter.DEFAULT_LIMIT,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val report: (Event) -> Unit = {},
) {
    enum class Stage { QUEUED, PREPARING, WAITING_TRANSFER, TRANSFERRING, WAITING_FINISH, FINISHING, FINISHED }
    data class Event(
        val job: Long,
        val stage: Stage,
        val elapsedMs: Long,
        val activeTransfers: Int,
        val idleGapMs: Long? = null,
        val stageTimesMs: Map<Stage, Long> = emptyMap(),
        val batchElapsedMs: Long? = null,
    )

    private val admission = DownloadConcurrencyLimiter(initialLimit + LOOKAHEAD, DownloadConcurrencyLimiter.MAX_LIMIT + LOOKAHEAD)
    private val preparation = DownloadConcurrencyLimiter(LOOKAHEAD)
    private val transfers = DownloadConcurrencyLimiter(initialLimit)
    private val finishing = DownloadConcurrencyLimiter(FINISHERS)
    private val lock = Any()
    private var nextId = 0L
    private var pending = 0
    private var activeTransfers = 0
    private var batchStart = 0L
    private var lastTransferEnd: Long? = null

    fun setLimit(value: Int) {
        val limit = value.coerceIn(1, DownloadConcurrencyLimiter.MAX_LIMIT)
        transfers.setLimit(limit)
        admission.setLimit(limit + LOOKAHEAD)
    }

    suspend fun <T> run(block: suspend (Lease) -> T): T {
        val lease = synchronized(lock) {
            if (pending++ == 0) { batchStart = nowMs(); lastTransferEnd = null }
            Lease(++nextId, nowMs())
        }
        try {
            lease.enter(Stage.QUEUED)
            admission.acquire()
            lease.admitted = true
            preparation.acquire()
            lease.preparing = true
            lease.enter(Stage.PREPARING)
            return block(lease)
        } finally { lease.close() }
    }

    inner class Lease internal constructor(private val id: Long, private val started: Long) {
        internal var admitted = false
        internal var preparing = false
        private var transferring = false
        private var finishingHeld = false
        private var stage = Stage.QUEUED
        private var stageStart = started
        private val times = mutableMapOf<Stage, Long>()

        suspend fun beginTransfer() {
            if (transferring) return // Same recording's provider retry keeps its slot.
            check(!finishingHeld)
            if (preparing) { preparing = false; preparation.release() }
            enter(Stage.WAITING_TRANSFER)
            transfers.acquire()
            transferring = true
            enter(Stage.TRANSFERRING)
        }

        suspend fun beginFinishing() {
            if (finishingHeld) return
            if (preparing) { preparing = false; preparation.release() }
            // Release the network first, even if both disk finishers are busy.
            enter(Stage.WAITING_FINISH)
            if (transferring) { transferring = false; transfers.release() }
            finishing.acquire()
            finishingHeld = true
            if (admitted) { admitted = false; admission.release() }
            enter(Stage.FINISHING)
        }

        internal fun enter(next: Stage) = synchronized(lock) {
            val now = nowMs()
            times[stage] = times.getOrDefault(stage, 0L) + (now - stageStart)
            var gap: Long? = null
            if (next == Stage.TRANSFERRING) {
                if (activeTransfers == 0) gap = lastTransferEnd?.let { now - it }
                activeTransfers++
            } else if (stage == Stage.TRANSFERRING) {
                activeTransfers--
                if (activeTransfers == 0) lastTransferEnd = now
            }
            stage = next
            stageStart = now
            val batch = if (next == Stage.FINISHED && --pending == 0) now - batchStart else null
            // Diagnostic identifiers are counters, never titles, URLs, headers, or tokens.
            runCatching { report(Event(id, next, now - started, activeTransfers, gap,
                if (next == Stage.FINISHED) times.toMap() else emptyMap(), batch)) }
            Unit
        }

        internal fun close() {
            enter(Stage.FINISHED)
            if (preparing) { preparing = false; preparation.release() }
            if (transferring) { transferring = false; transfers.release() }
            if (finishingHeld) { finishingHeld = false; finishing.release() }
            if (admitted) { admitted = false; admission.release() }
        }
    }

    companion object {
        private const val LOOKAHEAD = 2
        private const val FINISHERS = 2
    }
}
