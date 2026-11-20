package com.xcloud.tclnative.webrtc

internal enum class NetworkQuality(val signals: Int) {
    UNKNOWN(0), POOR(1), MODERATE(2), STABLE(3)
}

/** Confirm a change with three samples so a single delay or loss spike does not flash the indicator. */
internal class NetworkQualityMonitor {
    private var candidate = NetworkQuality.UNKNOWN
    private var consecutive = 0
    var quality = NetworkQuality.UNKNOWN
        private set

    fun observe(metrics: StreamMetrics) {
        val rtt = metrics.rttMs?.takeIf { it.isFinite() && it >= 0 }
        val jitter = metrics.jitterMs?.takeIf { it.isFinite() && it >= 0 }
        val loss = metrics.packetLossPercent?.takeIf { it.isFinite() && it in 0.0..100.0 }
        if (rtt == null && jitter == null && loss == null) { reset(); return }

        val measured = when {
            (rtt ?: 0.0) >= 100 || (jitter ?: 0.0) >= 20 || (loss ?: 0.0) >= 2 -> NetworkQuality.POOR
            (rtt ?: 0.0) >= 70 || (jitter ?: 0.0) >= 10 || (loss ?: 0.0) >= 0.5 -> NetworkQuality.MODERATE
            else -> NetworkQuality.STABLE
        }
        if (measured == quality) {
            consecutive = 0
            return
        }
        // When adjacent levels vary, confirm the minimum change the samples have in common.
        val worsening = measured < quality
        if (consecutive == 0 || (candidate < quality) != worsening ||
            quality == NetworkQuality.UNKNOWN && candidate != measured) {
            candidate = measured
            consecutive = 1
        } else {
            candidate = if (worsening) maxOf(candidate, measured) else minOf(candidate, measured)
            consecutive++
        }
        if (consecutive == 3) {
            quality = candidate
            consecutive = 0
        }
    }

    fun reset() {
        quality = NetworkQuality.UNKNOWN
        consecutive = 0
    }
}
