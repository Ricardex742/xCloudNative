package com.xcloud.tclnative

import com.xcloud.tclnative.webrtc.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

class ChannelLifecycleTraceTest {
    @Test fun firstCloseRemainsVisibleAndReceivedBodiesNeverEnterDiagnosticsOrConsumeTheirBuffer() {
        var now=1_000_000_000L
        val trace=ChannelLifecycleTrace { now }
        trace.state("control","OPEN");trace.state("input","OPEN");trace.state("message","OPEN")
        val payload=ByteBuffer.wrap("""{"type":"HandshakeAck","id":"private-account","content":"bearer-private-token"}""".toByteArray())
        trace.received("message",false,payload);assertEquals(0,payload.position())
        trace.received("control",false,ByteBuffer.wrap("""{"message":"authorizationResponse","token":"secret"}""".toByteArray()))
        trace.sent(ChannelMessage("control","""{"message":"authorizationRequest","accessKey":"private-key"}""".toByteArray()))
        trace.sent(ChannelMessage("input",GamepadPacket.metadata(0.0,10),true))
        now+=4_000_000_000L;trace.state("control","CLOSED")
        now+=3_000_000_000L;trace.state("input","CLOSED")
        val text=trace.diagnostics()
        assertTrue(text.contains("control=CLOSED"));assertTrue(text.contains("4000 ms"))
        assertTrue(text.contains("1/authorizationResponse"));assertTrue(text.contains("1/HandshakeAck"))
        listOf("private-account","private-token","private-key","secret").forEach { assertFalse(text.contains(it)) }
    }
    @Test fun unknownMessageTypesAndOversizedBodiesAreClassifiedWithoutBeingEchoed() {
        val trace=ChannelLifecycleTrace()
        trace.received("message",false,ByteBuffer.wrap("""{"type":"private-identity"}""".toByteArray()))
        trace.received("control",false,ByteBuffer.wrap(ByteArray(9000)))
        val text=trace.diagnostics()
        assertTrue(text.contains("1/OTHER"));assertTrue(text.contains("1/OVERSIZE"))
        assertFalse(text.contains("private-identity"))
    }
}
