package com.xcloud.tclnative.webrtc

/** Require sustained changes to avoid flashing an alert for one RTP sample. */
internal class NetworkWarning {
    private var consecutive = 0
    var active = false
        private set

    fun observe(metrics: StreamMetrics) {
        val rtt = metrics.rttMs?.takeIf { it.isFinite() && it >= 0 }
        val jitter = metrics.jitterMs?.takeIf { it.isFinite() && it >= 0 }
        val loss = metrics.packetLossPercent?.takeIf { it.isFinite() && it in 0.0..100.0 }
        if (rtt == null && jitter == null && loss == null) { reset(); return }
        val degraded = (rtt ?: 0.0) >= 100 || (jitter ?: 0.0) >= 20 || (loss ?: 0.0) >= 2
        if (degraded == active) {
            consecutive = 0
        } else if (++consecutive >= 3) {
            active = degraded
            consecutive = 0
        }
    }

    fun reset() { consecutive = 0; active = false }
}
