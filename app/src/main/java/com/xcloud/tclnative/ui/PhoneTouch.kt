package com.xcloud.tclnative.ui

import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import com.xcloud.tclnative.ui.dashboard.BackgroundMotionPolicy

/** Keep Android's click, long-press and scroll handling while skipping focus-only taps. */
internal inline fun View.dispatchPhoneTouch(event: MotionEvent, dispatch: () -> Boolean): Boolean {
    if (event.actionMasked != MotionEvent.ACTION_UP ||
        !event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN) ||
        !isEnabled || !isClickable || !isFocusableInTouchMode || isFocused ||
        BackgroundMotionPolicy.television(context)
    ) return dispatch()

    // View.onTouchEvent can consume ACTION_UP by requesting focus instead of clicking.
    isFocusableInTouchMode = false
    return try {
        dispatch()
    } finally {
        isFocusableInTouchMode = true
    }
}
