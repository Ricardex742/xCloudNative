package com.xcloud.tclnative.webrtc

/** Offer a user-selected HUD once, only after foreground playback spent time without server metadata. */
class NativeTouchReadiness(private val waitMs: Long=15_000) {
    private var waitingSince: Long?=null
    private var offered=false
    fun offer(now: Long,native: Boolean,ready: Boolean,foreground: Boolean): Boolean {
        if(ready || !native) { waitingSince=null;return false }
        if(!foreground) { waitingSince=null;return false }
        val since=waitingSince ?: now.also { waitingSince=it }
        if(offered || now<since || now-since<waitMs) return false
        offered=true;return true
    }
}
