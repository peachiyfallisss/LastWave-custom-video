package com.lastwave.app.data.download

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadConcurrencyLimiterTest {
    @Test fun startsTwoJobsAndKeepsRemainingWorkInOrder() = runTest {
        val limiter = DownloadConcurrencyLimiter()
        val starts = mutableListOf<Int>()
        val jobs = (1..4).map { id -> launch {
            limiter.acquire()
            try { starts += id; awaitCancellation() } finally { limiter.release() }
        } }
        runCurrent()
        assertEquals(listOf(1, 2), starts)
        jobs[0].cancelAndJoin(); runCurrent()
        assertEquals(listOf(1, 2, 3), starts)
        jobs[1].cancelAndJoin(); runCurrent()
        assertEquals(listOf(1, 2, 3, 4), starts)
        jobs.forEach { it.cancelAndJoin() }
    }

    @Test fun raisingLimitStartsWaitingJobsWithoutFinishingCurrentOnes() = runTest {
        val limiter = DownloadConcurrencyLimiter(1)
        var active = 0
        val jobs = (1..4).map { launch {
            limiter.acquire()
            try { active++; awaitCancellation() } finally { active--; limiter.release() }
        } }
        runCurrent(); assertEquals(1, active)
        limiter.setLimit(4); runCurrent(); assertEquals(4, active)
        assertTrue(jobs.all { it.isActive })
        jobs.forEach { it.cancelAndJoin() }
        assertEquals(0, active)
    }

    @Test fun loweringLimitLetsCurrentJobsFinishBeforeAnotherStarts() = runTest {
        val limiter = DownloadConcurrencyLimiter(3)
        val starts = mutableListOf<Int>()
        val jobs = (1..4).map { id -> launch {
            limiter.acquire()
            try { starts += id; awaitCancellation() } finally { limiter.release() }
        } }
        runCurrent(); assertEquals(listOf(1, 2, 3), starts)
        limiter.setLimit(1)
        jobs[0].cancelAndJoin(); runCurrent(); assertEquals(listOf(1, 2, 3), starts)
        jobs[1].cancelAndJoin(); runCurrent(); assertEquals(listOf(1, 2, 3), starts)
        jobs[2].cancelAndJoin(); runCurrent(); assertEquals(listOf(1, 2, 3, 4), starts)
        jobs[3].cancelAndJoin()
    }

    @Test fun cancelledWaitingJobDoesNotBlockNextJobOrConsumeCapacity() = runTest {
        val limiter = DownloadConcurrencyLimiter(1)
        limiter.acquire()
        val cancelled = launch(start = CoroutineStart.UNDISPATCHED) { limiter.acquire(); limiter.release() }
        var nextStarted = false
        val next = launch(start = CoroutineStart.UNDISPATCHED) {
            limiter.acquire(); nextStarted = true; limiter.release()
        }
        cancelled.cancelAndJoin()
        assertFalse(nextStarted)
        limiter.release(); next.join()
        assertTrue(nextStarted)
        limiter.acquire(); limiter.release()
    }

    @Test fun cancellationAfterGrantReturnsReservedPermit() = runTest {
        val limiter = DownloadConcurrencyLimiter(1)
        limiter.acquire()
        var entered = false
        val waiting = launch(start = CoroutineStart.UNDISPATCHED) {
            limiter.acquire()
            try { entered = true } finally { limiter.release() }
        }
        limiter.release() // Grants, but the waiting coroutine has not resumed yet.
        waiting.cancelAndJoin()
        assertFalse(entered)
        limiter.acquire(); limiter.release() // No stranded permit.
    }
}
