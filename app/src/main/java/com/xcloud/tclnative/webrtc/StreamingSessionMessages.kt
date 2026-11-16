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
class StreamingSessionMessages {
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
        if(activation!="QUEUED" || message.channel!="message" || message.binary || message.bytes.size>8192) return
        val json=runCatching { JSONObject(String(message.bytes,Charsets.UTF_8)) }.getOrNull() ?: return
        if(json.optString("id")==activationId && json.optString("type")=="TransactionStart" && json.optString("target")==TITLE_CONSTRAIN) activation="SENT"
    }
    @Synchronized fun receive(json: JSONObject): SessionMessageResult {
        val type=json.optString("type")
        val id=(json.opt("id") as? String)?.takeIf { it.isNotBlank() && it.length<=128 && it.none(Char::isISOControl) }
        if(id!=null && id==activationId) {
            activation=when(type) { "TransactionComplete" -> "CONFIRMED";"Unhandled" -> "UNSUPPORTED";"Error","ReceiverCancel" -> "REJECTED";else -> activation }
        }
        if(type!="TransactionStart" || json.optString("target")!=SERVER_DISCONNECT || id==null) return SessionMessageResult()
        val content=runCatching {
            val raw=json.opt("content")
            if(raw is JSONObject) raw else if(raw is String && raw.length<=8192) JSONObject(raw) else null
        }.getOrNull()
        val reason=XboxDisconnectReason.entries.firstOrNull { it.wire==content?.optString("reason") }
        val rawHr=content?.opt("hr")
        val hr=(rawHr as? Number)?.takeIf { it is Int || it is Long }?.toLong()?.takeIf { it in Int.MIN_VALUE.toLong()..0xffffffffL }?.toInt()
        disconnect=reason?.wire ?: "OTHER";hresult=hr
        // Complete only this known transaction, echoing its opaque id without retaining/logging it.
        val ack=ChannelHandshake.encode("message",JSONObject().put("type","TransactionComplete").put("id",id).put("content","").put("cv",correlationVector()))
        return SessionMessageResult(listOf(ack),reason?.let { XboxServerDisconnect(it,hr) })
    }
    @Synchronized fun diagnostics()="TITLE_ACTIVATION/$activation\nSERVER_DISCONNECT/$disconnect"+(hresult?.let { " • HRESULT 0x"+it.toUInt().toString(16) } ?: "")
    companion object {
        const val TITLE_CONSTRAIN="/streaming/title/constrain"
        const val SERVER_DISCONNECT="/streaming/sessionLifetimeManagement/serverInitiatedDisconnect"
        private fun correlationVector()=UUID.randomUUID().let { id ->
            ByteBuffer.allocate(16).putLong(id.mostSignificantBits).putLong(id.leastSignificantBits).array().toByteString().base64().trimEnd('=')+".0"
        }
    }
}
