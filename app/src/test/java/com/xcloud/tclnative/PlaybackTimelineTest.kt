package com.xcloud.tclnative

import com.xcloud.tclnative.diagnostics.PlaybackOrigin
import com.xcloud.tclnative.diagnostics.PlaybackStage
import com.xcloud.tclnative.diagnostics.PlaybackTimeline
import com.xcloud.tclnative.diagnostics.ReportMetric
import com.xcloud.tclnative.diagnostics.SessionEnd
import com.xcloud.tclnative.diagnostics.SessionReportTracker
import com.xcloud.tclnative.webrtc.InputMetrics
import com.xcloud.tclnative.webrtc.StreamMetrics
import org.junit.Assert.*
import org.junit.Test

class PlaybackTimelineTest {
    @Test fun epochsKeepIndependentObservedCodecsAndRejectCallbacksFromObsoleteTransports() {
        var now = 10_000L
        val timeline = PlaybackTimeline(7, PlaybackOrigin.SHORTCUT) { now }
        timeline.mark(0, PlaybackStage.PLAY_REQUEST, 10_120)
        timeline.mark(0, PlaybackStage.OFFER_POSTED, 10_200)
        timeline.mark(0, PlaybackStage.FIRST_TRACK_FRAME, 10_500)
        timeline.mark(0, PlaybackStage.FIRST_EGL_SUBMISSION, 10_520)
        timeline.mark(0, PlaybackStage.FIRST_EGL_SUBMISSION, 10_910)
        timeline.observe(0, "H264 High (64001e)", 1920, 1080)
        now = 11_000
        timeline.beginReconnect(1)
        timeline.mark(0, PlaybackStage.TRANSPORT_READY)
        timeline.observe(0, "AV1", 3840, 2160)
        timeline.mark(1, PlaybackStage.RENDERER_READY, 9000)
        timeline.mark(1, PlaybackStage.TRANSPORT_READY, 11_150)
        timeline.observe(1, "H265/HEVC Main", 1280, 720)
        val saved = timeline.snapshot()

        assertEquals(PlaybackOrigin.SHORTCUT, saved.origin)
        assertEquals(520L, saved.transports[0].stages[PlaybackStage.FIRST_EGL_SUBMISSION])
        assertEquals(500L, saved.transports[0].stages[PlaybackStage.FIRST_TRACK_FRAME])
        assertNull(saved.transports[0].stages[PlaybackStage.TRANSPORT_READY])
        assertEquals(PlaybackTimeline.Codec.H264, saved.transports[0].codec)
        assertEquals(1_000L, saved.transports[1].startedAtMs)
        assertEquals(1_150L, saved.transports[1].stages[PlaybackStage.TRANSPORT_READY])
        assertNull(saved.transports[1].stages[PlaybackStage.RENDERER_READY])
        assertEquals(PlaybackTimeline.Codec.H265, saved.transports[1].codec)
        assertEquals(saved, PlaybackTimeline.read(saved.json()))
    }

    @Test fun repeatedRecoveriesAreBoundedAndOnlyExportEnumeratedMediaFields() {
        var now = 700L
        val timeline = PlaybackTimeline(3, PlaybackOrigin.CATALOG) { now }
        for (epoch in 1..20) {
            now += 1000
            timeline.beginReconnect(epoch)
            timeline.mark(epoch, PlaybackStage.RENDERER_READY)
            timeline.observe(epoch, "opaque:/token", -1, 99_999)
        }
        val snapshot = timeline.snapshot()
        assertEquals(listOf(0, 18, 19, 20), snapshot.transports.map { it.epoch })
        assertFalse(snapshot.json().toString().contains("opaque"))
        assertNull(snapshot.transports.last().width)
        assertNull(snapshot.transports.last().codec)
    }

    @Test fun reportUsesOnlyValidRecentForegroundSamplesAndExportsNoFreeText() {
        val tracker = SessionReportTracker("personal title", "private device", "custom profile", 60)
        tracker.timeline = PlaybackTimeline(1, PlaybackOrigin.CATALOG) { 0 }
        tracker.inputDetails = { "unstructured secret" }
        tracker.begin(0, "private network")
        for (i in 1..125) tracker.add(i * 1000L, StreamMetrics(rttMs = i.toDouble()), InputMetrics())
        tracker.setVisible(false)
        tracker.add(126_000, StreamMetrics(rttMs = 700.0), InputMetrics())
        tracker.setVisible(true)
        tracker.add(127_000, StreamMetrics(rttMs = 700.0), InputMetrics())
        tracker.add(128_000, StreamMetrics(rttMs = Double.NaN), InputMetrics())
        val report = checkNotNull(tracker.finish(129_000, SessionEnd.USER))
        val samples = checkNotNull(report.metrics[ReportMetric.RTT]?.percentiles)
        assertEquals(120, samples.samples)
        assertEquals(65.0, samples.p50, 0.0)
        assertEquals(119.0, samples.p95, 0.0)
        assertEquals(124.0, samples.p99, 0.0)
        assertNull(report.metrics[ReportMetric.DECODE])
        val diagnostic = checkNotNull(report.exportJson())
        assertEquals("elapsedRealtime", diagnostic.getString("clock"))
        assertEquals(120, diagnostic.getJSONObject("lastIntervalSamples").getJSONObject("RTT").getInt("samples"))
        listOf("personal", "private", "custom", "secret").forEach { assertFalse(diagnostic.toString().contains(it)) }
    }
}
