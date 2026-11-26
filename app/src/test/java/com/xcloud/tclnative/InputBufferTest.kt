package com.xcloud.tclnative

import com.xcloud.tclnative.webrtc.GamepadInputBuffer
import com.xcloud.tclnative.webrtc.GamepadState
import com.xcloud.tclnative.webrtc.InputMetrics
import com.xcloud.tclnative.webrtc.RealtimeInputPump
import org.junit.Assert.*
import org.junit.Test

class InputBufferTest {
    @Test fun diagnosticSnapshotsAreThrottledWithoutSlowingPacketsAndFinalBytesAreAccurate() {
        val clock = java.util.concurrent.atomic.AtomicLong()
        val snapshots = java.util.IdentityHashMap<InputMetrics, Boolean>()
        val offered = java.util.concurrent.CountDownLatch(50)
        var sentBytes = 0L
        lateinit var pump: RealtimeInputPump
        pump = RealtimeInputPump({ 0 }, { packet ->
            snapshots[pump.metrics] = true
            sentBytes += packet.size
            clock.addAndGet(4_000_000)
            offered.countDown()
            if (offered.count == 0L) pump.requestStop()
            true
        }, { fail("Unexpected send failure") }, clockNs = clock::get)
        pump.start()
        try {
            assertTrue(offered.await(2, java.util.concurrent.TimeUnit.SECONDS))
        } finally { pump.close() }
        assertTrue(snapshots.size in 2..4)
        assertEquals(50L * 38, sentBytes)
        assertEquals(sentBytes, pump.metrics.sentBytes)
    }

    @Test fun officialTapInNativeModeReleasesOnlyAfterItsQueuedPress() {
        val packets = java.util.concurrent.LinkedBlockingQueue<ByteArray>()
        val pump = com.xcloud.tclnative.webrtc.RealtimeInputPump({ 0 }, { packets.offer(it); true },
            { fail("Send failed") }, initialGamepads = emptySet())
        try {
            pump.update(GamepadState(buttons = 16))
            pump.releaseAfterNeutral(preserveButtonEdges = true)
            pump.start()
            val buttons = List(2) {
                val packet = packets.poll(2, java.util.concurrent.TimeUnit.SECONDS) ?: error("Missing gamepad packet")
                java.nio.ByteBuffer.wrap(packet).order(java.nio.ByteOrder.LITTLE_ENDIAN).getShort(16).toInt()
            }
            assertEquals(listOf(16, 0), buttons)
        } finally {
            pump.close()
        }
    }

    @Test fun guideCountersCountEdgesAcceptedByTheChannel() {
        val pump=com.xcloud.tclnative.webrtc.RealtimeInputPump({0},{true},{fail("Input failed")})
        try {
            pump.update(GamepadState(buttons=2))
            pump.update(GamepadState(buttons=2,leftX=.6f))
            pump.update(GamepadState())
            pump.start()
            val end=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(2)
            while(pump.metrics.xboxReleases==0L && System.nanoTime()<end) Thread.sleep(1)
            assertEquals(1L,pump.metrics.xboxPresses)
            assertEquals(1L,pump.metrics.xboxReleases)
        } finally { pump.close() }
    }
    @Test fun resettingInputDiscardsUnsentPressesInsteadOfReplayingThemAfterFocusLoss() {
        val packets = java.util.concurrent.LinkedBlockingQueue<ByteArray>()
        val pump = com.xcloud.tclnative.webrtc.RealtimeInputPump({ 0 }, { packets.offer(it); true }, { fail("Send failed") })
        try {
            pump.update(GamepadState(buttons = 16))
            pump.update(GamepadState())
            pump.update(GamepadState(buttons = 16))
            pump.resetGamepads()
            pump.start()
            repeat(3) {
                val packet = packets.poll(2, java.util.concurrent.TimeUnit.SECONDS)
                assertNotNull(packet)
                assertEquals(0, java.nio.ByteBuffer.wrap(packet).order(java.nio.ByteOrder.LITTLE_ENDIAN).getShort(16).toInt())
            }
        } finally { pump.close() }
    }
    @Test fun diagnosticInputBytesCountOnlyPacketsActuallyAcceptedByTheChannel() {
        val sent=java.util.concurrent.atomic.AtomicLong()
        val pump=com.xcloud.tclnative.webrtc.RealtimeInputPump({0},{ sent.addAndGet(it.size.toLong()); true },
            { fail("Input failed") },initialGamepads=emptySet(),
            initialTouchDimensions=com.xcloud.tclnative.webrtc.TouchInputDimensions(1920,1080))
        try {
            pump.start()
            Thread.sleep(30)
            assertEquals(0L,pump.metrics.sentBytes); assertEquals(0L,sent.get())
            pump.pointers(listOf(com.xcloud.tclnative.webrtc.NativePointer(1,.25f,.5f,1)))
            pump.pointers(listOf(com.xcloud.tclnative.webrtc.NativePointer(1,.25f,.5f,2,0f)))
            val end=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(2)
            while(pump.metrics.pointerEventsSent<2 && System.nanoTime()<end) Thread.sleep(1)
            assertEquals(2L,pump.metrics.pointerEventsSent)
        } finally { pump.close() }
        assertEquals(72L,sent.get()); assertEquals(sent.get(),pump.metrics.sentBytes)
    }
    @Test fun cameraChangesWakeThePumpAndCoalesceWithoutReplayingStaleMotion() {
        val input=GamepadInputBuffer()
        assertTrue(input.update(GamepadState(rightX=.2f)))
        assertTrue(input.hasUrgent()); assertFalse(input.hasEdges())
        input.update(GamepadState(rightX=.8f))
        assertEquals(.8f,input.next().rightX,0f); assertFalse(input.hasUrgent())
        assertTrue(input.update(GamepadState()))
        assertTrue(input.hasUrgent()); assertEquals(0f,input.next().rightX,0f)
    }
    @Test fun quickCompositeTriggerActionsKeepTheirPressAndReleaseSnapshots() {
        val input=GamepadInputBuffer()
        input.update(GamepadState(buttons=16,rightTrigger=1f))
        input.update(GamepadState())
        val pressed=input.next(); val released=input.next()
        assertEquals(16,pressed.buttons); assertEquals(1f,pressed.rightTrigger,0f)
        assertEquals(GamepadState(),released)
        input.update(GamepadState(leftTrigger=1f)); input.update(GamepadState())
        assertEquals(1f,input.next().leftTrigger,0f); assertEquals(0f,input.next().leftTrigger,0f)
    }
    @Test fun nativePumpKeepsBothPlayersAndDisconnectingOneDoesNotReleaseTheOther() {
        val received=java.util.concurrent.LinkedBlockingQueue<ByteArray>()
        val pump=com.xcloud.tclnative.webrtc.RealtimeInputPump({ 0 },{ received.offer(it); true },{ fail("Send failed") })
        fun next(count: Int): ByteArray {
            val end=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(2)
            while(System.nanoTime()<end) {
                val data=received.poll(100,java.util.concurrent.TimeUnit.MILLISECONDS) ?: continue
                if(data[14].toInt()==count) return data
            }
            throw AssertionError("No packet with $count players")
        }
        try {
            pump.update(GamepadState(buttons=16),0); pump.update(GamepadState(buttons=32),1)
            pump.start()
            val both=java.nio.ByteBuffer.wrap(next(2)).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            assertEquals(16,both.getShort(16).toInt()); assertEquals(32,both.getShort(39).toInt())
            pump.remove(1)
            val one=java.nio.ByteBuffer.wrap(next(1)).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            assertEquals(16,one.getShort(16).toInt())
        } finally { pump.close() }
    }
    @Test fun quickPressAndReleaseBetweenPeriodicTicksAreBothPreserved() {
        val input = GamepadInputBuffer()
        assertTrue(input.update(GamepadState(buttons = 16)))
        assertTrue(input.update(GamepadState()))
        assertEquals(16, input.next().buttons)
        assertEquals(0, input.next().buttons)
        assertEquals(0, input.next().buttons)
    }
    @Test fun analogMotionUsesTheLatestStateInsteadOfQueuingOldPositions() {
        val input = GamepadInputBuffer()
        repeat(100) { input.update(GamepadState(leftX = it / 100f)) }
        assertFalse(input.hasEdges())
        assertEquals(0.99f, input.next().leftX, 0.001f)
    }
    @Test fun congestionDropsOldButtonStatesButPreservesTheCurrentRelease() {
        val input = GamepadInputBuffer()
        input.update(GamepadState(buttons = 16)); input.update(GamepadState())
        input.discardBacklog()
        assertFalse(input.hasEdges())
        assertEquals(0, input.next().buttons)
        assertEquals(2L, input.coalescedEdges)
    }
    @Test fun excessiveTransitionsCannotBecomeAnUnboundedInputReplay() {
        val input = GamepadInputBuffer()
        repeat(40) { input.update(GamepadState(buttons = if (it % 2 == 0) 16 else 0)) }
        var pending = 0
        while (input.hasEdges()) { input.next(); pending++ }
        assertTrue(pending <= 8)
        assertTrue(input.coalescedEdges > 0)
        assertEquals(0, input.next().buttons)
    }

    @Test fun liveInputPumpDoesNotFloodABlockedChannelAndPreservesARecentTap() {
        val queued = java.util.concurrent.atomic.AtomicLong(com.xcloud.tclnative.webrtc.RealtimeInputPump.MAX_BUFFERED_BYTES)
        val clock = java.util.concurrent.atomic.AtomicLong()
        val received = java.util.concurrent.LinkedBlockingQueue<ByteArray>()
        val pump = com.xcloud.tclnative.webrtc.RealtimeInputPump(
            { queued.get() }, { received.offer(it); true }, { fail("Unexpected send failure") }, clockNs = clock::get)
        try {
            pump.start()
            val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2)
            while (pump.metrics.backpressureSkips == 0L && System.nanoTime() < deadline) Thread.sleep(1)
            assertTrue(pump.metrics.backpressureSkips > 0)
            assertTrue(received.isEmpty())
            pump.update(GamepadState(buttons = 16), officialTouch = true)
            pump.update(GamepadState(), officialTouch = true)
            queued.set(0)
            clock.set(4_000_000)
            fun nextButtons(): Int {
                val packet = received.poll(2, java.util.concurrent.TimeUnit.SECONDS)
                assertNotNull(packet)
                return java.nio.ByteBuffer.wrap(packet).order(java.nio.ByteOrder.LITTLE_ENDIAN).getShort(16).toInt()
            }
            assertEquals(16, nextButtons())
            assertEquals(0, nextButtons())
        } finally { pump.close() }
    }

    @Test fun optionalIdleCadenceResumesImmediatelyForActiveGamepads() {
        val clock = java.util.concurrent.atomic.AtomicLong()
        val received = java.util.concurrent.LinkedBlockingQueue<ByteArray>()
        val milliseconds = java.util.concurrent.TimeUnit.MILLISECONDS
        val pump = com.xcloud.tclnative.webrtc.RealtimeInputPump(
            { 0 }, { received.offer(it); true }, { fail("Unexpected send failure") },
            clockNs = clock::get, tryIdleGamepadCadence = true)
        fun buttons(): Int {
            val packet = requireNotNull(received.poll(2_000, milliseconds))
            return java.nio.ByteBuffer.wrap(packet).order(java.nio.ByteOrder.LITTLE_ENDIAN).getShort(16).toInt()
        }
        try {
            pump.start()
            assertEquals(0, buttons())
            clock.set(4_000_000)
            assertNull(received.poll(50, milliseconds))
            pump.update(GamepadState(buttons = 16))
            assertEquals(16, buttons())
            clock.set(8_000_000)
            assertEquals(16, buttons())
            pump.update(GamepadState())
            assertEquals(0, buttons())
        } finally { pump.close() }
        assertTrue(pump.metrics.neutralGamepadReports > 0)
    }

    @Test fun staleTapExpiresWithoutReplayingThePressOrLosingTheCurrentRelease() {
        val input = GamepadInputBuffer()
        input.update(GamepadState(buttons = 16), 0)
        input.update(GamepadState(), 1_000_000)
        assertEquals(0, input.next(120_000_000).buttons)
        assertEquals(2L, input.expiredEdges)
        assertFalse(input.hasEdges())
    }

    @Test fun pumpCloseWaitsForAnInFlightSendBeforeItsChannelCanBeDisposed() {
        val sending = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val closed = java.util.concurrent.CountDownLatch(1)
        val seconds = java.util.concurrent.TimeUnit.SECONDS
        val pump = com.xcloud.tclnative.webrtc.RealtimeInputPump({ 0 }, {
            sending.countDown()
            release.await()
            true
        }, { fail("Unexpected send failure") })
        pump.start()
        try {
            assertTrue(sending.await(2, seconds))
            Thread { pump.close(); closed.countDown() }.start()
            assertFalse(closed.await(350, java.util.concurrent.TimeUnit.MILLISECONDS))
        } finally {
            release.countDown()
            pump.close()
        }
        assertTrue(closed.await(2, seconds))
    }
    @Test fun failedNativeSendTerminatesThePumpAndReportsFailure() {
        val failures = java.util.concurrent.atomic.AtomicInteger()
        val failed = java.util.concurrent.CountDownLatch(1)
        val pump = com.xcloud.tclnative.webrtc.RealtimeInputPump({ 0 }, { false }, { failures.incrementAndGet(); failed.countDown() })
        try {
            pump.start()
            assertTrue(failed.await(2, java.util.concurrent.TimeUnit.SECONDS))
        } finally { pump.close() }
        assertEquals(1, failures.get())
    }
    @Test fun firstNativeTapSurvivesChannelStartupAndStartsAfterMetadataSequenceZero() {
        val touch=com.xcloud.tclnative.webrtc.NativeTouchBuffer()
        touch.add(listOf(com.xcloud.tclnative.webrtc.NativePointer(7,.25f,.5f,1)))
        touch.add(listOf(com.xcloud.tclnative.webrtc.NativePointer(7,.25f,.5f,2,0f)))
        val received=java.util.concurrent.LinkedBlockingQueue<ByteArray>()
        val pump=com.xcloud.tclnative.webrtc.RealtimeInputPump({0},{ received.offer(it); true },{ fail("Input failed") },
            initialGamepads=emptySet(),touch=touch,
            initialTouchDimensions=com.xcloud.tclnative.webrtc.TouchInputDimensions(1920,1080))
        try {
            pump.start()
            val down=java.nio.ByteBuffer.wrap(received.poll(2,java.util.concurrent.TimeUnit.SECONDS)!!).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            val up=java.nio.ByteBuffer.wrap(received.poll(2,java.util.concurrent.TimeUnit.SECONDS)!!).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            assertEquals(4,down.getShort(0).toInt()); assertEquals(1,down.getInt(2)); assertEquals(2,up.getInt(2))
            assertEquals(7,down.getInt(23)); assertEquals(480,down.getInt(27)); assertEquals(540,down.getInt(31))
            assertEquals(1,down.get(35).toInt()); assertEquals(2,up.get(35).toInt())
        } finally { pump.close() }
    }
}
