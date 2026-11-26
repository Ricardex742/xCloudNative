package com.xcloud.tclnative

import com.xcloud.tclnative.webrtc.RtcStatisticsSampler
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RtcStatisticsSamplerTest {
    @Test fun lateWebRtcCallbackIsDiscardedWithoutOverlappingNativeRequests() = runTest {
        val sampler = RtcStatisticsSampler<String>(100) { testScheduler.currentTime }
        lateinit var complete: (String) -> Unit
        val first = async { sampler.sample { complete = it; true } }
        runCurrent()
        advanceTimeBy(150)
        assertTrue(sampler.diagnostics().contains("timeout=1"))
        assertNull(sampler.sample { error("The first native request still owns the slot") })
        complete("late")
        assertNull(first.await())
        assertTrue(sampler.diagnostics().contains("late=1"))

        val next = async { sampler.sample { complete = it; true } }
        runCurrent()
        advanceTimeBy(20)
        complete("current")
        assertEquals("current", next.await())
        assertTrue(sampler.diagnostics().contains("last_callback_ms=20"))
    }

    @Test fun closingTheOwnerReleasesTheWaitAndIgnoresTheRetiredCallback() = runTest {
        val sampler = RtcStatisticsSampler<String>(100) { testScheduler.currentTime }
        lateinit var retired: (String) -> Unit
        val pending = async { sampler.sample { retired = it; true } }
        runCurrent()
        pending.cancelAndJoin()
        retired("retired")
        assertNull(sampler.sample { false })
        assertEquals("new", sampler.sample { it("new"); true })
        assertTrue(sampler.diagnostics().contains("complete=1 timeout=0 late=0 rejected=1"))
    }
}
