package com.xcloud.tclnative

import com.xcloud.tclnative.diagnostics.PlaybackTimeline.CleanupStage
import com.xcloud.tclnative.ui.PlaybackCoordinator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackCoordinatorTest {
    private fun TestScope.playback(
        clearRenderer: suspend () -> Unit = {},
        detachTrack: suspend () -> Unit = {},
        closePeer: suspend (String) -> Unit = {},
        stopAllocation: suspend (String) -> Unit = {},
        onFailure: (Exception) -> Unit = { throw AssertionError("Unexpected cleanup failure", it) }
    ) = PlaybackCoordinator(this, clearRenderer, detachTrack, closePeer, stopAllocation,
        peerDispatcher = StandardTestDispatcher(testScheduler, "construct peer"), onCleanupFailure = onFailure)

    @Test fun replacementAndCancellationWaitForTheFirstAttemptsCleanup() = runTest {
        val events = mutableListOf<String>()
        val finishRelease = CompletableDeferred<Unit>()
        val playback = playback(closePeer = {
            events += "$it releasing"
            finishRelease.await()
            events += "$it released"
        })
        playback.start(allocation = "allocation 1", prepare = { events += "prepare first" }) { attempt ->
            attempt.createPeer { "first" }
            events += "first started"
            awaitCancellation()
        }
        runCurrent()
        playback.start(allocation = "allocation 2", prepare = { events += "prepare second" }) { events += "second started" }
        val closing = playback.cancel()
        playback.start(allocation = "allocation 3", prepare = { events += "prepare third" }) { events += "third started" }
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
        val events = mutableListOf<String>()
        val expected = IOException("detach failure")
        val peerError = IOException("peer failure")
        val playback = playback(clearRenderer = { events += "track"; throw expected },
            closePeer = { events += it; throw peerError }, stopAllocation = { events += it })
        playback.start(allocation = "allocation", prepare = {}) { attempt ->
            attempt.createPeer { "peer" }
            val failure = runCatching { attempt.release() }.exceptionOrNull()
            // Coroutine debugging can wrap the original when restoring the stack trace.
            assertEquals(expected.message, failure?.message)
            assertTrue(failure === expected || failure?.cause === expected)
            assertSame(peerError, expected.suppressed.single())
            val cleanup = attempt.timeline.snapshot().cleanup
            assertEquals(listOf(CleanupStage.RENDERER, CleanupStage.RTC, CleanupStage.ALLOCATION), cleanup.map { it.stage })
            assertEquals(listOf(true, true, false), cleanup.map { it.failed })
        }
        runCurrent()
        assertEquals(listOf("track", "peer", "allocation"), events)
        assertNull(playback.allocation)
    }

    @Test fun cancellationAfterNativeConstructionStillOwnsAndClosesThePeer() = runTest {
        val events = mutableListOf<String>()
        val playback = playback(clearRenderer = { events += "clear" }, closePeer = { events += it }, stopAllocation = { events += it })
        playback.start(allocation = "allocation", prepare = {}) { attempt ->
            attempt.createPeer {
                playback.cancel()
                "native peer"
            }
            fail("A cancelled launch published the peer to the dashboard")
        }
        runCurrent()
        assertEquals(listOf("clear", "native peer", "allocation"), events)
        assertNull(playback.allocation)
    }

    @Test fun transportRetryReleasesOnlyTheRetiredPeerAndKeepsTheAllocationUntilExit() = runTest {
        val events = mutableListOf<String>()
        val playback = playback(clearRenderer = { events += "clear" }, detachTrack = { events += "detach" },
            closePeer = { events += it }, stopAllocation = { events += it })
        playback.start(allocation = "allocation", prepare = {}) { attempt ->
            attempt.createPeer { "first peer" }
            val epoch = checkNotNull(attempt.recovery.begin())
            attempt.timeline.beginReconnect(epoch)
            attempt.releaseTransport()
            attempt.releaseTransport()
            assertEquals("allocation", playback.allocation)
            attempt.createPeer { "second peer" }
            assertEquals(listOf("detach", "first peer"), events)
            val transports = attempt.timeline.snapshot().transports
            assertEquals(listOf(CleanupStage.TRACK, CleanupStage.RTC), transports.first().cleanup.map { it.stage })
            assertTrue(transports.last().cleanup.isEmpty())
            withContext(NonCancellable) { attempt.release() }
            assertNull(playback.allocation)
        }
        runCurrent()
        assertEquals(listOf("detach", "first peer", "clear", "second peer", "allocation"), events)
    }
}
