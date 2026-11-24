package com.xcloud.tclnative.renderer

import kotlin.math.roundToInt

/** A phone or tablet window above QHD can reserve large Surface buffers before the first frame. */
internal object SurfaceMemoryBudget {
    fun applyLimit(width: Int, height: Int, tv: Boolean): Boolean =
        !tv && width.toLong() * height > 2560L * 1440L

    fun cap(target: VideoViewport): VideoViewport {
        val scale = minOf(1.0, 1920.0 / maxOf(target.width, target.height), 1080.0 / minOf(target.width, target.height))
        if (scale == 1.0) return target
        return VideoViewport((target.width * scale).roundToInt().coerceAtLeast(1),
            (target.height * scale).roundToInt().coerceAtLeast(1))
    }
}
