package com.xcloud.tclnative

import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.hardware.input.InputManager
import android.os.Looper
import android.os.SystemClock
import android.util.SparseArray
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.xcloud.tclnative.settings.TouchMode
import com.xcloud.tclnative.signaling.GameTitle
import com.xcloud.tclnative.touch.*
import com.xcloud.tclnative.ui.GameTouchView
import com.xcloud.tclnative.ui.MainActivity
import com.xcloud.tclnative.ui.dashboard.*
import com.xcloud.tclnative.webrtc.GamepadState
import com.xcloud.tclnative.webrtc.ControllerRouter
import com.xcloud.tclnative.webrtc.TransmissionRecoveryState
import com.xcloud.tclnative.webrtc.VirtualGamepad
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowInputDevice
import org.robolectric.util.ReflectionHelpers
import java.time.Duration
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],qualifiers="w1280dp-h720dp-land-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class QuickControlsUiTest {
    private fun event(view: View,action: Int,x: Float,y: Float) {
        val now=SystemClock.uptimeMillis()
        MotionEvent.obtain(now,now,action,x,y,0).let { view.onTouchEvent(it);it.recycle() }
    }
    private fun idle(seconds: Long)=shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(seconds))

    @Test @Config(qualifiers="w1280dp-h720dp-land-finger-mdpi")
    fun retryKeepsTheMenuReachableByTouchAndControllerWithoutSendingGameInput() {
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity=controller.get()
        shadowOf(activity.packageManager).setSystemFeature(PackageManager.FEATURE_TOUCHSCREEN,true)
        val manager=ReflectionHelpers.callStaticMethod<InputManager>(InputManager::class.java,"getInstance")
        val previous=ReflectionHelpers.getField<SparseArray<InputDevice>?>(manager,"mInputDevices")
        val touch=ShadowInputDevice.makeInputDeviceNamed("Touchscreen fixture")
        ReflectionHelpers.setField(touch,"mId",1)
        ReflectionHelpers.setField(touch,"mSources",InputDevice.SOURCE_TOUCHSCREEN)
        ReflectionHelpers.setField(manager,"mInputDevices",SparseArray<InputDevice>().apply { put(1,touch) })
        try {
            val recovery=TransmissionRecoveryState().apply { interrupted(0) }
            val view=prepareMenuSession(activity,recovery)
            val interrupted=MainActivity::class.java.getDeclaredMethod("presentTransmissionInterruption").apply { isAccessible=true }
            interrupted.invoke(activity)
            assertEquals(View.VISIBLE,view.visibility)
            assertFalse(view.controlsVisible)
            val anchor=view.getChildAt(0)
            assertEquals(View.VISIBLE,anchor.visibility)
            event(view,MotionEvent.ACTION_DOWN,anchor.left+anchor.width/2f,anchor.top+anchor.height/2f)
            event(view,MotionEvent.ACTION_UP,anchor.left+anchor.width/2f,anchor.top+anchor.height/2f)
            assertTrue(ReflectionHelpers.getField<android.app.Dialog>(activity,"gameMenuDialog").isShowing)
            ReflectionHelpers.getField<android.app.Dialog>(activity,"gameMenuDialog").dismiss()
            idle(0)
            assertEquals(View.VISIBLE,anchor.visibility)

            controller.windowFocusChanged(true)
            assertTrue(activity.dispatchKeyEvent(gamepad(17,KeyEvent.KEYCODE_BUTTON_START)))
            assertTrue(activity.dispatchKeyEvent(gamepad(17,KeyEvent.KEYCODE_BUTTON_SELECT)))
            assertTrue(ReflectionHelpers.getField<android.app.Dialog>(activity,"gameMenuDialog").isShowing)
            ReflectionHelpers.getField<android.app.Dialog>(activity,"gameMenuDialog").dismiss()
            idle(0)
        } finally {
            controller.pause().stop().destroy()
            ReflectionHelpers.setField(manager,"mInputDevices",previous)
        }
    }

    @Test fun aPadBehindReservedTouchPlayerCanOpenTheLocalMenu() {
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity=controller.get()
        prepareMenuSession(activity,TransmissionRecoveryState())
        val router=ReflectionHelpers.getField<ControllerRouter>(activity,"controllers")
        router.beginSession(true,true)
        assertEquals(1,router.slot(17))
        val dispatch=MainActivity::class.java.getDeclaredMethod("dispatchGameplayButton",KeyEvent::class.java).apply { isAccessible=true }
        dispatch.invoke(activity,gamepad(17,KeyEvent.KEYCODE_BUTTON_START))
        assertNull(ReflectionHelpers.getField<android.app.Dialog?>(activity,"gameMenuDialog"))
        dispatch.invoke(activity,gamepad(17,KeyEvent.KEYCODE_BUTTON_SELECT))
        assertTrue(ShadowDialog.getLatestDialog().isShowing)
        assertEquals(GamepadState(),router.physical(1))
        ShadowDialog.getLatestDialog().dismiss()
        idle(0)
        controller.pause().stop().destroy()
    }

    private fun prepareMenuSession(activity: MainActivity,recovery: TransmissionRecoveryState): GameTouchView {
        val game=GameTitle("demo","Demo")
        val reload: (Int?) -> Unit = {}
        ReflectionHelpers.setField(activity,"activeGame",game)
        ReflectionHelpers.setField(activity,"transmissionRecovery",recovery)
        ReflectionHelpers.setField(activity,"reloadTransmission",reload)
        ReflectionHelpers.getField<View>(activity,"controlPanel").visibility=View.GONE
        val view=ReflectionHelpers.getField<GameTouchView>(activity,"touchView")
        view.configure(TouchMode.OFF,game)
        view.measure(View.MeasureSpec.makeMeasureSpec(1280,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(720,View.MeasureSpec.EXACTLY))
        view.layout(0,0,1280,720)
        return view
    }

    private fun gamepad(device: Int,code: Int) = KeyEvent(0,0,KeyEvent.ACTION_DOWN,code,0,0,device,0,0,InputDevice.SOURCE_GAMEPAD)
    @Test fun idleHidePreservesHeldButtonsAndConsumesOnlyTheRevealingGesture() {
        val activity=Robolectric.buildActivity(Activity::class.java).setup()
        var state=GamepadState();var menus=0
        val view=GameTouchView(activity.get(),{state=it},{fail("HUD emitted native input")},{_,_->null},{menus++})
        activity.get().setContentView(view)
        view.layout(0,0,1280,720)
        view.configure(TouchMode.VIRTUAL,GameTitle("test","Test"),HudSettings(autoHide=HudAutoHide.IDLE))
        view.onWindowFocusChanged(true)
        val a=VirtualGamepad(1280,720).controls.single { it.id=="a" }
        event(view,MotionEvent.ACTION_DOWN,a.x,a.y)
        assertNotEquals(0,state.buttons)
        idle(12);assertTrue(view.controlsVisible);assertNotEquals(0,state.buttons)
        event(view,MotionEvent.ACTION_UP,a.x,a.y)
        assertEquals(GamepadState(),state)
        idle(9);assertTrue(view.controlsVisible)
        idle(1);assertFalse(view.controlsVisible)
        event(view,MotionEvent.ACTION_DOWN,a.x,a.y);event(view,MotionEvent.ACTION_UP,a.x,a.y)
        assertTrue(view.controlsVisible);assertEquals(GamepadState(),state)
        event(view,MotionEvent.ACTION_DOWN,a.x,a.y);assertNotEquals(0,state.buttons)
        event(view,MotionEvent.ACTION_UP,a.x,a.y);idle(10)
        assertFalse(view.controlsVisible)
        view.measure(View.MeasureSpec.makeMeasureSpec(1280,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(720,View.MeasureSpec.EXACTLY))
        view.layout(0,0,1280,720)
        val menu=view.getChildAt(0)
        val x=menu.left+menu.width/2f;val y=menu.top+menu.height/2f
        event(view,MotionEvent.ACTION_DOWN,x,y);event(view,MotionEvent.ACTION_UP,x,y)
        assertEquals(1,menus);assertFalse(view.controlsVisible)
        activity.pause().stop().destroy()
    }
    @Test fun defaultNeverHidesPhysicalRuleIgnoresNoiseAndEditorAndFocusPauseIdleTimer() {
        val activity=Robolectric.buildActivity(Activity::class.java).setup()
        val view=GameTouchView(activity.get(),{},{},{_,_->null},{})
        activity.get().setContentView(view);view.layout(0,0,1280,720)
        view.configure(TouchMode.VIRTUAL,GameTitle("test","Test"));view.onWindowFocusChanged(true)
        view.physicalInput(GamepadState(buttons=1));idle(20);assertTrue(view.controlsVisible)
        view.applyHud(HudSettings(autoHide=HudAutoHide.PHYSICAL))
        view.physicalInput(GamepadState(leftX=.05f));assertTrue(view.controlsVisible)
        view.physicalInput(GamepadState(buttons=1));assertFalse(view.controlsVisible)
        view.applyHud(HudSettings(autoHide=HudAutoHide.IDLE));view.beginEditing()
        idle(20);assertTrue(view.controlsVisible)
        view.cancelEditing();view.onWindowFocusChanged(false);idle(20);assertTrue(view.controlsVisible)
        view.onWindowFocusChanged(true);idle(10);assertFalse(view.controlsVisible)
        view.configure(TouchMode.NATIVE,GameTitle("test","Test"),HudSettings(autoHide=HudAutoHide.BOTH))
        val pointers=mutableListOf<Int>()
        val native=GameTouchView(activity.get(),{},{pointers+=it.map { p->p.type }},{x,y->x to y},{})
        activity.get().setContentView(native);native.layout(0,0,1280,720)
        native.configure(TouchMode.NATIVE,GameTitle("test","Test"),HudSettings(autoHide=HudAutoHide.BOTH))
        native.onWindowFocusChanged(true);idle(20);native.physicalInput(GamepadState(buttons=1))
        event(native,MotionEvent.ACTION_DOWN,900f,500f);event(native,MotionEvent.ACTION_UP,900f,500f)
        assertEquals(listOf(1,2),pointers)
        activity.pause().stop().destroy()
    }
    @Test fun autoHidePersistsPerGameAndUnknownVersionsDefaultToOff() {
        val activity=Robolectric.buildActivity(Activity::class.java).setup()
        val store=GameHudStore(activity.get())
        store.save("hide",HudSettings(autoHide=HudAutoHide.BOTH))
        assertEquals(HudAutoHide.BOTH,GameHudStore(activity.get()).read("hide").autoHide)
        assertEquals(HudAutoHide.OFF,store.read("other").autoHide)
        activity.get().getSharedPreferences("hud_"+com.xcloud.tclnative.catalog.GameLibrary.accountKey("future"),0)
            .edit().putString("layout","""{"autoHide":"FUTURE"}""").commit()
        assertEquals(HudAutoHide.OFF,store.read("future").autoHide)
        activity.pause().stop().destroy()
    }
    @Test fun quickMenuExposesActionsWithoutEnteringHudSettings() {
        val activity=Robolectric.buildActivity(Activity::class.java).setup()
        var toggles=0;var native=0
        fun nodes(view: View): List<View> = listOf(view)+if(view is ViewGroup)
            (0 until view.childCount).flatMap { nodes(view.getChildAt(it)) } else emptyList()
        fun open()=GameMenuPanel.show(activity.get(),{},{},{},{},hudToggle={toggles++},hudVisible=true,
            nativeTouch={native++})
        var panel=open()
        panel.panel.measure(View.MeasureSpec.makeMeasureSpec(1050,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(648,View.MeasureSpec.AT_MOST))
        panel.panel.layout(0,0,panel.panel.measuredWidth,panel.panel.measuredHeight)
        val bitmap=Bitmap.createBitmap(panel.panel.width,panel.panel.height,Bitmap.Config.ARGB_8888);panel.panel.draw(Canvas(bitmap))
        File("build/reports/social/quick-controls.png").apply { parentFile!!.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
        nodes(panel.panel).filterIsInstance<TextView>().single { it.text.toString()=="Ocultar controle virtual" }.performClick()
        panel=open();nodes(panel.panel).filterIsInstance<TextView>().single { it.text.toString()=="Toque nativo e controle" }.performClick()
        assertEquals(1,toggles);assertEquals(1,native)
        val unavailable=GameMenuPanel.show(activity.get(),{},{},{},{})
        assertFalse(nodes(unavailable.panel).filterIsInstance<TextView>().any { it.text.toString()=="Toque nativo e controle" })
        unavailable.dismiss();activity.pause().stop().destroy()
    }
}
