package com.xcloud.tclnative.webrtc

import org.json.JSONObject

/** One transport-local reset/registration cycle; never synthesizes a button or pointer event. */
class GamepadStartupSync(
    private val enabled: Boolean = true,
    private val clockMs: () -> Long = { System.nanoTime()/1_000_000 }
) {
    private var phase="NOT_STARTED"
    private var startedAt=0L
    private var removal: ChannelMessage?=null
    private var addition: ChannelMessage?=null
    private var removed=false
    private var added=false
    private var addedAfterMs: Long?=null
    private var closed=false
    val registered: Boolean @Synchronized get()=added

    @Synchronized fun begin(): ChannelMessage {
        check(phase=="NOT_STARTED" && !closed)
        startedAt=clockMs()
        return if(enabled) {
            phase="WAITING"
            change(false).also { removal=it }
        } else {
            phase="QUEUED"
            change(true).also { addition=it }
        }
    }
    @Synchronized fun remainingMs(): Long? = if(!closed && phase=="WAITING" && removed)
        (DELAY_MS-(clockMs()-startedAt).coerceAtLeast(0)).coerceAtLeast(0) else null

    @Synchronized fun due(): List<ChannelMessage> {
        if(remainingMs()!=0L) return emptyList()
        phase="QUEUED"
        return listOf(change(true).also { addition=it })
    }
    @Synchronized fun sent(message: ChannelMessage) {
        if(closed) return
        if(message===removal) removed=true
        if(message===addition && !added) {
            added=true;phase="ADDED"
            addedAfterMs=(clockMs()-startedAt).coerceAtLeast(0)
        }
    }
    @Synchronized fun close() { closed=true;if(!added) phase="CANCELLED" }
    @Synchronized fun diagnostics()="GAMEPAD_SYNC/$phase; removed=$removed; added=$added; added_after=${addedAfterMs?.let { "${it}ms" } ?: "n/d"}"
    private fun change(added: Boolean)=ChannelHandshake.encode("control",JSONObject()
        .put("message","gamepadChanged").put("gamepadIndex",0).put("wasAdded",added))
    companion object { const val DELAY_MS=500L }
}
