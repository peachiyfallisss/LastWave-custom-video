package com.lastwave.app.data.download

import org.junit.Assert.*
import org.junit.Test

class DownloadTraceTest {
    @Test fun separatesResolutionFirstByteTransferAndFinishingWithoutDoubleCountingParallelTime() {
        var clock = 0L
        val trace = DownloadTrace { clock }
        val id = trace.begin()
        clock = 100; trace.worker(id)
        trace.startPhase(id, DownloadTrace.Phase.AUDIO_RESOLVE)
        clock = 2100; trace.endPhase(id, DownloadTrace.Phase.AUDIO_RESOLVE)
        trace.source(id, "NEWPIPE", 128000); trace.video(id, 1080)
        val first = trace.call(id, DownloadTrace.Role.AUDIO, 500000, true, true)
        val second = trace.call(id, DownloadTrace.Role.AUDIO, 500000, true, true)
        clock = 2200; first.sent(); second.sent()
        clock = 2300; first.headers(); second.headers()
        clock = 2400; first.bytes(500000); second.bytes(500000)
        clock = 3100; first.end(false, false); second.end(false, false)
        trace.startPhase(id, DownloadTrace.Phase.FINISHING)
        clock = 4100; trace.finish(id, "success")
        val report = trace.report()
        assertTrue(report.contains("totalMs=4100 queueMs=100"))
        assertTrue(report.contains("AUDIO_RESOLVE=2000"))
        assertTrue(report.contains("FINISHING=1000"))
        assertTrue(report.contains("AUDIO bytes=1000000 spanMs=1000 effectiveMbitPerSec=8.00"))
        assertTrue(report.contains("firstByteMaxMs=300 setupMaxMs=100 serverWaitMaxMs=100"))
        assertTrue(report.contains("rangedCalls=2 maxRangeBytes=500000"))
        assertTrue(report.contains("profile=NEWPIPE audioBitrateBps=128000 videoHeight=1080"))
    }
    @Test fun reportsAllThreeMediaStreamsAndCountsRetriedBytes() {
        var clock = 0L
        val trace = DownloadTrace { clock }
        val id = trace.begin(); trace.worker(id)
        val failed = trace.call(id, DownloadTrace.Role.AUDIO, null, false, false)
        clock = 10; failed.bytes(100); failed.end(true, false)
        val retry = trace.call(id, DownloadTrace.Role.AUDIO, null, false, false)
        val video = trace.call(id, DownloadTrace.Role.VIDEO, null, true, true)
        val soundtrack = trace.call(id, DownloadTrace.Role.VIDEO_AUDIO, null, true, true)
        clock = 100
        retry.bytes(200); video.bytes(300); soundtrack.bytes(400)
        retry.end(false, false); video.end(false, false); soundtrack.end(false, false)
        trace.logicalMedia(id, 200, 300, 400)
        trace.finish(id, "success")
        val report = trace.report()
        assertTrue(report.contains("AUDIO bytes=300"))
        assertTrue(report.contains("VIDEO bytes=300"))
        assertTrue(report.contains("VIDEO_AUDIO bytes=400"))
        assertTrue(report.contains("failed=1"))
        assertTrue(report.contains("totalNetworkBodyBytes=1000 logicalAudioBytes=200 logicalVideoBytes=300 logicalVideoAudioBytes=400"))
    }
    @Test fun bufferedLastReadAfterCallEndIsIncludedAndCancellationIsCategorical() {
        var clock = 0L
        val trace = DownloadTrace { clock }
        val id = trace.begin(); trace.worker(id)
        val call = trace.call(id, DownloadTrace.Role.VIDEO, null, false, false)
        clock = 5; call.end(true, true)
        clock = 6; call.bytes(123)
        clock = 8; trace.finish(id, "cancelled")
        val report = trace.report()
        assertTrue(report.contains("VIDEO bytes=123 spanMs=6"))
        assertTrue(report.contains("active=0 failed=1 cancelled=1"))
        assertFalse(report.contains("Infinity"))
    }
    @Test fun transferAdmissionWaitIsNotMistakenForSlowExtraction() {
        var clock = 0L
        val trace = DownloadTrace { clock }
        val id = trace.begin(); trace.worker(id)
        trace.endPhase(id, DownloadTrace.Phase.PREPARE_METADATA)
        trace.startPhase(id, DownloadTrace.Phase.AUDIO_RESOLVE)
        clock = 500; trace.endPhase(id, DownloadTrace.Phase.AUDIO_RESOLVE)
        trace.startPhase(id, DownloadTrace.Phase.TRANSFER_SLOT_WAIT)
        clock = 9500; trace.endPhase(id, DownloadTrace.Phase.TRANSFER_SLOT_WAIT)
        trace.finish(id, "success")
        assertTrue(trace.report().contains("AUDIO_RESOLVE=500"))
        assertTrue(trace.report().contains("TRANSFER_SLOT_WAIT=9000"))
    }

    @Test fun openEndedResumeIsRecordedAsRangedWithoutInventingItsSize() {
        val trace = DownloadTrace { 10 }
        val id = trace.begin(); trace.worker(id)
        trace.call(id, DownloadTrace.Role.AUDIO, null, true, false, hasRange = true).end(false, false)
        trace.finish(id, "success")
        assertTrue(trace.report().contains("rangedCalls=1 maxRangeBytes=0"))
    }

    @Test fun historyIsBoundedAndQueuedJobsDoNotHideCompletedMeasurements() {
        val trace = DownloadTrace { 0 }
        repeat(25) { val id = trace.begin(); trace.worker(id); trace.finish(id, "success") }
        repeat(100) { trace.begin() }
        val report = trace.report()
        assertFalse(report.contains("job=1 "))
        assertTrue(report.contains("job=6 "))
        assertTrue(report.contains("job=25 "))
        assertEquals(20, Regex("job=").findAll(report).count())
    }
}
