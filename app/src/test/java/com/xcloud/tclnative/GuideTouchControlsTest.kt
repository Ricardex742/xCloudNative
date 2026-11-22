package com.xcloud.tclnative

import android.app.Activity
import android.graphics.Rect
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.xcloud.tclnative.settings.TouchMode
import com.xcloud.tclnative.signaling.GameTitle
import com.xcloud.tclnative.ui.GameTouchView
import com.xcloud.tclnative.ui.GuideTouchControls
import com.xcloud.tclnative.webrtc.NativePointer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w640dp-h320dp-land-mdpi-finger")
@LooperMode(LooperMode.Mode.PAUSED)
class GuideTouchControlsTest {
    @Test fun firstTouchesSendDirectionsAndFaceButtonsAndCloseReleasesTheLastPress() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val root = FrameLayout(activity)
        val reports = mutableListOf<Int>()
        lateinit var controls: GuideTouchControls
        controls = GuideTouchControls(activity, { reports += controls.buttons }, { controls.hide() })
        root.addView(controls, FrameLayout.LayoutParams(-2, -2))
        activity.setContentView(root)
        controls.setCutout(Rect(0, 0, 32, 24))
        controls.show()
        root.measure(View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 640, 320)
        val directions = controls.getChildAt(0) as LinearLayout
        val actions = controls.getChildAt(1) as LinearLayout
        try {
            assertTrue(reports.isEmpty())
            val position = controls.layoutParams as FrameLayout.LayoutParams
            assertTrue(position.rightMargin > 32)
            assertTrue(position.bottomMargin > 24)
            for (index in 0 until directions.childCount) {
                val button = directions.getChildAt(index)
                assertTrue(button.width >= 48 && button.height >= 48)
                tap(button, button.width / 2f, button.height / 2f)
            }
            tap(actions.getChildAt(0), 20f, 20f)
            tap(actions.getChildAt(0), 20f, 20f)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
            assertEquals(listOf(1024, 0, 256, 0, 512, 0, 2048, 0, 16, 0, 16, 0), reports)
            tap(actions.getChildAt(1), 20f, 20f)
            assertEquals(32, controls.buttons)
            tap(actions.getChildAt(2), 20f, 20f)
            assertFalse(controls.active)
            assertEquals(0, controls.buttons)
            assertEquals(listOf(32, 0), reports.takeLast(2))
            val count = reports.size
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))
            assertEquals(count, reports.size)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun guideReturnsToTheSameNativeTouchModeAfterTitleFocusComesBack() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val pointers = mutableListOf<NativePointer>()
        val game = GameTouchView(activity, {}, { pointers += it }, { _, _ -> .3f to .4f }, {})
        lateinit var controls: GuideTouchControls
        controls = GuideTouchControls(activity, {}, { controls.hide(); game.suspendGameplayTouch(false) })
        activity.setContentView(game)
        game.configure(TouchMode.NATIVE, GameTitle("demo", "Demo"))
        game.layout(0, 0, 640, 320)
        try {
            motion(game, MotionEvent.ACTION_DOWN, 560f, 200f)
            assertEquals(listOf(1), pointers.map { it.type })
            game.suspendGameplayTouch(true)
            controls.show()
            assertEquals(listOf(1, 2), pointers.map { it.type })
            controls.onTitleFocusChanged(true)
            assertTrue(controls.active)
            tap(game, 560f, 200f)
            assertEquals(2, pointers.size)
            controls.onTitleFocusChanged(false)
            controls.onTitleFocusChanged(true)
            assertFalse(controls.active)
            tap(game, 560f, 200f)
            assertEquals(listOf(1, 2, 1, 2), pointers.map { it.type })
        } finally { controller.pause().stop().destroy() }
    }

    private fun tap(view: View, x: Float, y: Float) {
        motion(view, MotionEvent.ACTION_DOWN, x, y)
        motion(view, MotionEvent.ACTION_UP, x, y)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun motion(view: View, action: Int, x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(now, now, action, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        try { assertTrue(view.dispatchTouchEvent(event)) } finally { event.recycle() }
    }
}
