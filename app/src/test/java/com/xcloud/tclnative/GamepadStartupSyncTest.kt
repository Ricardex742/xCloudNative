package com.xcloud.tclnative

import com.xcloud.tclnative.webrtc.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class GamepadStartupSyncTest {
    private fun body(message: ChannelMessage): JSONObject {
        assertEquals("control",message.channel);assertFalse(message.binary)
        return JSONObject(String(message.bytes,Charsets.UTF_8))
    }
    @Test fun aFreshTransportResetsThenRegistersSlotZeroOnceWithoutAnyGameInput() {
        var now=1000L;val sync=GamepadStartupSync { now }
        val reset=sync.begin()
        assertEquals("gamepadChanged",body(reset).getString("message"))
        assertEquals(0,body(reset).getInt("gamepadIndex"));assertFalse(body(reset).getBoolean("wasAdded"))
        assertNull(sync.remainingMs());assertTrue(sync.due().isEmpty())
        sync.sent(reset);assertEquals(500L,sync.remainingMs())
        now+=499;assertTrue(sync.due().isEmpty());assertFalse(sync.registered)
        now++;val add=sync.due().single()
        assertEquals(3,body(add).length());assertTrue(body(add).getBoolean("wasAdded"))
        assertEquals(0,body(add).getInt("gamepadIndex"))
        assertFalse(sync.registered);assertTrue(sync.due().isEmpty())
        sync.sent(add);assertTrue(sync.registered)
        assertTrue(sync.diagnostics().contains("ADDED; removed=true; added=true; added_after=500ms"))
        sync.close();assertTrue(sync.due().isEmpty())
    }
    @Test fun closingAnOldAttemptCancelsItsRegistrationWhileTheNextAttemptHasItsOwnDeadline() {
        var now=0L;val old=GamepadStartupSync { now }
        old.sent(old.begin());now=200;old.close()
        val next=GamepadStartupSync { now };next.sent(next.begin())
        now=500;assertTrue(old.due().isEmpty());assertTrue(next.due().isEmpty())
        assertTrue(old.diagnostics().contains("CANCELLED"));assertFalse(old.registered)
        now=700;val added=next.due().single();next.sent(added)
        assertTrue(next.registered);assertFalse(old.registered)
    }
    @Test fun anUnacceptedAdditionIsNotReportedAsARegisteredControlAndIsNotRetriedIndefinitely() {
        var now=0L;val sync=GamepadStartupSync { now }
        sync.sent(sync.begin());now=500
        assertEquals(1,sync.due().size)
        now=5000;assertTrue(sync.due().isEmpty());assertFalse(sync.registered)
        assertTrue(sync.diagnostics().contains("QUEUED"));sync.close()
        assertTrue(sync.diagnostics().contains("CANCELLED"))
    }
    @Test fun nativeKeyboardMouseKeepsItsImmediateRegistrationWithoutAScheduledCycle() {
        val sync=GamepadStartupSync(false)
        val message=sync.begin();assertTrue(body(message).getBoolean("wasAdded"))
        sync.sent(message);assertTrue(sync.registered)
        assertNull(sync.remainingMs());assertTrue(sync.due().isEmpty())
        assertTrue(sync.diagnostics().contains("removed=false; added=true"))
    }
    @Test fun aRealGestureQueuedDuringRegistrationIsDeliveredInOrderAfterInputStarts() {
        var now=0L;val sync=GamepadStartupSync { now };sync.sent(sync.begin())
        val packets=LinkedBlockingQueue<ByteArray>()
        val pump=RealtimeInputPump({0},{packets.offer(it);true},{fail("send")},initialGamepads=emptySet(),
            initialTouchDimensions=TouchInputDimensions(1920,1080),initializeTouchSlot=true)
        try {
            pump.pointers(listOf(NativePointer(1,.2f,.4f,1)))
            pump.pointers(listOf(NativePointer(1,.2f,.4f,2)))
            assertFalse(sync.registered);assertNull(packets.poll(30,TimeUnit.MILLISECONDS))
            now=500;sync.sent(sync.due().single());assertTrue(sync.registered)
            pump.start()
            val types=(1..2).map {
                val packet=packets.poll(1500,TimeUnit.MILLISECONDS) ?: throw AssertionError("Missing gesture")
                assertEquals(4,ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN).short.toInt())
                packet.last().toInt()
            }
            assertEquals(listOf(1,2),types)
            assertNull(packets.poll(30,TimeUnit.MILLISECONDS))
        } finally { pump.close() }
    }
}
