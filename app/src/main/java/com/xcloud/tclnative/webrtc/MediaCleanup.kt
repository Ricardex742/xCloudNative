package com.xcloud.tclnative.webrtc

/** Preserve the first failure while still attempting every independent release. */
internal class MediaCleanup(private val clockMs: () -> Long = { System.nanoTime() / 1_000_000 }) {
    data class Step(val name: String, val durationMs: Long, val failed: Boolean)

    private val steps = mutableListOf<Step>()
    private var failure: Exception? = null

    inline fun step(name: String, action: () -> Unit) {
        val start = now()
        var failed = false
        try {
            action()
        } catch (error: Exception) {
            failed = true
            record(error)
        } finally {
            finishStep(name, now() - start, failed)
        }
    }

    fun completedSteps(): List<Step> = steps.toList()
    fun diagnostics() = steps.joinToString(" ") {
        "${it.name}=${it.durationMs}ms" + if (it.failed) "/FAILED" else ""
    }
    fun throwIfFailed() { failure?.let { throw it } }

    private fun now() = clockMs()
    private fun record(error: Exception) {
        val first = failure
        if (first == null) failure = error
        else if (error !== first) first.addSuppressed(error)
    }
    private fun finishStep(name: String, elapsedMs: Long, failed: Boolean) {
        steps.add(Step(name, elapsedMs.coerceAtLeast(0), failed))
    }
}
