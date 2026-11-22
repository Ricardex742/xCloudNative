package com.xcloud.tclnative

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
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
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.io.File
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w640dp-h320dp-land-mdpi-finger")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GuideTouchControlsTest {
    @Test fun firstTouchesSendDirectionsAndFaceButtonsAndCloseReleasesTheLastPress() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val root = FrameLayout(activity)
        val reports = mutableListOf<Int>()
        lateinit var controls: GuideTouchControls
        controls = GuideTouchControls(activity, { reports += controls.buttons }, { controls.hide() })
        root.setBackgroundColor(0xff34505c.toInt())
        root.addView(controls, FrameLayout.LayoutParams(-1, -1))
        activity.setContentView(root)
        controls.setCutout(Rect(18, 6, 32, 24))
        controls.show()
        root.measure(View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 640, 320)
        val pad = controls.getChildAt(0) as FrameLayout
        val up = pad.getChildAt(0)
        val left = pad.getChildAt(1)
        val right = pad.getChildAt(2)
        val down = pad.getChildAt(3)
        val confirm = controls.getChildAt(1)
        val back = controls.getChildAt(2)
        val close = controls.getChildAt(3)
        fun tapControl(view: View) {
            val bounds = Rect(0, 0, view.width, view.height)
            controls.offsetDescendantRectToMyCoords(view, bounds)
            tap(controls, bounds.exactCenterX(), bounds.exactCenterY())
        }
        try {
            assertTrue(reports.isEmpty())
            assertTrue(pad.left > 18 && pad.right < controls.width / 2)
            assertTrue(controls.height - pad.bottom > 24)
            assertTrue(confirm.left > controls.width / 2)
            assertTrue(close.top > 6 && close.top < controls.height / 2)
            assertTrue(close.right == back.right && controls.width - close.right > 32)
            assertEquals(up.left, down.left)
            assertEquals(left.top, right.top)
            assertTrue(left.left < up.left && up.left < right.left)
            assertTrue(up.top < left.top && left.top < down.top)
            assertTrue(confirm.top > back.top)
            val backgroundTap = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 320f, 160f, 0)
            try { assertFalse(controls.dispatchTouchEvent(backgroundTap)) } finally { backgroundTap.recycle() }
            val preview = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(preview))
            File("build/reports/dashboard/guide-touch.png").apply { parentFile?.mkdirs() }.outputStream().use {
                preview.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            preview.recycle()
            for (button in listOf(left, up, down, right)) {
                assertTrue(button.width >= 48 && button.height >= 48)
                tapControl(button)
            }
            tapControl(confirm)
            tapControl(confirm)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
            assertEquals(listOf(1024, 0, 256, 0, 512, 0, 2048, 0, 16, 0, 16, 0), reports)
            tapControl(back)
            assertEquals("Button history: $reports", 32, controls.buttons)
            tapControl(close)
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
