package com.xcloud.tclnative.renderer

/** Briefly skip GPU signature probes after confirming distinct frames at full source cadence. */
internal class FrameSignatureProbeGate {
    private var previous = 0L
    private var uniqueRun = 0
    private var remainingSkip = 0

    fun shouldProbe(timestampNs: Long): Boolean {
        val interval = timestampNs - previous
        previous = timestampNs
        if (interval !in ContentFrameCadence.FAST_INTERVAL_NS) {
            remainingSkip = 0
            uniqueRun = 0
        }
        if (remainingSkip == 0) return true
        remainingSkip--
        return false
    }

    /** The caller must discard GPU and content history before jumping over any input. */
    fun observe(uniqueFastFrame: Boolean): Boolean {
        uniqueRun = if (uniqueFastFrame) uniqueRun + 1 else 0
        if (uniqueRun < CONFIRMING_FRAMES) return false
        uniqueRun = 0
        remainingSkip = SKIP_FRAMES
        return true
    }

    fun reset() {
        previous = 0
        uniqueRun = 0
        remainingSkip = 0
    }

    companion object {
        private const val CONFIRMING_FRAMES = 12
        private const val SKIP_FRAMES = 48
    }
}
