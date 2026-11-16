package com.xcloud.tclnative

import com.xcloud.tclnative.webrtc.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class NativeTouchInitializationTest {
    private fun flags(data: ByteArray)=ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).short.toInt()
    private fun take(packets: LinkedBlockingQueue<ByteArray>)=packets.poll(1500,TimeUnit.MILLISECONDS) ?: throw AssertionError("No input packet")
    private fun awaitMetrics(pump: RealtimeInputPump) {
        val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(1)
        while(pump.metrics.sentBytes==0L && System.nanoTime()<end) Thread.yield()
    }
    @Test fun initialStateIsSlotZeroWithEveryControlReleasedAndNoPhysicalityBits() {
        val bytes=GamepadPacket.neutralTouchSlot(7,12.0)
        val b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(38,bytes.size);assertEquals(2,b.short.toInt());assertEquals(7,b.int);assertEquals(12.0,b.double,0.0)
        assertEquals(1,b.get().toInt());assertEquals(0,b.get().toInt())
        while(b.hasRemaining()) assertEquals(0,b.get().toInt())
    }
    @Test fun startupWaitsForMetadataInitializesOnceAndKeepsSubsequentInputNative() {
        val packets=LinkedBlockingQueue<ByteArray>()
        val pump=RealtimeInputPump({0},{packets.offer(it);true},{fail("send")},initialGamepads=emptySet(),initializeTouchSlot=true)
        try {
            pump.start();assertNull(packets.poll(100,TimeUnit.MILLISECONDS))
            pump.setTouchDimensions(TouchInputDimensions(1920,1080))
            assertEquals(2,flags(take(packets)));awaitMetrics(pump)
            assertEquals(1L,pump.metrics.initialStateReportsSent);assertEquals(0L,pump.metrics.pointerEventsSent)
            assertNull(packets.poll(100,TimeUnit.MILLISECONDS))
            pump.pointers(listOf(NativePointer(1,.25f,.5f,1)))
            assertEquals(4,flags(take(packets)))
            assertNull(packets.poll(100,TimeUnit.MILLISECONDS))
        } finally { pump.close() }
    }
    @Test fun queuedRealGestureUsesItsOwnReportAndDoesNotGetAnExtraNeutralGamepad() {
        val packets=LinkedBlockingQueue<ByteArray>()
        val pump=RealtimeInputPump({0},{packets.offer(it);true},{fail("send")},initialGamepads=emptySet(),
            initialTouchDimensions=TouchInputDimensions(1920,1080),initializeTouchSlot=true)
        try {
            pump.pointers(listOf(NativePointer(1,.2f,.4f,1)));pump.start()
            assertEquals(4,flags(take(packets)));awaitMetrics(pump)
            assertEquals(0L,pump.metrics.initialStateReportsSent);assertNull(packets.poll(100,TimeUnit.MILLISECONDS))
        } finally { pump.close() }
    }
    @Test fun backpressureDelaysInitializationWithoutAddingAFingerOrContinuousGamepadTraffic() {
        val packets=LinkedBlockingQueue<ByteArray>();val queued=AtomicLong(10000)
        val pump=RealtimeInputPump({queued.get()},{packets.offer(it);true},{fail("send")},initialGamepads=emptySet(),
            initialTouchDimensions=TouchInputDimensions(1920,1080),initializeTouchSlot=true)
        try {
            pump.start();assertNull(packets.poll(100,TimeUnit.MILLISECONDS));assertEquals(0L,pump.metrics.initialStateReportsSent)
            queued.set(0);assertEquals(2,flags(take(packets)));awaitMetrics(pump)
            assertEquals(1L,pump.metrics.initialStateReportsSent);assertEquals(0L,pump.metrics.pointerEventsSent)
            assertNull(packets.poll(100,TimeUnit.MILLISECONDS))
        } finally { pump.close() }
    }
}
