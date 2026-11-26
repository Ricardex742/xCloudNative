package com.xcloud.tclnative.webrtc

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

data class GamepadState(
    val buttons: Int = 0,
    val leftX: Float = 0f, val leftY: Float = 0f,
    val rightX: Float = 0f, val rightY: Float = 0f,
    val leftTrigger: Float = 0f, val rightTrigger: Float = 0f
)

/** Common gamepad report in the negotiated input/1.0 channel (little-endian header). */
object GamepadPacket {
    fun metadata(timestampMs: Double, maxTouchPoints: Int = 0): ByteArray =
        header(15, 8, 0, timestampMs).put(maxTouchPoints.coerceIn(0,10).toByte()).array()
    /** One all-released state for the logical touch slot, with both physicality masks zero. */
    fun neutralTouchSlot(sequence: Int,timestampMs: Double): ByteArray =
        // allocate() zeroes the 23-byte slot payload: index 0, no buttons, axes, triggers or action masks.
        header(38,2,sequence,timestampMs).put(1).array()
    fun encode(state: GamepadState, sequence: Int, timestampMs: Double): ByteArray = encode(mapOf(0 to state),sequence,timestampMs)
    fun encode(states: Map<Int,GamepadState>, sequence: Int, timestampMs: Double,
               pointers: List<NativePointer> = emptyList(), width: Int = 1920, height: Int = 1080,
               keys: List<NativeKey> = emptyList(), mice: List<NativeMouse> = emptyList(),
               officialTouchSlots: Int = 0): ByteArray {
        require(states.size<=4 && states.keys.all { it in 0..3 } && pointers.size<=10)
        require(keys.size<=32 && mice.size<=16 && keys.all { it.code in 1..255 })
        val type=(if(states.isEmpty()) 0 else 2) or (if(pointers.isEmpty()) 0 else 4) or
            (if(mice.isEmpty()) 0 else 32) or (if(keys.isEmpty()) 0 else 64)
        val out=header(14+(if(states.isEmpty()) 0 else 1+23*states.size)+(if(pointers.isEmpty()) 0 else 2+20*pointers.size)+
            (if(mice.isEmpty()) 0 else 1+18*mice.size)+(if(keys.isEmpty()) 0 else 1+3*keys.size),type,sequence,timestampMs)
        if(states.isNotEmpty()) {
            out.put(states.size.toByte())
            for (index in 0..3) {
                val state = states[index] ?: continue
                out.put(index.toByte()).putShort(state.buttons.toShort())
                    .putShort(axis(state.leftX)).putShort(axis(-state.leftY))
                    .putShort(axis(state.rightX)).putShort(axis(-state.rightY))
                    .putShort(trigger(state.leftTrigger)).putShort(trigger(state.rightTrigger))
                if (officialTouchSlots and (1 shl index) != 0) {
                    // Touch layouts set the active control bits in the virtual mask.
                    // The SDK serializes both physicality fields as little-endian uint32.
                    out.putInt(0).putInt(virtualPhysicality(state))
                } else {
                    // Keep the compatibility fields used by the existing controller paths.
                    out.putInt(1).order(ByteOrder.BIG_ENDIAN).putInt(1).order(ByteOrder.LITTLE_ENDIAN)
                }
            }
        }
        if(pointers.isNotEmpty()) {
            out.put(1).put(pointers.size.toByte())
            pointers.forEach { p ->
                // Match DataView's integer conversion in the official reliable input serializer.
                out.putShort((p.height.coerceIn(0f,1f)*height).toInt().coerceIn(0,65535).toShort())
                    .putShort((p.width.coerceIn(0f,1f)*width).toInt().coerceIn(0,65535).toShort())
                    .put((if(p.type==2) 0 else (p.pressure.coerceIn(0f,1f)*255).toInt()).toByte())
                    .putShort(0).putInt(p.id)
                    .putInt((p.x.coerceIn(0f,1f)*width.coerceAtLeast(1)).toInt())
                    .putInt((p.y.coerceIn(0f,1f)*height.coerceAtLeast(1)).toInt()).put(p.type.toByte())
            }
        }
        if(mice.isNotEmpty()) {
            out.put(mice.size.toByte())
            mice.forEach { out.putInt(it.x).putInt(it.y).putInt(it.wheelX).putInt(it.wheelY).put((it.buttons and 31).toByte()).put(0) }
        }
        if(keys.isNotEmpty()) {
            out.put(keys.size.toByte())
            keys.forEach { out.put(2).put((if(it.pressed) 1 else 0).toByte()).put(it.code.toByte()) }
        }
        return out.array()
    }
    internal fun virtualPhysicality(state: GamepadState): Int {
        val buttons = state.buttons
        var mask = (buttons and 0x0f00) ushr 8
        mask = mask or ((buttons and 0x000c) shl 2) or ((buttons and 0xc000) ushr 8) or
            ((buttons and 0x3000) ushr 4) or ((buttons and 0x0002) shl 9) or ((buttons and 0x00f0) shl 8)
        if (trigger(state.leftTrigger) != 0.toShort()) mask = mask or 0x10000
        if (trigger(state.rightTrigger) != 0.toShort()) mask = mask or 0x20000
        if (axis(state.leftX) != 0.toShort() || axis(state.leftY) != 0.toShort()) mask = mask or 0xc0000
        if (axis(state.rightX) != 0.toShort() || axis(state.rightY) != 0.toShort()) mask = mask or 0x300000
        return mask
    }
    private fun header(size: Int, type: Int, sequence: Int, time: Double) = ByteBuffer.allocate(size)
        .order(ByteOrder.LITTLE_ENDIAN).putShort(type.toShort()).putInt(sequence).putDouble(time)
    private fun axis(value: Float) = (if (value.isFinite()) value.coerceIn(-1f, 1f) else 0f)
        .times(32767).roundToInt().toShort()
    private fun trigger(value: Float) = (if (value.isFinite()) value.coerceIn(0f, 1f) else 0f)
        .times(65535).toInt().toShort()
}
