package com.xcloud.tclnative.webrtc

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent

internal object AndroidGamepadPolicy {
    fun supportsGamepad(source: Int): Boolean =
        source and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
            source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK

    fun canReserveTvSlot(device: InputDevice): Boolean {
        if (device.isVirtual || !supportsGamepad(device.sources)) return false
        if (device.getMotionRange(MotionEvent.AXIS_X, InputDevice.SOURCE_JOYSTICK) != null &&
            device.getMotionRange(MotionEvent.AXIS_Y, InputDevice.SOURCE_JOYSTICK) != null) return true
        // Some TV remotes advertise SOURCE_GAMEPAD and map OK and Back to A and B.
        return (device.hasKeys(KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BUTTON_X,
            KeyEvent.KEYCODE_BUTTON_Y, KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1,
            KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_BUTTON_SELECT)?.count { it } ?: 0) >= 3
    }

    fun confirmsGamepad(event: KeyEvent): Boolean = supportsGamepad(event.source) &&
        KeyEvent.isGamepadButton(event.keyCode) &&
        event.keyCode != KeyEvent.KEYCODE_BUTTON_A && event.keyCode != KeyEvent.KEYCODE_BUTTON_B
}
