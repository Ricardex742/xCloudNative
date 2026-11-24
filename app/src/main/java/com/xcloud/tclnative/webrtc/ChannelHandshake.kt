package com.xcloud.tclnative.webrtc

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class ChannelMessage(val channel: String, val bytes: ByteArray, val binary: Boolean = false)

/** Ordered protocol transitions; callbacks for the three channels can arrive in any order. */
class ChannelHandshake(private val nativeTouch: Boolean = false,
                       private val resolution: StreamResolution = StreamResolution.HD_1080_HQ,
                       private val synchronizeGamepad: Boolean = true,
                       clockMs: () -> Long = { System.nanoTime()/1_000_000 },
                       private val activateCloudTitle: Boolean = true) {
    val sessionMessages=StreamingSessionMessages()
    val gamepadSync=GamepadStartupSync(synchronizeGamepad,clockMs)
    private val opened = mutableSetOf<String>()
    private var acknowledged = false
    private var started = false
    @Volatile var displayDimensions: ClientDisplayDimensions? = null
    val ready: Boolean @Synchronized get() = started

    @Synchronized fun onOpen(channel: String): List<ChannelMessage> {
        if (!opened.add(channel)) return emptyList()
        val messages = mutableListOf<ChannelMessage>()
        if (channel == "message") messages += encode("message", JSONObject()
            .put("type", "Handshake").put("version", "messageV1").put("id", UUID.randomUUID().toString()).put("cv", "0"))
        messages += startIfReady()
        return messages
    }
    @Synchronized fun onHandshakeAck(): List<ChannelMessage> {
        acknowledged = true
        return startIfReady()
    }
    @Synchronized fun onGamepadSyncDue(): List<ChannelMessage> {
        val registration=gamepadSync.due()
        // Preserve registration before title activation when startup synchronization is delayed.
        return if(registration.isEmpty() || !activateCloudTitle) registration else registration+sessionMessages.activateTitle()
    }
    private fun startIfReady(): List<ChannelMessage> {
        if (started || !acknowledged || !opened.containsAll(listOf("message", "control", "input"))) return emptyList()
        started = true
        return listOf(encode("control", JSONObject().put("message", "authorizationRequest").put("accessKey", "4BDB3609-C1F1-4195-9B37-FEFF45DA8B8E")),
            // Reset the logical slot before the delayed registration, independently of user input.
            gamepadSync.begin(),
            // The server must learn maxTouchPoints before touchInputEnabled, as in the web client.
            ChannelMessage("input", GamepadPacket.metadata(0.0, if(nativeTouch) 10 else 0), true),
            config("/streaming/systemUi/configuration", JSONObject().put("version", JSONArray(listOf(0, 2, 0)))
                .put("systemUis", JSONArray().apply { if (activateCloudTitle) put(StreamingSessionMessages.GAME_TIME_UI_ID) })),
            config("/streaming/properties/clientappinstallidchanged", JSONObject().put("clientAppInstallId", UUID.randomUUID().toString())),
            config("/streaming/characteristics/orientationchanged", JSONObject().put("orientation", 0)),
            config("/streaming/characteristics/touchinputenabledchanged", JSONObject().put("touchInputEnabled", nativeTouch)),
            config("/streaming/characteristics/clientdevicecapabilities", JSONObject())
        ) + listOfNotNull(displayDimensions?.let(::dimensions)) +
            (if(synchronizeGamepad || !activateCloudTitle) emptyList() else listOf(sessionMessages.activateTitle())) + resolutionRequest(resolution)
    }
    private fun config(target: String, content: JSONObject) = encode("message", JSONObject()
        .put("type", "Message").put("target", target).put("id", UUID.randomUUID().toString()).put("cv", "")
        .put("content", content.toString()))

    companion object {
        /** Xbox web SDK: ControlChannel.sendUserRequestedResolutionUpdate, after connecting input. */
        fun resolutionRequest(resolution: StreamResolution) = encode("control",JSONObject()
            .put("message","userRequestedResolutionUpdate").put("resolutionAlias",resolution.resolutionAlias))
        fun dimensions(display: ClientDisplayDimensions) = encode("message",JSONObject().put("type","Message")
            .put("target","/streaming/characteristics/dimensionschanged").put("id",UUID.randomUUID().toString()).put("cv","")
            .put("content",display.content().toString()))
        fun encode(channel: String, json: JSONObject) = ChannelMessage(channel, json.toString().toByteArray(Charsets.UTF_8))
    }
}
