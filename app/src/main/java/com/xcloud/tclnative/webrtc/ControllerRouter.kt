package com.xcloud.tclnative.webrtc

import kotlin.math.abs

/** Slots remain stable until a device disconnects; input from different players is never merged. */
class ControllerRouter {
    private val devices=mutableMapOf<ControllerSource,Int>()
    private val states=Array(4) { GamepadState() }
    private val androidKeys = Array(4) { mutableMapOf<Pair<Int, Int>, Int>() }
    private val androidKeyMasks = IntArray(4)
    private val androidHatMasks = IntArray(4)
    var coOp=false
        private set
    private var virtual=GamepadState()
    private var xboxButtonPressed = false
    private var reserveTouch=false
    fun beginSession(enabled: Boolean, touchPlayerOne: Boolean = false) { devices.clear(); coOp=enabled; reserveTouch=enabled && touchPlayerOne; reset() }
    fun slot(deviceId: Int): Int? = slot(ControllerSource.Android(deviceId))
    fun slot(source: ControllerSource): Int? = devices[source] ?: ((if(reserveTouch) 1 else 0) until (if(coOp) 4 else 1))
        .firstOrNull { it !in devices.values }?.also {
            devices[source]=it
            // An unassigned slot may contain fallback directional input from a remote.
            resetSlot(it)
        }
    fun sourceFor(slot: Int): ControllerSource? = devices.entries.firstOrNull { it.value==slot }?.key
    fun existingSlot(source: ControllerSource): Int? = devices[source]
    fun deviceFor(slot: Int): Int? = (sourceFor(slot) as? ControllerSource.Android)?.deviceId
    fun remove(deviceId: Int): Int? = remove(ControllerSource.Android(deviceId))
    fun remove(source: ControllerSource): Int? = devices.remove(source)?.also(::resetSlot)
    fun attachUsb(source: ControllerSource.Usb,androidId: Int?,aliases: Set<Int> = emptySet()): Int? {
        val existing=devices[source]
        val previous=(aliases+listOfNotNull(androidId)).mapNotNull { remove(it) }
        val target=existing ?: previous.minOrNull()
        if(target!=null) { devices[source]=target;return target }
        return slot(source)
    }
    fun physical(slot: Int) = states[slot]
    fun update(slot: Int, state: GamepadState) { states[slot]=state }
    fun updateAndroidKey(slot: Int, deviceId: Int, keyCode: Int, mask: Int, down: Boolean): Boolean {
        val keys = androidKeys[slot]
        val key = deviceId to keyCode
        val changed = if (down) keys.put(key, mask) != mask else keys.remove(key) != null
        if (!changed) return false
        val previous = androidButtons(slot)
        androidKeyMasks[slot] = keys.values.fold(0, Int::or)
        states[slot] = states[slot].copy(buttons = (states[slot].buttons and previous.inv()) or androidButtons(slot))
        return true
    }
    fun updateAndroidMotion(slot: Int, axes: GamepadState) {
        val previous = androidButtons(slot)
        androidHatMasks[slot] = axes.buttons
        states[slot] = axes.copy(buttons = (states[slot].buttons and previous.inv()) or androidButtons(slot))
    }
    private fun androidButtons(slot: Int) = androidKeyMasks[slot] or androidHatMasks[slot]
    fun virtual(state: GamepadState) { virtual=state }
    fun xboxButton(pressed: Boolean) { xboxButtonPressed = pressed }
    fun combined(slot: Int, physicalOverride: GamepadState? = null): GamepadState {
        val physical = physicalOverride ?: states[slot]
        if (slot != 0) return physical
        val merged = merge(physical, virtual)
        return if (xboxButtonPressed) merged.copy(buttons = merged.buttons or XBOX_BUTTON) else merged
    }
    private fun resetSlot(slot: Int) {
        states[slot] = GamepadState()
        androidKeys[slot].clear()
        androidKeyMasks[slot] = 0
        androidHatMasks[slot] = 0
    }
    fun reset() {
        states.indices.forEach(::resetSlot)
        virtual = GamepadState()
        xboxButtonPressed = false
    }
    companion object {
        const val XBOX_BUTTON = 2
        fun merge(physical: GamepadState, virtual: GamepadState): GamepadState {
            fun axis(a: Float,b: Float) = if(abs(b)>abs(a)) b else a
            return physical.copy(buttons=physical.buttons or virtual.buttons,
                leftX=axis(physical.leftX,virtual.leftX),leftY=axis(physical.leftY,virtual.leftY),
                rightX=axis(physical.rightX,virtual.rightX),rightY=axis(physical.rightY,virtual.rightY),
                leftTrigger=maxOf(physical.leftTrigger,virtual.leftTrigger),rightTrigger=maxOf(physical.rightTrigger,virtual.rightTrigger))
        }
    }
}
