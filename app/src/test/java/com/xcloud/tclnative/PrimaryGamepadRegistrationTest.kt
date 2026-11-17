package com.xcloud.tclnative

import com.xcloud.tclnative.webrtc.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class PrimaryGamepadRegistrationTest {
    @Test fun startupWithSeveralKnownDevicesAndReconnectBothAnnouncePlayerOneFirst() {
        for(knownBeforeHandshake in listOf(true,false)) {
            var now=0L
            val handshake=ChannelHandshake(clockMs={now})
            val connections=GamepadConnections { handshake.gamepadSync.registered }
            val accepted=mutableListOf<Pair<Int,Boolean>>()
            val trace=ChannelLifecycleTrace()
            fun sent(message: ChannelMessage) {
                trace.sent(message);handshake.gamepadSync.sent(message)
                if(message.channel=="control" && !message.binary) {
                    val json=JSONObject(String(message.bytes))
                    if(json.optString("message")=="gamepadChanged") accepted+=json.getInt("gamepadIndex") to json.getBoolean("wasAdded")
                }
            }
            fun secondary(index: Int): Boolean {
                sent(ChannelHandshake.encode("control",JSONObject().put("message","gamepadChanged")
                    .put("gamepadIndex",index).put("wasAdded",true)))
                return true
            }
            listOf("message","input","control").forEach { handshake.onOpen(it).forEach(::sent) }
            handshake.onHandshakeAck().forEach(::sent)
            val known=if(knownBeforeHandshake) setOf(3,0,2,1) else setOf(0)
            assertFalse(connections.registerPending(known,::secondary))
            assertFalse(connections.add(1))
            assertEquals(listOf(0 to false),accepted)
            now=499;assertTrue(handshake.onGamepadSyncDue().isEmpty())
            now=500;handshake.onGamepadSyncDue().forEach(::sent)
            assertTrue(connections.registerPending(known,::secondary))
            // Reconnect discovers other devices after the first frame instead of before its handshake.
            assertTrue(connections.registerPending(setOf(3,0,2,1),::secondary))
            assertTrue(connections.registerPending(setOf(0,1,2,3),::secondary))
            assertEquals(listOf(0 to false,0 to true,1 to true,2 to true,3 to true),accepted)
            assertTrue(trace.diagnostics().contains("GAMEPAD_ORDER/0->0+>1+>2+>3+"))
        }
    }
    @Test fun rejectedSecondaryRegistrationRemainsPendingAndNativeTouchSlotNeverGetsASecondAttachment() {
        var primary=false
        val connections=GamepadConnections {primary}
        var calls=0
        assertFalse(connections.registerPending(setOf(0,1)) {calls++;true});assertEquals(0,calls)
        primary=true
        assertFalse(connections.registerPending(setOf(0,1)) {calls++;false})
        assertTrue(connections.registerPending(setOf(0,1)) {calls++;true})
        assertEquals(2,calls)
        assertFalse(connections.add(0));assertFalse(connections.remove(0))
        assertTrue(connections.remove(1));assertTrue(connections.add(1));assertFalse(connections.add(1))
    }
    @Test fun genuinePlayerOnePressQueuedBeforeAttachmentStaysInSlotZeroAfterSecondaryRegistration() {
        var now=0L
        val sync=GamepadStartupSync {now};sync.sent(sync.begin())
        val connections=GamepadConnections {sync.registered}
        val packets=LinkedBlockingQueue<ByteArray>()
        val pump=RealtimeInputPump({0},{packets.offer(it);true},{fail("Input failure")},initialGamepads=setOf(0,1))
        try {
            pump.update(GamepadState(buttons=16),0);pump.update(GamepadState(),0)
            assertFalse(connections.registerPending(setOf(0,1)) {true})
            assertNull(packets.poll(20,TimeUnit.MILLISECONDS))
            now=500;sync.sent(sync.due().single())
            assertTrue(connections.registerPending(setOf(0,1)) {true})
            pump.start()
            val press=ByteBuffer.wrap(packets.poll(1500,TimeUnit.MILLISECONDS)!!).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals(2,press.short.toInt());assertEquals(2,press.get(14).toInt())
            assertEquals(0,press.get(15).toInt());assertEquals(16,press.getShort(16).toInt())
            assertEquals(1,press.get(38).toInt());assertEquals(0,press.getShort(39).toInt())
            val release=ByteBuffer.wrap(packets.poll(1500,TimeUnit.MILLISECONDS)!!).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals(0,release.getShort(16).toInt())
        } finally {pump.close()}
    }
    @Test fun registrationTraceIsBoundedAndDoesNotRetainOtherFields() {
        val trace=ChannelLifecycleTrace()
        repeat(20) { index ->
            trace.sent(ChannelHandshake.encode("control",JSONObject().put("message","gamepadChanged")
                .put("gamepadIndex",index%4).put("wasAdded",true).put("private","DO_NOT_KEEP")))
        }
        assertEquals(8,trace.diagnostics().substringAfter("GAMEPAD_ORDER/").split(">").size)
        assertFalse(trace.diagnostics().contains("DO_NOT_KEEP"))
    }
}
