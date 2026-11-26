package com.xcloud.tclnative.webrtc

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull

/** WebRTC has no cancellation API for getStats. Keep ownership until its callback arrives. */
internal class RtcStatisticsSampler<T : Any>(
    private val timeoutMs: Long = 2_000,
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000 }
) {
    private val request = Mutex()
    @Volatile private var inFlightAt: Long? = null
    @Volatile private var returned = 0L
    @Volatile private var timedOut = 0L
    @Volatile private var late = 0L
    @Volatile private var rejected = 0L
    @Volatile private var lastRequestDurationMs: Long? = null

    suspend fun sample(submit: ((T) -> Unit) -> Boolean): T? {
        if (!request.tryLock()) return null
        val started = clockMs()
        val callback = CompletableDeferred<T>()
        inFlightAt = started
        try {
            val submitted = try {
                submit { callback.complete(it) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: RuntimeException) {
                false
            }
            if (!submitted) {
                rejected++
                return null
            }
            val result = withTimeoutOrNull(timeoutMs) { callback.await() }
            if (result != null) {
                returned++
                lastRequestDurationMs = (clockMs() - started).coerceAtLeast(0)
                return result
            }
            // A timeout is diagnostic only. Do not issue more JNI requests behind this one.
            timedOut++
            callback.await()
            late++
            return null
        } finally {
            callback.cancel()
            inFlightAt = null
            request.unlock()
        }
    }

    fun diagnostics(): String {
        val pending = inFlightAt?.let { (clockMs() - it).coerceAtLeast(0) }
        return "RTC_STATS/complete=$returned timeout=$timedOut late=$late rejected=$rejected " +
            "pending_ms=${pending ?: "n/d"} last_callback_ms=${lastRequestDurationMs ?: "n/d"}"
    }
}
