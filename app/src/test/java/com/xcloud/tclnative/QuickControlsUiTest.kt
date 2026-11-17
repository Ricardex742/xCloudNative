package com.xcloud.tclnative

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.xcloud.tclnative.settings.TouchMode
import com.xcloud.tclnative.signaling.GameTitle
import com.xcloud.tclnative.touch.*
import com.xcloud.tclnative.ui.GameTouchView
import com.xcloud.tclnative.ui.dashboard.*
import com.xcloud.tclnative.webrtc.GamepadState
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
        event(view,MotionEvent.ACTION_DOWN,640f,720*.055f);event(view,MotionEvent.ACTION_UP,640f,720*.055f)
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
        var toggles=0;var native=0;var selection=0
        fun nodes(view: View): List<View> = listOf(view)+if(view is ViewGroup)
            (0 until view.childCount).flatMap { nodes(view.getChildAt(it)) } else emptyList()
        fun open()=GameMenuPanel.show(activity.get(),{},{},{},{},hudToggle={toggles++},hudVisible=true,
            nativeTouch={native++},touchSelection={selection++})
        var panel=open()
        panel.panel.measure(View.MeasureSpec.makeMeasureSpec(1050,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(648,View.MeasureSpec.AT_MOST))
        panel.panel.layout(0,0,panel.panel.measuredWidth,panel.panel.measuredHeight)
        val bitmap=Bitmap.createBitmap(panel.panel.width,panel.panel.height,Bitmap.Config.ARGB_8888);panel.panel.draw(Canvas(bitmap))
        File("build/reports/social/quick-controls.png").apply { parentFile!!.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
        nodes(panel.panel).filterIsInstance<TextView>().single { it.text.toString()=="Ocultar controle virtual" }.performClick()
        panel=open();nodes(panel.panel).filterIsInstance<TextView>().single { it.text.toString()=="Usar toque nativo nesta partida" }.performClick()
        panel=open();nodes(panel.panel).filterIsInstance<TextView>().single { it.text.toString()=="Modo de toque da próxima partida" }.performClick()
        assertEquals(1,toggles);assertEquals(1,native);assertEquals(1,selection)
        val unavailable=GameMenuPanel.show(activity.get(),{},{},{},{})
        assertFalse(nodes(unavailable.panel).filterIsInstance<TextView>().any { it.text.toString()=="Usar toque nativo nesta partida" })
        unavailable.dismiss();activity.pause().stop().destroy()
    }
}
