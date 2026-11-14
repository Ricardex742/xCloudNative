package com.xcloud.tclnative.webrtc

import com.xcloud.tclnative.settings.ControllerProfile
import java.util.Locale
import kotlin.math.abs

/** Bounded app-side observations. Never infer RF latency or include Android descriptors/USB paths. */
class ControllerPathTrace {
    private data class Sample(val source: ControllerSource?,var changes: Int,val invertLeft: Boolean,val invertRight: Boolean,
        var left: Pair<Float,Float>?=null,var right: Pair<Float,Float>?=null)
    private val slots=arrayOfNulls<Sample>(4)
    @Synchronized fun clear() { slots.fill(null) }
    @Synchronized fun record(slot: Int,source: ControllerSource?,raw: GamepadState,effective: GamepadState,profile: ControllerProfile?) {
        require(slot in 0..3)
        val previous=slots[slot]
        val invertLeft=profile?.invertLeftY ?: false;val invertRight=profile?.invertRightY ?: false
        val sample=if(previous?.source==source && previous!=null && previous.invertLeft==invertLeft && previous.invertRight==invertRight)
            previous else Sample(source,(previous?.changes ?: -1)+1,invertLeft,invertRight).also { slots[slot]=it }
        if(abs(raw.leftY)>.1f) sample.left=raw.leftY to effective.leftY
        if(abs(raw.rightY)>.1f) sample.right=raw.rightY to effective.rightY
    }
    @Synchronized fun diagnostics(): String = slots.mapIndexedNotNull { index,sample -> sample?.let {
        val source=when(val s=it.source) {
            is ControllerSource.Usb -> "USB %04x:%04x/i%d".format(s.vendor,s.product,s.intf)
            is ControllerSource.Android -> "Android id=${s.deviceId}"
            null -> "virtual"
        }
        fun y(value: Pair<Float,Float>?)=value?.let { v -> String.format(Locale.US,"%.3f/%.3f",v.first,v.second) } ?: "n/d"
        "Input[$index]: $source; changes=${it.changes}; invertY=${it.invertLeft}/${it.invertRight}; lastY(app/profile)=${y(it.left)};${y(it.right)}"
    } }.joinToString("\n")
}
