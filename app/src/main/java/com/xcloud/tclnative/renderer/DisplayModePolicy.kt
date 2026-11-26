package com.xcloud.tclnative.renderer

data class OutputMode(val id: Int, val width: Int, val height: Int, val hz: Float)

object DisplayModePolicy {
    /** Treat incomplete or conflicting firmware lists as a request to leave the system mode alone. */
    internal fun playbackMode(current: OutputMode, supported: List<OutputMode>, fps: Int, tv4k: Boolean): Int? {
        val valid = supported.filter { it.id > 0 && it.width > 0 && it.height > 0 && it.hz.isFinite() && it.hz > 0f }
        if (current !in valid || valid.distinct().groupBy(OutputMode::id).any { (_, modes) -> modes.size != 1 }) return null
        return (if (tv4k) preferred4k60(current, valid) else null) ?: preferredCadence(current, valid, fps)
    }

    /** Match the stream cadence without changing the phone's physical resolution. */
    fun preferredCadence(current: OutputMode, supported: List<OutputMode>, fps: Int): Int? {
        if (fps <= 0) return null
        fun compatible(mode: OutputMode): Boolean {
            if (!mode.hz.isFinite() || mode.hz <= 0) return false
            val multiple = kotlin.math.round(mode.hz / fps)
            return multiple >= 1 && kotlin.math.abs(mode.hz - multiple * fps) <= multiple * 0.15f
        }
        if (compatible(current)) return current.id
        return supported.filter {
            it.width == current.width && it.height == current.height && compatible(it)
        }.minByOrNull { it.hz }?.id
    }

    /** Keep an already-fast 4K mode; request supported native 4K60 instead of 4K24/30 for TV gaming. */
    fun preferred4k60(current: OutputMode, supported: List<OutputMode>): Int? {
        if (current.width == 3840 && current.height == 2160 && current.hz >= 59) return null
        return supported.firstOrNull { it.width == 3840 && it.height == 2160 && it.hz in 59f..61f }?.id
    }
}
