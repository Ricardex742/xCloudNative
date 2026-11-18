package com.xcloud.tclnative

import com.xcloud.tclnative.ui.PlaybackCoordinator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackCoordinatorTest {
    @Test fun replacementAndCancellationWaitForTheFirstAttemptsCleanup() = runTest {
        val playback = PlaybackCoordinator(this)
        val events = mutableListOf<String>()
        val finishRelease = CompletableDeferred<Unit>()
        playback.start(prepare = { events += "prepare first" }) {
            events += "first started"
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) {
                    events += "first releasing"
                    finishRelease.await()
                    events += "first released"
                }
            }
        }
        runCurrent()
        playback.start(prepare = { events += "prepare second" }) { events += "second started" }
        val closing = playback.cancel()
        playback.start(prepare = { events += "prepare third" }) { events += "third started" }
        runCurrent()

        assertEquals(listOf("prepare first", "first started", "prepare second", "prepare third", "first releasing"), events)
        assertEquals(false, closing.job?.isCompleted)
        assertTrue(closing.generation < playback.generation)
        finishRelease.complete(Unit)
        runCurrent()
        assertEquals(listOf("first releasing", "first released", "third started"), events.takeLast(3))
        assertEquals(true, closing.job?.isCancelled)
    }

    @Test fun releasingAnAttemptKeepsTheTrackPeerAllocationOrderEvenIfDetachingFails() = runTest {
        val attempt = PlaybackCoordinator.Attempt(1)
        val events = mutableListOf<String>()
        val expected = IOException("detach failure")
        val result = runCatching {
            attempt.releaseMedia(
                detachVideo = { events += "track"; throw expected },
                closePeer = { events += "peer" },
                endAllocation = { events += "allocation" }
            )
        }
        assertSame(expected, result.exceptionOrNull())
        assertEquals(listOf("track", "peer", "allocation"), events)
    }
}
