package com.xcloud.tclnative.webrtc

import org.json.JSONObject
import java.util.ArrayDeque

/** Bounded protocol categories and declared title state. No payloads, IDs or target strings survive. */
internal class SessionMessageTrace(private val clockMs: () -> Long) {
    private var origin: Long?=null
    private var received=0L
    private var prepared=0L
    private var sent=0L
    private var titleState="UNDECLARED"
    private var titleFocus="UNDECLARED"
    private var titleUpdates=0L
    private val incoming=ArrayDeque<String>()
    private val titles=ArrayDeque<String>()
    private fun age(): Long {
        val now=clockMs();if(origin==null) origin=now
        return (now-(origin ?: now)).coerceAtLeast(0)
    }
    fun receive(type: String,target: String?,activation: Boolean) {
        received++
        val safeType=if(type in TYPES) type else "OTHER"
        add(incoming,"${age()}:$safeType/${if(activation) "TITLE_CONSTRAIN" else route(target)}",8)
    }
    fun title(content: JSONObject?) {
        titleUpdates++
        val raw=content?.opt("state")
        titleState=when(raw) {
            is Int -> STATES.getOrNull(raw) ?: "OTHER"
            is Long -> if(raw in 0L..4L) STATES[raw.toInt()] else "OTHER"
            is String -> raw.takeIf { it in STATES } ?: "OTHER"
            null -> "UNDECLARED"
            else -> "OTHER"
        }
        titleFocus=when(content?.opt("focused")) { true -> "true";false -> "false";else -> "UNDECLARED" }
        add(titles,"${age()}:$titleState/$titleFocus",4)
    }
    fun prepare() { prepared++ }
    fun sent() { sent++ }
    fun diagnostics()="TITLE_STATE/$titleState; focused=$titleFocus; updates=$titleUpdates\n"+
        "MESSAGE_DISPATCH/rx=$received; reply_queued=$prepared; reply_sent=$sent\n"+
        "TITLE_TRACE/"+titles.joinToString(">")+"\nRX_MESSAGES/"+incoming.joinToString(">")
    private fun add(queue: ArrayDeque<String>,value: String,limit: Int) { if(queue.size>=limit) queue.removeFirst();queue.addLast(value) }
    companion object {
        private val STATES=listOf("Closed","Inactive","Obscured","Visible","Active")
        private val TYPES=setOf("Message","TransactionStart","TransactionComplete","Unhandled","Error","ReceiverCancel","SenderCancel")
        private val TARGETS=mapOf(
            StreamingSessionMessages.TITLE_CONSTRAIN to "TITLE_CONSTRAIN",StreamingSessionMessages.SERVER_DISCONNECT to "SERVER_DISCONNECT",
            StreamingSessionMessages.TITLE_INFO to "TITLE_INFO",StreamingSessionMessages.GAME_TIME to "GAME_TIME",
            "/streaming/systemUi/messages/ShowMessageDialog" to "SYSTEM_DIALOG",
            "/streaming/systemUi/messages/ShowVirtualKeyboard" to "SYSTEM_KEYBOARD",
            "/streaming/systemUi/messages/ShowApplication" to "SYSTEM_APPLICATION",
            "/streaming/systemUi/messages/ShowPrivilegeBlocked" to "PRIVILEGE_BLOCK",
            "/streaming/systemUi/messages/ShowAdditionalContextForGame" to "GAME_CONTEXT",
            "/streaming/mouse/cursor" to "MOUSE_CURSOR")
        private fun route(target: String?): String {
            if(target==null) return "NONE"
            return TARGETS[target] ?: when {
                target.startsWith("/streaming/touchControls/") -> "TOUCH_CONTROLS"
                target.startsWith("/streaming/systemUi/") -> "SYSTEM_UI_OTHER"
                target.startsWith("/streaming/properties/") -> "PROPERTIES_OTHER"
                target.startsWith("/streaming/characteristics/") -> "CHARACTERISTICS_OTHER"
                else -> "OTHER"
            }
        }
    }
}
