package com.xcloud.tclnative.webrtc

import com.xcloud.tclnative.i18n.UiText
import org.json.JSONObject
import java.nio.ByteBuffer

/** Constant-size transport evidence. Retain only known states/types, never JSON bodies or IDs. */
class ChannelLifecycleTrace(private val nanoTime: () -> Long=System::nanoTime) {
    private val states=linkedMapOf("control" to "CONNECTING","input" to "CONNECTING","message" to "CONNECTING")
    private var openedAt: Long?=null
    private var firstCloseMs: Long?=null
    private var controlReceived=0L
    private var messageReceived=0L
    private var controlType="NONE"
    private var messageType="NONE"
    private var authorizationSent=false
    private var metadataSent=false
    private var handshakeAck=false
    private val gamepadOrder=java.util.ArrayDeque<String>()
    @Synchronized fun state(channel: String,state: String) {
        if(channel !in states || state !in setOf("CONNECTING","OPEN","CLOSING","CLOSED")) return
        states[channel]=state
        val now=nanoTime()
        if(state=="OPEN" && openedAt==null) openedAt=now
        if(state=="CLOSED" && firstCloseMs==null) firstCloseMs=openedAt?.let { (now-it).coerceAtLeast(0)/1_000_000 }
    }
    @Synchronized fun sent(message: ChannelMessage) {
        if(message.channel=="input" && message.binary) metadataSent=true
        if(message.channel=="control" && !message.binary && message.bytes.size<=MAX_JSON) {
            val json=runCatching { JSONObject(String(message.bytes,Charsets.UTF_8)) }.getOrNull() ?: return
            when(json.optString("message")) {
                "authorizationRequest" -> authorizationSent=true
                "gamepadChanged" -> {
                    val index=json.optInt("gamepadIndex",-1)
                    if(index !in 0..3 || json.opt("wasAdded") !is Boolean) return
                    if(gamepadOrder.size==8) gamepadOrder.removeFirst()
                    gamepadOrder.addLast("$index${if(json.getBoolean("wasAdded")) "+" else "-"}")
                }
            }
        }
    }
    @Synchronized fun received(channel: String,binary: Boolean,data: ByteBuffer) {
        if(channel!="control" && channel!="message") return
        val type=if(binary) "BINARY" else if(data.remaining()>MAX_JSON) "OVERSIZE" else runCatching {
            val bytes=ByteArray(data.remaining());data.duplicate().get(bytes)
            val json=JSONObject(String(bytes,Charsets.UTF_8).trimEnd('\u0000'))
            val candidate=if(channel=="control") json.optString("message") else json.optString("type")
            if(candidate in KNOWN_TYPES) candidate else "OTHER"
        }.getOrDefault("INVALID")
        if(channel=="control") { controlReceived++;controlType=type }
        else { messageReceived++;messageType=type;if(type=="HandshakeAck") handshakeAck=true }
    }
    @Synchronized fun diagnostics()=UiText.text("Canais: {0}; autorização enviada {1}; metadata enviada {2}; ack {3}; controle recebido {4}/{5}; mensagem recebida {6}/{7}; primeiro fechamento após {8} ms",
        UiText.nested(states.entries.joinToString(",") { "${it.key}=${it.value}" }),UiText.nested(authorizationSent),UiText.nested(metadataSent),UiText.nested(handshakeAck),
        UiText.nested(controlReceived),UiText.nested(controlType),UiText.nested(messageReceived),UiText.nested(messageType),UiText.nested(firstCloseMs?.toString() ?: "n/d"))+"\nGAMEPAD_ORDER/${gamepadOrder.joinToString(">").ifEmpty { "NONE" }}"
    companion object {
        private const val MAX_JSON=8192
        private val KNOWN_TYPES=setOf("Handshake","HandshakeAck","Message","Transaction","TransactionStart","TransactionComplete","Unhandled","ReceiverCancel","SenderCancel","Response","Error",
            "authorizationResponse","authorizationResult","authorizationRequest","error","sessionState","disconnect","close")
    }
}
