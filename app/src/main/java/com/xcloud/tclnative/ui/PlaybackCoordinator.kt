package com.xcloud.tclnative.ui

import androidx.annotation.MainThread
import com.xcloud.tclnative.webrtc.TransmissionRecoveryState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException

/** Queues playback behind the previous owner's cleanup. Mutations are confined to the UI thread. */
internal class PlaybackCoordinator(private val scope: CoroutineScope) {
    class Attempt(val generation: Int) {
        val firstFrame = CompletableDeferred<Unit>()
        val recovery = TransmissionRecoveryState()

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

        suspend fun releaseMedia(detachVideo: () -> Unit, closePeer: () -> Unit, endAllocation: suspend () -> Unit) {
            try {
                detachVideo()
            } finally {
                try {
                    closePeer()
                } finally {
                    endAllocation()
                }
            }
        }
    }

    data class Closing(val generation: Int, val job: Job?)

    @Volatile var generation = 0
        private set
    var job: Job? = null
        private set

    @OptIn(ExperimentalCoroutinesApi::class)
    @MainThread
    fun start(prepare: () -> Unit, play: suspend CoroutineScope.(Attempt) -> Unit) {
        val previous = job
        previous?.cancel()
        val next = ++generation
        prepare()
        // A cancelled waiting launch must still join its predecessor before completing.
        job = scope.launch(start = CoroutineStart.ATOMIC) {
            if (previous != null) withContext(NonCancellable) { previous.join() }
            ensureActive()
            if (generation == next) play(Attempt(next))
        }
    }

    @MainThread
    fun cancel(): Closing {
        val closing = Closing(++generation, job)
        closing.job?.cancel()
        return closing
    }
}
