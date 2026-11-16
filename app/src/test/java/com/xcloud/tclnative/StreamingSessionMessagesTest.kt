package com.xcloud.tclnative

import com.xcloud.tclnative.diagnostics.*
import com.xcloud.tclnative.webrtc.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class StreamingSessionMessagesTest {
    private fun json(message: ChannelMessage)=JSONObject(String(message.bytes,Charsets.UTF_8))
    @Test fun everyChannelOrderActivatesTheTitleOnceAfterTheHandshakeForBothInputModes() {
        val orders=listOf(listOf("control","input","message"),listOf("control","message","input"),listOf("input","control","message"),
            listOf("input","message","control"),listOf("message","control","input"),listOf("message","input","control"))
        for(touch in listOf(false,true)) for(order in orders) for(ackAt in 0..3) {
            val handshake=ChannelHandshake(touch);val messages=mutableListOf<ChannelMessage>()
            for(i in 0..3) {
                if(i==ackAt) messages+=handshake.onHandshakeAck()
                if(i<3) messages+=handshake.onOpen(order[i])
            }
            val activation=messages.filterNot { it.binary }.single { json(it).optString("target")==StreamingSessionMessages.TITLE_CONSTRAIN }
            val body=json(activation)
            assertEquals("message",activation.channel);assertEquals("TransactionStart",body.getString("type"))
            assertFalse(JSONObject(body.getString("content")).getBoolean("constrain"))
            assertTrue(body.getString("cv").matches(Regex("[A-Za-z0-9+/]{22}\\.0")))
            assertTrue(messages.indexOf(activation)>messages.indexOfFirst { it.binary })
            assertTrue(handshake.onHandshakeAck().isEmpty());assertTrue(handshake.onOpen("message").isEmpty())
        }
    }
    @Test fun activationTracksTheMatchingResponseWithoutTreatingAnUnsupportedOptionalMethodAsAStreamFailure() {
        val messages=StreamingSessionMessages();val activation=messages.activateTitle();val id=json(activation).getString("id")
        assertTrue(messages.diagnostics().contains("TITLE_ACTIVATION/QUEUED"))
        messages.sent(activation);assertTrue(messages.diagnostics().contains("TITLE_ACTIVATION/SENT"))
        messages.receive(JSONObject().put("type","TransactionComplete").put("id","other"))
        assertTrue(messages.diagnostics().contains("TITLE_ACTIVATION/SENT"))
        assertNull(messages.receive(JSONObject().put("type","Unhandled").put("id",id)).disconnect)
        assertTrue(messages.diagnostics().contains("TITLE_ACTIVATION/UNSUPPORTED"))
        messages.receive(JSONObject().put("type","TransactionComplete").put("id",id));messages.sent(activation)
        assertTrue(messages.diagnostics().contains("TITLE_ACTIVATION/CONFIRMED"))
    }
    @Test fun serverTerminationIsAcknowledgedAndKeepsItsKnownReasonWithoutEchoingItsBodyIntoTheReport() {
        val messages=StreamingSessionMessages()
        val result=messages.receive(JSONObject().put("type","TransactionStart").put("id","opaque-server-id")
            .put("target",StreamingSessionMessages.SERVER_DISCONNECT).put("content",JSONObject().put("reason","KickForAppError")
                .put("hr",2147500037L).put("message","private-account private-token").toString()))
        assertEquals(XboxDisconnectReason.APP_ERROR,result.disconnect!!.reason)
        assertEquals(0x80004005.toInt(),result.disconnect!!.hresult)
        val ack=json(result.replies.single())
        assertEquals("TransactionComplete",ack.getString("type"));assertEquals("opaque-server-id",ack.getString("id"));assertEquals("",ack.getString("content"))
        val error=XboxServerDisconnectException(result.disconnect!!)
        val failure=SessionFailure.from(error)
        assertEquals(FailureStage.SESSION_STATE,failure.stage);assertEquals(FailureKind.SESSION_ENDED,failure.kind)
        assertEquals("KickForAppError",failure.serviceCode)
        val recovery=TransmissionRecoveryState();assertTrue(recovery.interrupted(0,error))
        assertEquals(TransmissionRecoveryState.Phase.SESSION_ENDED,recovery.phase);assertNull(recovery.begin())
        val diagnostics=messages.diagnostics()+failure.description
        listOf("private-account","private-token","opaque-server-id").forEach { assertFalse(diagnostics.contains(it)) }
    }
    @Test fun warningsUnknownReasonsAndUnrelatedTargetsCannotTerminateTheSession() {
        for((target,reason,terminal) in listOf(Triple(StreamingSessionMessages.SERVER_DISCONNECT,"WarningForBeingIdle",false),
            Triple(StreamingSessionMessages.SERVER_DISCONNECT,"private-value",false),Triple("/unrelated","KickForAppError",false))) {
            val messages=StreamingSessionMessages()
            val result=messages.receive(JSONObject().put("type","TransactionStart").put("id","server-id").put("target",target)
                .put("content",JSONObject().put("reason",reason).put("hr",2.5)))
            assertEquals(terminal,result.disconnect?.reason?.terminal ?: false)
            assertNull(result.disconnect?.hresult);assertFalse(messages.diagnostics().contains("private-value"))
        }
    }
}
