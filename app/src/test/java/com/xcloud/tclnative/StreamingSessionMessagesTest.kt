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
    @Test fun unsupportedRequestsReceiveTheOfficialUnhandledEnvelopeWithoutAFalseSuccessOrAnInfiniteReplyLoop() {
        val cv="AAAAAAAAAAAAAAAAAAAAAA.4"
        for(type in listOf("Message","TransactionStart")) {
            val messages=StreamingSessionMessages()
            val request=JSONObject().put("type",type).put("id","server-request").put("target","/unsupported/feature")
                .put("content","private-account bearer private-token").put("cv",cv)
            val result=messages.receive(request);assertNull(result.disconnect)
            val reply=json(result.replies.single())
            assertEquals("Unhandled",reply.getString("type"));assertEquals("server-request",reply.getString("id"))
            assertEquals("/unsupported/feature",reply.getString("target"));assertEquals("AAAAAAAAAAAAAAAAAAAAAA.5",reply.getString("cv"))
            assertFalse(reply.has("content"));assertTrue(messages.diagnostics().contains("reply_queued=1; reply_sent=0"))
            messages.sent(result.replies.single());assertTrue(messages.diagnostics().contains("reply_sent=1"))
            for(response in listOf("Unhandled","Error","TransactionComplete","ReceiverCancel","SenderCancel")) {
                assertTrue(messages.receive(JSONObject(request.toString()).put("type",response)).replies.isEmpty())
            }
            assertFalse(messages.diagnostics().contains("private-account"));assertFalse(messages.diagnostics().contains("private-token"))
        }
    }
    @Test fun titleNotificationsRecordOnlyDeclaredStateAndFocusAndNeverCloseTheSessionByThemselves() {
        var now=1000L;val messages=StreamingSessionMessages { now }
        val notification=JSONObject().put("type","Message").put("target",StreamingSessionMessages.TITLE_INFO)
            .put("content",JSONObject().put("state",4).put("focused",true).put("titleid","private-id").put("titleaumid","private-aumid").toString())
        assertTrue(messages.receive(notification).replies.isEmpty())
        assertTrue(messages.diagnostics().contains("TITLE_STATE/Active; focused=true; updates=1"))
        now+=3000
        notification.put("id","server-id").put("content",JSONObject().put("state",0).put("focused",false))
        val closed=messages.receive(notification)
        assertNull(closed.disconnect);assertTrue(closed.replies.isEmpty())
        val text=messages.diagnostics()
        assertTrue(text.contains("TITLE_STATE/Closed; focused=false; updates=2"))
        assertTrue(text.contains("0:Active/true>3000:Closed/false"))
        listOf("private-id","private-aumid","server-id").forEach { assertFalse(text.contains(it)) }
        val unexpected=messages.receive(notification.put("type","TransactionStart"))
        assertEquals("ReceiverCancel",json(unexpected.replies.single()).getString("type"))
        assertFalse(json(unexpected.replies.single()).has("content"))
    }
    @Test fun passiveTimeNotificationsDoNotInventAQuotaOrAcknowledgeAnUnexpectedTransactionAsCompleted() {
        val messages=StreamingSessionMessages()
        val message=JSONObject().put("type","Message").put("target",StreamingSessionMessages.GAME_TIME).put("id","time-request")
            .put("content",JSONObject().put("private-time-data","private-user"))
        assertTrue(messages.receive(message).replies.isEmpty());assertFalse(messages.diagnostics().contains("private-user"))
        val result=messages.receive(message.put("type","TransactionStart"))
        assertEquals("ReceiverCancel",json(result.replies.single()).getString("type"));assertNull(result.disconnect)
    }
    @Test fun malformedEnvelopesAndCorrelationVectorsCannotEchoPayloadsOrCreateInvalidReplies() {
        val messages=StreamingSessionMessages()
        for(id in listOf("", "x".repeat(129),"bad\nid")) {
            assertTrue(messages.receive(JSONObject().put("type","TransactionStart").put("target","/unknown").put("id",id)).replies.isEmpty())
        }
        for(target in listOf("https://private.example", "/"+"x".repeat(300),"/bad\ntarget")) {
            assertTrue(messages.receive(JSONObject().put("type","TransactionStart").put("target",target).put("id","id")).replies.isEmpty())
        }
        for(cv in listOf("private-bearer", "x".repeat(500),"AAAAAAAAAAAAAAAAAAAAAA.9999999999")) {
            val reply=messages.receive(JSONObject().put("type","Message").put("target","/unknown").put("id","id").put("cv",cv)).replies.single()
            assertTrue(json(reply).getString("cv").matches(Regex("[A-Za-z0-9+/]{22}\\.0")))
        }
    }
    @Test fun theProtocolTranscriptIsBoundedAndCannotRecordUnknownTargetsOrBodyValues() {
        var now=100L;val messages=StreamingSessionMessages { now }
        repeat(100) { i ->
            now+=10
            val result=messages.receive(JSONObject().put("type","Message").put("target","/private-account-$i").put("id","private-id-$i")
                .put("content","private-body"))
            messages.sent(result.replies.single())
        }
        val text=messages.diagnostics()
        assertTrue(text.length<1000);assertTrue(text.contains("rx=100; reply_queued=100; reply_sent=100"))
        assertEquals(8,text.substringAfter("RX_MESSAGES/").split('>').size)
        listOf("private-account","private-id","private-body").forEach { assertFalse(text.contains(it)) }
    }

}
