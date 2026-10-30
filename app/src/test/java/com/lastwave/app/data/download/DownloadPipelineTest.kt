package com.lastwave.app.data.download

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadPipelineTest {
    @Test fun controlledPlaylistBenchmarkRemovesTransferGapsWithoutRaisingLimit() = runTest {
        val old = DownloadConcurrencyLimiter(2)
        val baselineStart = testScheduler.currentTime
        (1..8).map { launch {
            old.acquire()
            try { delay(100); delay(600); delay(1_000); delay(300) }
            finally { old.release() }
        } }.joinAll()
        val baselineMs = testScheduler.currentTime - baselineStart
        val events = mutableListOf<DownloadPipeline.Event>()
        val pipeline = DownloadPipeline(2, { testScheduler.currentTime }, events::add)
        val start = testScheduler.currentTime
        (1..8).map { launch { pipeline.run { lease ->
            delay(100) // Resolve the same recording, not a cheaper substitute.
            lease.beginTransfer()
            coroutineScope {
                val video = async { delay(1_000) }
                delay(600)
                video.await()
            }
            lease.beginFinishing()
            delay(300)
        } } }.joinAll()
        val pipelineMs = testScheduler.currentTime - start
        assertEquals(8_000L, baselineMs)
        assertEquals(4_400L, pipelineMs)
        assertEquals(2, events.maxOf { it.activeTransfers })
        assertTrue(events.mapNotNull { it.idleGapMs }.all { it == 0L })
        assertEquals(pipelineMs, events.last().batchElapsedMs)
        assertTrue(events.filter { it.stage == DownloadPipeline.Stage.FINISHED }
            .all { it.stageTimesMs.values.sum() == it.elapsedMs })
        println("Controlled 8-song benchmark (virtual clock, same 2-song limit): old=${baselineMs}ms pipeline=${pipelineMs}ms; inter-transfer idle gaps=0ms. Not a phone/network speed measurement.")
    }

    @Test fun oneTransferSettingStillAllowsPreparationAndFinishingOverlap() = runTest {
        val events = mutableListOf<DownloadPipeline.Event>()
        val pipeline = DownloadPipeline(1, { testScheduler.currentTime }, events::add)
        val finish = CompletableDeferred<Unit>()
        val transfers = mutableListOf<Int>()
        val jobs = (1..4).map { id -> launch { pipeline.run { lease ->
            delay(10)
            lease.beginTransfer(); transfers += id; delay(100)
            lease.beginFinishing(); finish.await()
        } } }
        advanceTimeBy(250); runCurrent()
        assertEquals(listOf(1, 2, 3), transfers)
        assertEquals(1, events.maxOf { it.activeTransfers })
        assertEquals(2, events.count { it.stage == DownloadPipeline.Stage.FINISHING })
        finish.complete(Unit)
        jobs.joinAll()
        assertEquals(listOf(1, 2, 3, 4), transfers)
    }

    @Test fun stuckFinishersApplyBackpressureInsteadOfAccumulatingPlaylistFiles() = runTest {
        val pipeline = DownloadPipeline(1)
        val release = CompletableDeferred<Unit>()
        var prepared = 0
        val jobs = (1..100).map { launch { pipeline.run { lease ->
            prepared++
            lease.beginTransfer()
            lease.beginFinishing()
            release.await()
        } } }
        runCurrent()
        assertEquals(5, prepared) // 2 finishers + (1 transfer + 2 lookahead).
        release.complete(Unit)
        jobs.joinAll()
        assertEquals(100, prepared)
    }

    @Test fun cancellationInEveryStageReleasesAllCapacity() = runTest {
        val pipeline = DownloadPipeline(1)
        val finishing = CompletableDeferred<Unit>()
        val jobs = (1..10).map { launch { pipeline.run { lease ->
            lease.beginTransfer()
            delay(10)
            lease.beginFinishing()
            finishing.await()
        } } }
        advanceTimeBy(50); runCurrent()
        // Finishers, a waiting finisher, queued transfers, and admission waiters.
        jobs.forEach { it.cancelAndJoin() }
        (1..10).map { async { pipeline.run { lease ->
            lease.beginTransfer(); lease.beginFinishing()
        } } }.awaitAll()
    }

    @Test fun cancellationDuringPreparationDoesNotStrandLookahead() = runTest {
        val pipeline = DownloadPipeline(1)
        val jobs = (1..8).map { launch { pipeline.run { awaitCancellation() } } }
        runCurrent()
        jobs.forEach { it.cancelAndJoin() }
        pipeline.run { it.beginTransfer(); it.beginFinishing() }
    }

    @Test fun failuresInEachStageDoNotBlockFollowingJobs() = runTest {
        val pipeline = DownloadPipeline(1)
        repeat(3) { stage ->
            val failure = runCatching { pipeline.run { lease ->
                if (stage >= 1) lease.beginTransfer()
                if (stage >= 2) lease.beginFinishing()
                error("test failure")
            } }
            assertTrue(failure.isFailure)
            pipeline.run { it.beginTransfer(); it.beginFinishing() }
        }
    }

    @Test fun liveLimitChangesOnlyAffectNetworkAdmissionAndDoNotCancelTransfers() = runTest {
        val pipeline = DownloadPipeline(1)
        val starts = mutableListOf<Int>()
        val jobs = (1..5).map { id -> launch { pipeline.run { lease ->
            lease.beginTransfer(); starts += id; awaitCancellation()
        } } }
        runCurrent(); assertEquals(listOf(1), starts)
        pipeline.setLimit(3)
        runCurrent(); assertEquals(listOf(1, 2, 3), starts)
        pipeline.setLimit(1)
        jobs[0].cancelAndJoin(); jobs[1].cancelAndJoin()
        runCurrent(); assertEquals(listOf(1, 2, 3), starts)
        jobs[2].cancelAndJoin()
        runCurrent(); assertEquals(listOf(1, 2, 3, 4), starts)
        jobs.forEach { it.cancelAndJoin() }
        pipeline.run { it.beginTransfer(); it.beginFinishing() }
    }
}
