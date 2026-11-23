package com.xcloud.tclnative

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import com.xcloud.tclnative.settings.TouchMode
import com.xcloud.tclnative.signaling.GameTitle
import com.xcloud.tclnative.touch.*
import com.xcloud.tclnative.ui.GameTouchView
import com.xcloud.tclnative.ui.dashboard.*
import com.xcloud.tclnative.webrtc.*
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
import java.io.File
import java.io.ByteArrayOutputStream
import java.time.Duration
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],qualifiers="w1280dp-h720dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class GameHudUiTest {
    @Test fun xboxBundleRendersInTheNativeHudAndHideReleasesTheGamepad() {
        val output=ByteArrayOutputStream()
        val art=ByteArrayOutputStream()
        Bitmap.createBitmap(16,16,Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.RED)
            compress(Bitmap.CompressFormat.PNG,100,art)
            recycle()
        }
        val files=mapOf(
            "takxmanifest.json" to """{"assets":"assets","layouts":"layouts","defaultLayout":"Default","contextFile":"context.json"}""".toByteArray(),
            "context.json" to """{"state":{"show":false,"opacity":0.5}}""".toByteArray(),
            "layouts/neutral/Default.json" to """{"content":{"right":{"inner":[{"type":"button","action":"gamepadB"}]}}}""".toByteArray(),
            "layouts/pt/pt-BR/Default.json" to """
                {"definitions":{"face":{"type":"asset","value":"Action"}},"content":{"right":{"inner":[
                  {"type":"button","action":["gamepadA","rightTrigger"],
                   "visible":{"${'$'}ref":"../../context.json#/state/show"},
                   "styles":{
                     "default":{"faceImage":{"${'$'}ref":"#/definitions/face"}},
                     "idle":{"opacity":{"${'$'}ref":"../../context.json#/state/opacity"}},
                     "activated":{"background":{"type":"color","value":"#0f08"}}}}
                ]}}}
            """.trimIndent().toByteArray(),
            "layouts/neutral/block.json" to """
                {"content":{"left":{"inner":[
                  {"type":"joystick","axis":{"input":"axisXY","output":"leftJoystick"},
                   "action":"leftBumper","actionThreshold":0.5}
                ]}}}
            """.trimIndent().toByteArray(),
            "layouts/neutral/aim.json" to """
                {"content":{"right":{"inner":[{"type":"touchpad",
                   "axis":{"input":"axisXY","output":"rightJoystick"},"action":"rightTrigger"}]},
                   "upper":{"right":[{"type":"button","action":"leftTrigger","toggle":true,
                   "styles":{"toggled":{"faceImage":{"type":"icon","value":"aim"}}}}]}}}
            """.trimIndent().toByteArray(),
            "layouts/neutral/adventure.json" to """
                {"content":{
                   "left":{"inner":[{"type":"joystick","axis":[
                       {"input":"axisX","output":"leftJoystickX"},{"input":"axisY","output":"leftJoystickY"}]}]},
                   "center":{"inner":[{"type":"button","action":"view"}]},
                   "lower":{"center":{"type":"button","action":"gamepadX"},
                       "rightCenter":[{"type":"button","action":"gamepadA","visible":false},
                           {"type":"button","action":"gamepadY","enabled":false}]}
                }}
            """.trimIndent().toByteArray(),
            "layouts/pt/pt-BR/optional.json" to "[not a layout]".toByteArray(),
            "assets/neutral/@1.0x/Action.png" to art.toByteArray()
        )
        ZipOutputStream(output).use { zip ->
            for ((path,bytes) in files) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        val xbox=OfficialTouchSession(false)
        assertTrue(xbox.beginLoad())
        xbox.receive(OfficialTouchNotice.Hide)
        xbox.receive(OfficialTouchNotice.Patch("""[{"op":"replace","path":"show","value":true}]"""))
        xbox.load(OfficialTouchLoad.Failed(OfficialTouchLoad.Stage.REFERENCE,403))
        assertEquals("TOUCH_LAYOUT/UNAVAILABLE_REFERENCE/HTTP_403",xbox.diagnostics())
        xbox.selectManually()
        assertTrue(xbox.waitingForManualLoad)
        assertTrue(xbox.beginLoad())
        assertFalse(xbox.beginLoad())
        val archive=File.createTempFile("touch-layout", ".takx")
        val bundle=try {
            archive.writeBytes(output.toByteArray())
            OfficialTouchBundle.parse(archive,"pt-BR")
        } finally { archive.delete() }
        val withBlock = VirtualGamepad(1280, 720, official = bundle.controls("block", bundle.newState())!!)
        val stick = withBlock.controls.single()
        withBlock.down(1, stick.x, stick.y)
        assertEquals(0, withBlock.state().buttons)
        withBlock.move(1, stick.x + stick.radius * .75f, stick.y)
        assertEquals(HudBinding.LB.button, withBlock.state().buttons)
        withBlock.up(1)
        assertEquals(GamepadState(), withBlock.state())
        val aimLayout = bundle.controls("aim", bundle.newState())!!
        val withAim = VirtualGamepad(1280, 720, official = aimLayout)
        val aim = withAim.controls.single { it.toggle }
        val camera = withAim.controls.single { it.dragLook }
        withAim.down(1, aim.x, aim.y)
        withAim.up(1)
        assertTrue(withAim.toggled(aim))
        assertEquals(1f, withAim.state().leftTrigger, 0f)
        withAim.down(1, aim.x, aim.y)
        withAim.up(1)
        assertEquals(GamepadState(), withAim.state())
        withAim.down(1, camera.x + 20f, camera.y, now = 100)
        assertEquals(0f, withAim.state(100).rightX, 0f)
        assertEquals(1f, withAim.state(100).rightTrigger, 0f)
        withAim.move(1, camera.x + 30f, camera.y, now = 110)
        assertTrue(withAim.state(110).rightX > 0f)
        assertEquals(0f, withAim.state(150).rightX, 0f)
        withAim.up(1, now = 151)
        assertEquals(GamepadState(), withAim.state(151))
        val adventure = bundle.controls("adventure", bundle.newState())!!
        assertEquals(TouchKind.LEFT_STICK, adventure.first().kind)
        assertEquals(HudStickAxis.BOTH, adventure.first().stickAxis)
        assertTrue(adventure.any { it.binding == HudBinding.VIEW })
        assertTrue(adventure.any { it.binding == HudBinding.X })
        assertFalse(adventure.any { it.binding == HudBinding.A })
        assertFalse(adventure.single { it.binding == HudBinding.Y }.officialAppearance!!.enabled)
        xbox.load(OfficialTouchLoad.Ready(bundle))
        val controls=xbox.controls()!!
        val appearance = controls.single().officialAppearance!!
        assertNotNull(appearance.idle.face?.image)
        assertSame(appearance.idle.face?.image, appearance.active.face?.image)
        assertEquals(.5f, appearance.idle.opacity, 0f)
        assertEquals(0x8800ff00.toInt(), appearance.active.background?.color)
        xbox.receive(OfficialTouchNotice.Show("layout-missing-from-package"))
        assertEquals(controls,xbox.controls())
        xbox.receive(OfficialTouchNotice.Show(""))
        assertTrue(xbox.controls()!!.any { it.binding==HudBinding.A })
        xbox.receive(OfficialTouchNotice.ShowDefault)
        val controller=Robolectric.buildActivity(Activity::class.java).setup()
        var state=GamepadState()
        val direct=mutableListOf<NativePointer>()
        val view=GameTouchView(controller.get(),{state=it},{direct.addAll(it)},{_,_ -> .25f to .5f},{})
        controller.get().setContentView(view)
        val hud = HudSettings(scale=150, opacity=20, placements=mapOf(
            controls.single().id to HudPlacement(.15f, .20f, binding=HudBinding.Y)
        ))
        view.configure(TouchMode.VIRTUAL,GameTitle("test","Test game"),hud)
        view.installOfficialControls(xbox.layout())
        layout(view)
        val button=VirtualGamepad(1280,720,official=controls).controls.single()
        val render=Bitmap.createBitmap(1280,720,Bitmap.Config.ARGB_8888)
        view.draw(Canvas(render))
        val center=render.getPixel(button.x.toInt(),button.y.toInt())
        assertTrue(Color.red(center)>Color.green(center)+80)
        assertTrue(Color.alpha(center) in 126..129)
        val edgeX = (button.x + button.radius * .9f).toInt()
        assertEquals(0, Color.alpha(render.getPixel(edgeX, button.y.toInt())))
        render.recycle()
        val now=SystemClock.uptimeMillis()
        event(view,MotionEvent.ACTION_DOWN,now,Triple(0,button.x,button.y))
        assertEquals(HudBinding.A.button,state.buttons)
        assertEquals(1f,state.rightTrigger,0f)
        assertTrue(direct.isEmpty())
        val active = Bitmap.createBitmap(1280,720,Bitmap.Config.ARGB_8888)
        view.draw(Canvas(active))
        assertTrue(Color.green(active.getPixel(edgeX,button.y.toInt())) > 200)
        active.recycle()
        view.beginEditing()
        assertFalse(view.isEditing)
        view.applyHud(hud.copy(scale=75, opacity=100))
        assertEquals(HudBinding.A.button,state.buttons)
        xbox.receive(OfficialTouchNotice.Hide)
        view.installOfficialControls(xbox.layout())
        assertEquals(GamepadState(),state)
        assertFalse(view.controlsVisible)
        xbox.receive(OfficialTouchNotice.ShowDefault)
        view.installOfficialControls(xbox.layout())
        assertTrue(view.controlsVisible)
        view.configure(TouchMode.NATIVE,GameTitle("test","Test game"))
        event(view,MotionEvent.ACTION_DOWN,now+10,Triple(0,button.x,button.y))
        assertEquals(HudBinding.A.button,state.buttons)
        assertTrue(direct.isEmpty())
        event(view,MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            now+15,Triple(0,button.x,button.y),Triple(1,620f,200f))
        assertEquals(HudBinding.A.button,state.buttons)
        assertEquals(2,direct.single().id)
        view.releaseInputs()
        assertEquals(GamepadState(),state)
        assertEquals(2,direct.last().type)
        controller.pause().stop().destroy()
    }

    @Test fun officialEditorSavesGeometryForOneLayoutAndKeepsLiveUpdatesAndCommands() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val store = GameHudStore(activity)
        val game = GameTitle("test-official-editor", "Test game")
        var state = GamepadState()
        val direct = mutableListOf<NativePointer>()
        val view = GameTouchView(activity, { state = it }, { direct.addAll(it) }, { _, _ -> .25f to .5f }, {})
        activity.setContentView(view)
        val style = OfficialTouchStyle()
        val control = HudElement("right-inner-0", "LB", .75f, .62f, .05f,
            binding = HudBinding.LB, officialAppearance = OfficialTouchAppearance(style, style, enabled = true))
        val combat = OfficialTouchLayout("combat", listOf(control))
        val navigation = OfficialTouchLayout("navigation", listOf(control.copy(binding = HudBinding.B, action = HudAction(HudBinding.B))))
        val local = HudSettings(placements = mapOf(control.id to HudPlacement(.20f, .30f, binding = HudBinding.Y)))
        store.save(game.titleId, local)
        view.onReadOfficialHud = { store.readOfficial(game.titleId, it) }
        view.onOfficialHudSaved = { id, positions -> store.saveOfficial(game.titleId, id, positions); true }
        view.configure(TouchMode.NATIVE, game, local)
        view.installOfficialControls(combat)
        layout(view)

        val panel = GameHudPanel(activity, game, store, {}, {}, {}, {},
            editOfficial = view::beginOfficialEditing).show()
        fun findEditorRow(child: View): TextView? {
            if (child is TextView && child.isClickable && child.text == "Editar HUD oficial") return child
            if (child is ViewGroup) for (i in 0 until child.childCount) {
                findEditorRow(child.getChildAt(i))?.let { return it }
            }
            return null
        }
        assertTrue(findEditorRow(panel.body)!!.performClick())
        assertTrue(view.isEditing)
        val now = SystemClock.uptimeMillis()
        event(view, MotionEvent.ACTION_DOWN, now, Triple(0, 960f, 446f))
        event(view, MotionEvent.ACTION_MOVE, now + 1, Triple(0, 800f, 360f))
        event(view, MotionEvent.ACTION_UP, now + 2, Triple(0, 800f, 360f))
        tap(view, 610f, 690f)
        view.installOfficialControls(navigation)
        tap(view, 810f, 53f)
        assertFalse(view.isEditing)
        assertEquals(GamepadState(), state)
        assertTrue(direct.isEmpty())
        val saved = store.readOfficial(game.titleId, "combat").getValue(control.id)
        assertEquals(.625f, saved.x, .01f)
        assertEquals(1.1f, saved.scale, .001f)
        assertTrue(store.readOfficial(game.titleId, "navigation").isEmpty())
        assertEquals(local, store.read(game.titleId))

        view.installOfficialControls(combat)
        event(view, MotionEvent.ACTION_DOWN, now + 5, Triple(0, 800f, 360f))
        assertEquals(HudBinding.LB.button, state.buttons)
        event(view, MotionEvent.ACTION_UP, now + 6, Triple(0, 800f, 360f))
        view.beginOfficialEditing()
        tap(view, 645f, 53f)
        tap(view, 480f, 53f)
        assertEquals(saved, store.readOfficial(game.titleId, "combat").getValue(control.id))
        view.beginOfficialEditing()
        tap(view, 645f, 53f)
        tap(view, 810f, 53f)
        assertTrue(store.readOfficial(game.titleId, "combat").isEmpty())
        assertEquals(local, store.read(game.titleId))
        controller.pause().stop().destroy()
    }
    @Test fun comboPickerDoesNotApplyIntermediateChoicesAndAllowsClearingASwipe() {
        val controller=Robolectric.buildActivity(Activity::class.java).setup()
        val activity=controller.get().apply { setContentView(FrameLayout(this)) }; layout(activity.window.decorView)
        fun views(view: View): List<View> = listOf(view)+if(view is ViewGroup)
            (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
        fun choose(text: String) {
            val dialog=ShadowDialog.getLatestDialog() as ConsoleDialog
            views(dialog.panel).filterIsInstance<TextView>().single { it.isClickable && it.text.toString()==text }.performClick()
        }
        var applied: HudAction?=null; var count=0
        HudActionDialogs.action(activity,HudAction(HudBinding.B)) { applied=it; count++ }
        choose("○ RB"); assertEquals(0,count)
        choose("APLICAR B + RB"); assertEquals(HudAction(HudBinding.B,HudBinding.RB),applied)
        HudActionDialogs.action(activity,applied,allowClear=true) { applied=it; count++ }
        choose("Remover este gesto"); assertNull(applied); assertEquals(2,count)
        controller.pause().stop().destroy()
    }
    @Test fun mobileShotGestureHoldsItsModifiersThenReleasesAndCancellationNeverFiresLater() {
        val controller=Robolectric.buildActivity(Activity::class.java).setup()
        var state=GamepadState()
        val view=GameTouchView(controller.get(),{state=it},{fail("HUD used native pointers")},{_,_ -> null},{})
        controller.get().setContentView(view); view.configure(TouchMode.VIRTUAL,GameTitle("EAFC26","EA SPORTS FC 26")); layout(view)
        val shot=VirtualGamepad(1280,720,HudPreset.EA_FC).controls.single { it.id=="shoot" }
        val now=SystemClock.uptimeMillis()
        event(view,MotionEvent.ACTION_DOWN,now,Triple(0,shot.x,shot.y))
        event(view,MotionEvent.ACTION_MOVE,now+8,Triple(0,shot.x,shot.y+40))
        event(view,MotionEvent.ACTION_UP,now+12,Triple(0,shot.x,shot.y+40))
        assertEquals(8224,state.buttons)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(90))
        assertEquals(GamepadState(),state)
        val next=SystemClock.uptimeMillis()
        event(view,MotionEvent.ACTION_DOWN,next,Triple(0,shot.x,shot.y))
        event(view,MotionEvent.ACTION_UP,next+1,Triple(0,shot.x,shot.y))
        view.releaseInputs()
        assertEquals(GamepadState(),state)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
        assertEquals(GamepadState(),state)
        controller.pause().stop().destroy()
    }
    @Test fun editorCanCombineKeysAndEditGesturesWithoutPublishingActionsUntilGameplayResumes() {
        val controller=Robolectric.buildActivity(Activity::class.java).setup()
        val activity=controller.get(); var state=GamepadState()
        val view=GameTouchView(activity,{state=it},{},{_,_ -> null},{})
        activity.setContentView(view); view.configure(TouchMode.VIRTUAL,GameTitle("EAFC","EA SPORTS FC")); layout(view)
        val pass=VirtualGamepad(1280,720,HudPreset.EA_FC).controls.single { it.id=="pass" }
        view.beginEditing(); tap(view,pass.x,pass.y)
        var edits=0
        view.onGesturesRequested={ id,_ ->
            edits++; view.setEditedGestures(id,mapOf(HudSwipe.UP to HudGesture("Combinado",HudAction(HudBinding.RB,HudBinding.X))))
        }
        tap(view,1280*.75f,720*.94f)
        assertEquals(1,edits)
        view.setEditedAction("pass",HudAction(HudBinding.RT,HudBinding.A))
        assertEquals(GamepadState(),state)
        val store=GameHudStore(activity)
        view.onHudSaved={ store.save("EAFC",it); true }
        tap(view,1280*.635f,720*.07f)
        assertEquals(HudAction(HudBinding.RT,HudBinding.A),store.read("EAFC").placements["pass"]!!.action)
        assertEquals(1,store.read("EAFC").placements["pass"]!!.gestures!!.size)
        assertEquals(GamepadState(),state)
        controller.pause().stop().destroy()
    }
    private fun layout(view: View,width: Int=1280,height: Int=720) {
        repeat(2) {
            view.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY))
            view.layout(0,0,width,height)
            if(it==0) shadowOf(Looper.getMainLooper()).idle()
        }
    }
    private fun event(view: View,action: Int,time: Long,vararg fingers: Triple<Int,Float,Float>) {
        val properties=fingers.map { MotionEvent.PointerProperties().apply { id=it.first; toolType=MotionEvent.TOOL_TYPE_FINGER } }.toTypedArray()
        val points=fingers.map { MotionEvent.PointerCoords().apply { x=it.second; y=it.third; pressure=1f } }.toTypedArray()
        MotionEvent.obtain(0,time,action,fingers.size,properties,points,0,0,1f,1f,0,0,0x1002,0).also {
            assertTrue(view.dispatchTouchEvent(it)); it.recycle()
        }
    }
    private fun tap(view: View,x: Float,y: Float) {
        val now=SystemClock.uptimeMillis()
        event(view,MotionEvent.ACTION_DOWN,now,Triple(0,x,y))
        event(view,MotionEvent.ACTION_UP,now+1,Triple(0,x,y))
    }
    private fun save(view: View,name: String) {
        val image=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
        view.draw(Canvas(image))
        File("build/reports/huds/$name.png").apply { parentFile?.mkdirs() }.outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) }
        image.recycle()
    }
    @Test fun threeFingersMoveAimShootAndJumpThroughTheGamepadWireFormatWithoutNativePointers() {
        val controller=Robolectric.buildActivity(Activity::class.java).setup()
        var state=GamepadState()
        val view=GameTouchView(controller.get(),{state=it},{fail("The virtual HUD sent native touch")},{x,y -> x/1280 to y/720},{})
        controller.get().setContentView(view)
        view.configure(TouchMode.VIRTUAL,GameTitle("CALLOFDUTY","Call of Duty")); layout(view)
        val controls=VirtualGamepad(1280,720,HudPreset.CALL_OF_DUTY).controls
        val move=controls.single { it.id=="move" }; val fire=controls.single { it.id=="fire" }; val jump=controls.single { it.id=="jump" }
        val left=Triple(3,move.x+move.radius,move.y)
        val shooting=Triple(7,fire.x,fire.y)
        val jumping=Triple(11,jump.x,jump.y)
        val base=SystemClock.uptimeMillis()
        event(view,MotionEvent.ACTION_DOWN,base,Triple(3,move.x,move.y))
        event(view,MotionEvent.ACTION_MOVE,base+16,left)
        event(view,MotionEvent.ACTION_POINTER_DOWN or (1 shl 8),base+16,left,shooting)
        event(view,MotionEvent.ACTION_POINTER_DOWN or (2 shl 8),base+16,left,shooting,jumping)
        event(view,MotionEvent.ACTION_MOVE,base+32,left,shooting.copy(second=fire.x+12),jumping)
        assertEquals(1f,state.leftX,.0001f); assertEquals(16,state.buttons)
        assertEquals(1f,state.rightTrigger,0f); assertTrue(state.rightX>0)
        val bytes=java.nio.ByteBuffer.wrap(GamepadPacket.encode(state,1,0.0)).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        assertEquals(16,bytes.getShort(16).toInt()); assertEquals(65535,bytes.getShort(28).toInt() and 65535)
        event(view,MotionEvent.ACTION_POINTER_UP or (2 shl 8),base+40,left,shooting,jumping)
        assertEquals(0,state.buttons); assertEquals(1f,state.rightTrigger,0f)
        event(view,MotionEvent.ACTION_POINTER_UP or (1 shl 8),base+48,left,shooting)
        assertEquals(0f,state.rightTrigger,0f); assertEquals(1f,state.leftX,.0001f)
        event(view,MotionEvent.ACTION_CANCEL,base+50,left)
        assertEquals(GamepadState(),state)
        controller.pause().stop().destroy()
    }
    @Test fun cameraAutomaticallyReturnsToNeutralWhenTheFingerStopsMoving() {
        val controller=Robolectric.buildActivity(Activity::class.java).setup()
        var state=GamepadState()
        val view=GameTouchView(controller.get(),{state=it},{},{_,_ -> null},{})
        controller.get().setContentView(view); view.configure(TouchMode.VIRTUAL,GameTitle("CALLOFDUTY","Call of Duty")); layout(view)
        val now=SystemClock.uptimeMillis()
        event(view,MotionEvent.ACTION_DOWN,now,Triple(0,700f,300f))
        event(view,MotionEvent.ACTION_MOVE,now+16,Triple(0,712f,300f))
        assertTrue(state.rightX>0)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(120))
        assertEquals(0f,state.rightX,0f)
        view.releaseInputs(); controller.pause().stop().destroy()
    }
    @Test fun editorSavesPositionsAndBindingsPerGameAndCancelNeverSendsAnAction() {
        val controller=Robolectric.buildActivity(Activity::class.java).setup()
        val activity=controller.get(); val store=GameHudStore(activity)
        var state=GamepadState(); var saves=0
        val view=GameTouchView(activity,{state=it},{},{_,_ -> null},{})
        view.setBackgroundColor(Color.rgb(24,43,33))
        activity.setContentView(view)
        val game=GameTitle("EAFC26","EA SPORTS FC 26")
        view.configure(TouchMode.VIRTUAL,game,store.read(game.titleId)); layout(view)
        view.onHudSaved={ store.save(game.titleId,it); saves++; true }
        view.beginEditing()
        val pass=VirtualGamepad(1280,720,HudPreset.EA_FC).controls.single { it.id=="pass" }
        event(view,MotionEvent.ACTION_DOWN,0,Triple(0,pass.x,pass.y))
        event(view,MotionEvent.ACTION_MOVE,16,Triple(0,1280*.67f,720*.77f))
        event(view,MotionEvent.ACTION_UP,32,Triple(0,1280*.67f,720*.77f))
        assertEquals(GamepadState(),state)
        view.setEditedBinding("pass",HudBinding.X)
        save(view,"editor")
        tap(view,1280*.635f,720*.07f)
        assertFalse(view.isEditing); assertEquals(1,saves)
        val saved=GameHudStore(activity).read("EAFC26")
        assertEquals(.67f,saved.placements["pass"]!!.x,.0001f)
        assertEquals(HudBinding.X,saved.placements["pass"]!!.binding)
        event(view,MotionEvent.ACTION_DOWN,50,Triple(0,1280*.67f,720*.77f))
        assertEquals(64,state.buttons)
        view.releaseInputs(); view.beginEditing()
        event(view,MotionEvent.ACTION_DOWN,60,Triple(0,1280*.67f,720*.77f))
        event(view,MotionEvent.ACTION_MOVE,76,Triple(0,1280*.55f,720*.8f))
        view.cancelEditing()
        assertEquals(saved,view.configuration); assertEquals(1,saves)
        assertEquals(GamepadState(),state)
        assertEquals(HudSettings(),store.read("FORTNITE"))
        controller.pause().stop().destroy()
    }
    @Test fun inGameSettingsChooseAndPersistTheHudWithoutSendingGameActions() {
        val controller=Robolectric.buildActivity(Activity::class.java).setup()
        val activity=controller.get().apply { setContentView(FrameLayout(this)) }
        layout(activity.window.decorView)
        val store=GameHudStore(activity); val game=GameTitle("EAFC26","EA SPORTS FC 26")
        var applied=HudSettings(); var edits=0; var hidden=0; var page=HudPreset.EA_FC
        val panel=GameHudPanel(activity,game,store,{ applied=it },{ edits++ },{ hidden++ },{ page=it })
        fun all(view: View): List<View> = listOf(view)+if(view is ViewGroup)
            (0 until view.childCount).flatMap { all(view.getChildAt(it)) } else emptyList()
        fun choose(text: String) {
            val dialog=ShadowDialog.getLatestDialog() as ConsoleDialog
            all(dialog.body).filterIsInstance<TextView>().single { it.isClickable && it.text.toString().contains(text) }.performClick()
        }
        val settings=panel.show()
        val root=settings.window!!.decorView
        root.measure(View.MeasureSpec.makeMeasureSpec(settings.window!!.attributes.width,View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(648,View.MeasureSpec.AT_MOST))
        root.layout(0,0,root.measuredWidth,root.measuredHeight)
        save(root,"settings")
        choose("Opacidade:"); choose("75%")
        assertEquals(75,applied.opacity); assertEquals(applied,GameHudStore(activity).read(game.titleId))
        choose("Mostrar HUD de defesa"); assertEquals(HudPreset.EA_FC_DEFENSE,page)
        panel.show(); choose("Editar posição"); assertEquals(1,edits)
        panel.show(); choose("Layout:"); choose("Corrida — padrão")
        assertEquals(HudPreset.RACING,applied.preset)
        assertEquals(HudSettings(),store.read("HADES2"))
        choose("Usar apenas controle nesta partida"); assertEquals(1,hidden)
        controller.pause().stop().destroy()
    }
    @Test fun actionHudsRenderAtPhoneSizesAndPageChangesKeepTheSavedProfile() {
        val controller=Robolectric.buildActivity(Activity::class.java).setup()
        val activity=controller.get()
        val root=FrameLayout(activity).apply { setBackgroundColor(Color.rgb(43,49,57)) }
        val view=GameTouchView(activity,{}, {},{_,_ -> null},{})
        root.addView(view,FrameLayout.LayoutParams(-1,-1)); activity.setContentView(root)
        val profiles=mapOf("call-of-duty" to HudPreset.CALL_OF_DUTY,"forza" to HudPreset.FORZA,"ea-fc" to HudPreset.EA_FC,
            "ea-fc-defense" to HudPreset.EA_FC_DEFENSE,"hades" to HudPreset.HADES)
        for((name,preset) in profiles) {
            view.configure(TouchMode.VIRTUAL,GameTitle("DEMO","Demonstração"),HudSettings(preset=preset))
            layout(root); save(root,name)
            layout(root,2400,1080)
            assertEquals(preset,view.preset)
        }
        view.configure(TouchMode.VIRTUAL,GameTitle("FC","EA SPORTS FC"),HudSettings())
        view.showPage(HudPreset.EA_FC_DEFENSE); view.applyHud(view.configuration.copy(opacity=75))
        assertEquals(HudPreset.EA_FC_DEFENSE,view.preset)
        controller.pause().stop().destroy()
    }
}
