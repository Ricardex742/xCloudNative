package com.xcloud.tclnative.webrtc

import org.json.JSONObject
import okio.ByteString.Companion.toByteString
import java.io.IOException
import java.nio.ByteBuffer
import java.util.UUID

enum class XboxDisconnectReason(val wire: String,val terminal: Boolean=true) {
    NEW_SESSION("KickByNewSession"),CLOSED_GAME("KickForClosedGame"),IDLE("KickForBeingIdle"),SIGN_OUT("KickForSignOut"),
    SHUTDOWN("KickForServerShutdown"),STOP("KickForStopCommand"),APP_ERROR("KickForAppError"),
    NO_CLIENT("KickForNoClientConnection"),OUT_OF_TIME("KickForOutOfStreamingTime"),IDLE_WARNING("WarningForBeingIdle",false)
}
data class XboxServerDisconnect(val reason: XboxDisconnectReason,val hresult: Int?=null)
class XboxServerDisconnectException(val notice: XboxServerDisconnect): IOException("SESSION_STATE: ${notice.reason.wire}")
data class SessionMessageResult(val replies: List<ChannelMessage> = emptyList(),val disconnect: XboxServerDisconnect?=null)

/** SDK title activation and server lifetime messages. No game input is synthesized. */
class StreamingSessionMessages(clockMs: () -> Long={ System.nanoTime()/1_000_000 }) {
    private val trace=SessionMessageTrace(clockMs)
    private var activationId: String?=null
    private var activation="NOT_SENT"
    private var disconnect="NONE"
    private var hresult: Int?=null
    @Synchronized fun activateTitle(): ChannelMessage {
        val id=UUID.randomUUID().toString();activationId=id;activation="QUEUED"
        return ChannelHandshake.encode("message",JSONObject().put("type","TransactionStart").put("target",TITLE_CONSTRAIN)
            .put("id",id).put("cv",correlationVector()).put("content",JSONObject().put("constrain",false).toString()))
    }
    @Synchronized fun sent(message: ChannelMessage) {
        if(message.channel!="message" || message.binary || message.bytes.size>8192) return
        val json=runCatching { JSONObject(String(message.bytes,Charsets.UTF_8)) }.getOrNull() ?: return
        if(activation=="QUEUED" && json.optString("id")==activationId && json.optString("type")=="TransactionStart" && json.optString("target")==TITLE_CONSTRAIN) activation="SENT"
        if(json.optString("type") in REPLIES) trace.sent()
    }
    @Synchronized fun receive(json: JSONObject): SessionMessageResult {
        val type=json.optString("type")
        val id=(json.opt("id") as? String)?.takeIf { it.isNotBlank() && it.length<=128 && it.none(Char::isISOControl) }
        if(id!=null && id==activationId) {
            activation=when(type) { "TransactionComplete" -> "CONFIRMED";"Unhandled" -> "UNSUPPORTED";"Error","ReceiverCancel" -> "REJECTED";else -> activation }
        }
        val target=(json.opt("target") as? String)?.takeIf { it.startsWith('/') && it.length<=256 && it.none(Char::isISOControl) }
        trace.receive(type,target,id!=null && id==activationId)
        // Replies are never answered with another reply. In particular, do not loop on Unhandled/Error.
        if(type!="Message" && type!="TransactionStart" || target==null) return SessionMessageResult()
        if(target==TITLE_INFO || target==GAME_TIME) {
            if(type=="Message") {
                if(target==TITLE_INFO) trace.title(content(json))
                return SessionMessageResult()
            }
            // The SDK's passive TitleInfo/GameTime handlers cancel unexpected transactions.
            return if(id!=null) reply("ReceiverCancel",id,json) else SessionMessageResult()
        }
        if(id==null) return SessionMessageResult()
        if(target!=SERVER_DISCONNECT) return reply("Unhandled",id,json,target)
        if(type!="TransactionStart") return SessionMessageResult()
        val body=content(json)
        val reason=XboxDisconnectReason.entries.firstOrNull { it.wire==body?.optString("reason") }
        val rawHr=body?.opt("hr")
        val hr=(rawHr as? Number)?.takeIf { it is Int || it is Long }?.toLong()?.takeIf { it in Int.MIN_VALUE.toLong()..0xffffffffL }?.toInt()
        disconnect=reason?.wire ?: "OTHER";hresult=hr
        // Complete only this known transaction. Its opaque id exists only in the outgoing envelope.
        return reply("TransactionComplete",id,json,complete=true).copy(disconnect=reason?.let { XboxServerDisconnect(it,hr) })
    }
    private fun content(json: JSONObject): JSONObject?=runCatching {
        when(val raw=json.opt("content")) { is JSONObject -> raw;is String -> if(raw.length<=8192) JSONObject(raw) else null;else -> null }
    }.getOrNull()
    private fun reply(type: String,id: String,request: JSONObject,target: String?=null,complete: Boolean=false): SessionMessageResult {
        val body=JSONObject().put("type",type).put("id",id).put("cv",replyVector(request.optString("cv"),type!="Unhandled"))
        if(target!=null) body.put("target",target)
        if(complete) body.put("content","")
        trace.prepare()
        return SessionMessageResult(listOf(ChannelHandshake.encode("message",body)))
    }
    @Synchronized fun diagnostics()="TITLE_ACTIVATION/$activation\nSERVER_DISCONNECT/$disconnect"+
        (hresult?.let { " • HRESULT 0x"+it.toUInt().toString(16) } ?: "")+"\n"+trace.diagnostics()
    companion object {
        private val REPLIES=setOf("Unhandled","ReceiverCancel","TransactionComplete")
        const val TITLE_INFO="/streaming/properties/titleinfo"
        const val GAME_TIME="/streaming/systemUi/messages/ShowGameTimeNotification"
        const val TITLE_CONSTRAIN="/streaming/title/constrain"
        const val SERVER_DISCONNECT="/streaming/sessionLifetimeManagement/serverInitiatedDisconnect"
        private fun replyVector(raw: String,extend: Boolean): String {
            if(raw.length>110 || !raw.matches(Regex("(?:[A-Za-z0-9+/]{16}|[A-Za-z0-9+/]{22})(?:\\.[0-9]{1,10}){1,8}"))) return correlationVector()
            if(raw.substringAfter('.').split('.').any { it.toLongOrNull()?.let { n -> n !in 0L..2147483647L } != false }) return correlationVector()
            if(extend) return "$raw.1"
            val counter=raw.substringAfterLast('.').toLongOrNull() ?: return correlationVector()
            return if(counter<2147483647L) raw.substringBeforeLast('.')+"."+(counter+1) else correlationVector()
        }
        private fun correlationVector()=UUID.randomUUID().let { id ->
            ByteBuffer.allocate(16).putLong(id.mostSignificantBits).putLong(id.leastSignificantBits).array().toByteString().base64().trimEnd('=')+".0"
        }
    }
}
