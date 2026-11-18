package com.xcloud.tclnative.renderer

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** A posted callback from a destroyed surface cannot make playback ready. */
internal class FirstSurfaceSubmission {
    private val surfaceEpoch = AtomicLong()
    private val pending = AtomicBoolean(true)

    fun surfaceChanged() { surfaceEpoch.incrementAndGet() }

    fun begin(): Long? {
        if (!pending.get()) return null
        val epoch = surfaceEpoch.get()
        return epoch.takeIf { pending.compareAndSet(true, false) }
    }

    fun confirm(epoch: Long, valid: Boolean): Boolean {
        if (valid && epoch == surfaceEpoch.get()) return true
        pending.set(true)
        return false
    }
}
