package com.xcloud.tclnative.ui

import androidx.annotation.MainThread
import com.xcloud.tclnative.diagnostics.PlaybackOrigin
import com.xcloud.tclnative.diagnostics.PlaybackTimeline
import com.xcloud.tclnative.webrtc.MediaCleanup
import com.xcloud.tclnative.webrtc.TransmissionRecoveryState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.selects.select
import java.io.IOException

/** Queues playback behind the previous owner's cleanup. The queue is confined to the UI thread. */
internal class PlaybackCoordinator<Peer : Any, Allocation : Any>(
    private val scope: CoroutineScope,
    private val clearRenderer: suspend () -> Unit,
    private val detachTrack: suspend () -> Unit,
    private val closePeer: suspend (Peer) -> Unit,
    private val stopAllocation: suspend (Allocation) -> Unit,
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000 },
    private val peerDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val onCleanupFailure: (Exception) -> Unit
) {
    inner class Attempt(val generation: Int, val timeline: PlaybackTimeline, val allocation: Allocation) {
        val firstFrame = CompletableDeferred<Unit>()
        val recovery = TransmissionRecoveryState()
        private val mediaLock = Mutex()
        private var peerEpoch = 0
        @Volatile var peer: Peer? = null
            private set
        @Volatile var released = false
            private set
        @Volatile var readyPeer: Peer? = null
            private set

        suspend fun awaitFirstFrame(failure: Deferred<Exception>, timeoutMs: Long = 20_000) {
            val presented = withTimeoutOrNull(timeoutMs) {
                select<Unit> {
                    firstFrame.onAwait { }
                    failure.onAwait { throw it }
                }
                true
            }
            if (presented != true) {
                throw IOException("VIDEO: transporte conectado, mas nenhum quadro foi renderizado em ${timeoutMs / 1000}s.")
            }
        }

        // Register on the constructor dispatcher before returning to Main. Cancellation at the
        // dispatch boundary can discard the return value after native resources have been created.
        suspend fun createPeer(factory: () -> Peer): Peer {
            val epoch = recovery.epoch
            val created = withContext(peerDispatcher) {
                mediaLock.withLock {
                    check(!released && peer == null) { "Playback already owns media" }
                    factory().also {
                        peer = it
                        peerEpoch = epoch
                    }
                }
            }
            readyPeer = created
            return created
        }

        suspend fun releaseTransport() = withContext(NonCancellable) {
            mediaLock.withLock {
                val retired = peer ?: return@withLock
                readyPeer = null
                peer = null
                val cleanup = MediaCleanup(clockMs)
                cleanup.step("track") { detachTrack() }
                cleanup.step("rtc") { closePeer(retired) }
                recordCleanup(peerEpoch, cleanup)
                cleanup.throwIfFailed()
            }
        }

        suspend fun release() = withContext(NonCancellable) {
            mediaLock.withLock {
                if (released) return@withLock
                released = true
                recovery.close()
                val retired = peer
                readyPeer = null
                peer = null
                val cleanup = MediaCleanup(clockMs)
                cleanup.step("renderer") { clearRenderer() }
                if (retired != null) cleanup.step("rtc") { closePeer(retired) }
                cleanup.step("allocation") { stopAllocation(allocation) }
                recordCleanup(null, cleanup)
                cleanup.throwIfFailed()
            }
        }

        private fun recordCleanup(epoch: Int?, cleanup: MediaCleanup) {
            val steps = cleanup.completedSteps().mapNotNull { step ->
                val stage = PlaybackTimeline.CleanupStage.entries.firstOrNull { it.name.equals(step.name, ignoreCase = true) }
                    ?: return@mapNotNull null
                PlaybackTimeline.Cleanup(stage, step.durationMs, step.failed)
            }
            timeline.recordCleanup(epoch, steps)
        }
    }

    data class Closing(val generation: Int, val job: Job?)

    @Volatile var generation = 0
        private set
    @Volatile private var active: Attempt? = null
    val peer: Peer? get() = active?.readyPeer
    val allocation: Allocation? get() = active?.takeUnless { it.released }?.allocation
    var job: Job? = null
        private set

    @OptIn(ExperimentalCoroutinesApi::class)
    @MainThread
    fun start(origin: PlaybackOrigin = PlaybackOrigin.CATALOG, allocation: Allocation, prepare: () -> Unit,
              play: suspend CoroutineScope.(Attempt) -> Unit) {
        val previous = job
        previous?.cancel()
        val next = ++generation
        val timeline = PlaybackTimeline(next, origin, clockMs)
        prepare()
        // A cancelled waiting launch must still join its predecessor before completing.
        job = scope.launch(start = CoroutineStart.ATOMIC) {
            if (previous != null) withContext(NonCancellable) { previous.join() }
            ensureActive()
            if (generation != next) return@launch
            val attempt = Attempt(next, timeline, allocation)
            active = attempt
            try {
                coroutineScope { play(attempt) }
            } finally {
                try { attempt.release() }
                catch (error: Exception) { onCleanupFailure(error) }
                finally { active = null }
            }
        }
    }

    @MainThread
    fun cancel(): Closing {
        val closing = Closing(++generation, job)
        closing.job?.cancel()
        return closing
    }
}
