package com.xcloud.tclnative.webrtc

import com.xcloud.tclnative.touch.OfficialTouchNotice
import org.json.JSONArray
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
data class XboxGameTime(val secondsLeft: Long?)
class XboxServerDisconnectException(val notice: XboxServerDisconnect): IOException("SESSION_STATE: ${notice.reason.wire}")
data class SessionMessageResult(val replies: List<ChannelMessage> = emptyList(),val disconnect: XboxServerDisconnect?=null,
                                val titleFocused: Boolean?=null,val gameTime: XboxGameTime?=null,
                                val touchLayout: OfficialTouchNotice?=null)

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
        if(json.optString("type")=="TransactionComplete") trace.sent()
    }
    @Synchronized fun receive(json: JSONObject): SessionMessageResult {
        val type=json.optString("type")
        val id=(json.opt("id") as? String)?.takeIf { it.isNotBlank() && it.length<=128 && it.none(Char::isISOControl) }
        if(id!=null && id==activationId) {
            activation=when(type) { "TransactionComplete" -> "CONFIRMED";"Unhandled" -> "UNSUPPORTED";"Error","ReceiverCancel" -> "REJECTED";else -> activation }
        }
        val target=(json.opt("target") as? String)?.takeIf { it.startsWith('/') && it.length<=256 && it.none(Char::isISOControl) }
        trace.receive(type,target,id!=null && id==activationId)
        // Observe notifications without changing the established session exchange.
        // Generic Unhandled replies require matching feature handlers and capability negotiation.
        if (type=="Message" && target==TITLE_INFO) {
            val title = content(json)
            trace.title(title)
            return SessionMessageResult(titleFocused = title?.opt("focused") as? Boolean)
        }
        if (type=="Message" && target==GAME_TIME) {
            return SessionMessageResult(gameTime=content(json)?.let(::gameTime))
        }
        if (type=="Message" && target?.startsWith("/streaming/touchcontrols/",ignoreCase=true)==true) {
            return SessionMessageResult(touchLayout=touchLayout(target.substringAfterLast('/'),json))
        }
        if(type!="TransactionStart" || target!=SERVER_DISCONNECT || id==null) return SessionMessageResult()
        val body=content(json)
        val reason=XboxDisconnectReason.entries.firstOrNull { it.wire==body?.optString("reason") }
        val rawHr=body?.opt("hr")
        val hr=(rawHr as? Number)?.takeIf { it is Int || it is Long }?.toLong()?.takeIf { it in Int.MIN_VALUE.toLong()..0xffffffffL }?.toInt()
        disconnect=reason?.wire ?: "OTHER";hresult=hr
        // Retain the established acknowledgement for this known transaction only.
        val ack=ChannelHandshake.encode("message",JSONObject().put("type","TransactionComplete").put("id",id).put("content","").put("cv",correlationVector()))
        trace.prepare()
        return SessionMessageResult(listOf(ack),reason?.let { XboxServerDisconnect(it,hr) })
    }
    private fun content(json: JSONObject): JSONObject?=runCatching {
        when(val raw=json.opt("content")) { is JSONObject -> raw;is String -> if(raw.length<=8192) JSONObject(raw) else null;else -> null }
    }.getOrNull()
    private fun touchLayout(route: String, json: JSONObject): OfficialTouchNotice? {
        fun patch(body: JSONObject?): String? = when(val value = body?.opt("patch")) {
            is String -> value
            is JSONArray -> value.toString()
            else -> null
        }?.takeIf { it.length<=8192 }
        fun validName(value: Any?): String? = (value as? String)?.trim()?.takeIf {
            it.length in 1..128 && it.none(Char::isISOControl)
        }
        return when {
            route.equals("hide",true) -> OfficialTouchNotice.Hide
            route.equals("showtitledefault",true) -> OfficialTouchNotice.ShowDefault
            route.equals("showlayout",true) -> validName(json.opt("content"))?.let { OfficialTouchNotice.Show(it) }
            route.equals("showlayoutv2",true) -> content(json)?.let { body ->
                validName(body.opt("layoutId"))?.let { OfficialTouchNotice.Show(it,patch(body)) }
            }
            route.equals("patchstate",true) -> patch(content(json))?.let { OfficialTouchNotice.Patch(it) }
            else -> null
        }
    }
    private fun gameTime(body: JSONObject): XboxGameTime? {
        val active=body.opt("IsTimerActive") as? Boolean ?: return null
        if(!active) return XboxGameTime(null)
        val seconds=when(val value=body.opt("SecondsLeft")) {
            is Int -> value.toLong()
            is Long -> value
            else -> return null
        }
        return if(seconds in 0L..86_400L) XboxGameTime(seconds) else null
    }
    @Synchronized fun diagnostics()="TITLE_ACTIVATION/$activation\nSERVER_DISCONNECT/$disconnect"+
        (hresult?.let { " • HRESULT 0x"+it.toUInt().toString(16) } ?: "")+"\n"+trace.diagnostics()
    companion object {
        const val TITLE_INFO="/streaming/properties/titleinfo"
        const val GAME_TIME="/streaming/systemUi/messages/ShowGameTimeNotification"
        const val TITLE_CONSTRAIN="/streaming/title/constrain"
        const val SERVER_DISCONNECT="/streaming/sessionLifetimeManagement/serverInitiatedDisconnect"
        private fun correlationVector()=UUID.randomUUID().let { id ->
            ByteBuffer.allocate(16).putLong(id.mostSignificantBits).putLong(id.leastSignificantBits).array().toByteString().base64().trimEnd('=')+".0"
        }
    }
}
