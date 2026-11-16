package com.xcloud.tclnative

import com.xcloud.tclnative.webrtc.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class IdleInputHeartbeatTest {
    private fun frame(bytes: ByteArray)=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    @Test fun emptyTransportReportMatchesTheReliableV8HeaderWithoutAnInputPayload() {
        // Official SDK InputReporter.generateInputReport(8), no metadata or input frames;
        // normalize only its timestamp to 10ms. None does not represent a user action.
        val bytes=GamepadPacket.encode(emptyMap(),1,10.0)
        assertEquals("0000010000000000000000002440",bytes.joinToString("") { "%02x".format(it) })
    }
    @Test fun waitingWithoutAnyGestureSendsOnlyEmptyTransportHeadersAndStopsOnClose() {
        val packets=LinkedBlockingQueue<ByteArray>()
        val pump=RealtimeInputPump({0},{packets.offer(it);true},{fail("send failed")},initialGamepads=emptySet(),idleReports=true)
        try {
            pump.start()
            repeat(2) {
                val bytes=packets.poll(2500,TimeUnit.MILLISECONDS) ?: throw AssertionError("No idle transport report")
                assertEquals(14,bytes.size)
                val b=frame(bytes);assertEquals(0,b.short.toInt());assertEquals(it+1,b.int);assertTrue(b.double>=0)
            }
            val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(1)
            while(pump.metrics.idleReportsSent<2 && System.nanoTime()<end) Thread.yield()
            assertEquals(0L,pump.metrics.pointerEventsSent);assertEquals(GamepadState(),pump.metrics.sentState)
            assertEquals(2L,pump.metrics.idleReportsSent);assertEquals(28L,pump.metrics.sentBytes)
        } finally { pump.close() }
        assertNull(packets.poll(1100,TimeUnit.MILLISECONDS))
    }
    @Test fun idleMaintenanceRespectsBackpressureAndNeverCreatesAFingerOrGamepad() {
        val queued=AtomicLong(10000);val packets=LinkedBlockingQueue<ByteArray>()
        val pump=RealtimeInputPump({queued.get()},{packets.offer(it);true},{fail("send failed")},initialGamepads=emptySet(),idleReports=true)
        try {
            pump.start();assertNull(packets.poll(1200,TimeUnit.MILLISECONDS));assertEquals(0L,pump.metrics.idleReportsSent)
            queued.set(0)
            assertEquals(0,frame(packets.poll(1500,TimeUnit.MILLISECONDS)!!).short.toInt())
            pump.setTouchDimensions(TouchInputDimensions(1920,1080))
            pump.pointers(listOf(NativePointer(1,.25f,.5f,1)))
            val finger=frame(packets.poll(1000,TimeUnit.MILLISECONDS)!!)
            assertEquals(4,finger.short.toInt());assertEquals(36,finger.capacity());assertEquals(1,finger.getInt(23))
        } finally { pump.close() }
    }
}
