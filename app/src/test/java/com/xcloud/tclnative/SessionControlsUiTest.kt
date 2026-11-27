package com.xcloud.tclnative

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Looper
import android.view.InputDevice
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import com.xcloud.tclnative.auth.CloudRegion
import com.xcloud.tclnative.settings.*
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
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowInputDevice
import org.robolectric.shadows.ShadowSensor
import org.robolectric.shadows.ShadowSensorManager
import org.robolectric.util.ReflectionHelpers
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SessionControlsUiTest {
    private fun all(view: View): List<View> = listOf(view)+if(view is ViewGroup) (0 until view.childCount).flatMap { all(view.getChildAt(it)) } else emptyList()
    private fun choose(dialog: ConsoleDialog,text: String) { all(dialog.body).filterIsInstance<TextView>().single { it.isClickable && it.text.contains(text) }.performClick() }
    private fun prepare(activity: Activity) {
        val root=activity.window.decorView
        root.measure(View.MeasureSpec.makeMeasureSpec(1280,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(720,View.MeasureSpec.EXACTLY))
        root.layout(0,0,1280,720);shadowOf(Looper.getMainLooper()).idle()
    }
    private fun capture(dialog: ConsoleDialog,name: String) {
        dialog.panel.measure(View.MeasureSpec.makeMeasureSpec(1100,View.MeasureSpec.AT_MOST),View.MeasureSpec.makeMeasureSpec(720,View.MeasureSpec.AT_MOST))
        dialog.panel.layout(0,0,dialog.panel.measuredWidth,dialog.panel.measuredHeight)
        val bitmap=Bitmap.createBitmap(dialog.panel.width,dialog.panel.height,Bitmap.Config.ARGB_8888)
        dialog.panel.draw(Canvas(bitmap))
        File("build/reports/dashboard/$name.png").apply { parentFile.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
        bitmap.recycle()
    }
    @Test fun gameOptionsSaveWithoutChangingDefaultsAndReopenWithTheNewValues() {
        val controller=Robolectric.buildActivity(Activity::class.java).setup()
        val activity=controller.get().apply { setContentView(FrameLayout(this)) }
        prepare(activity)
        val store=SessionOptionsStore(activity,"ui-account")
        val panel=SessionOptionsPanel(activity,store,listOf(CloudRegion("BrazilSouth","https://region.example")),"game","Teste") {}
        val menu=panel.show();capture(menu,"session-options-0.31.6")
        choose(menu,"Bitrate máximo")
        choose(ShadowDialog.getLatestDialog() as ConsoleDialog,"15.0 Mbps")
        assertEquals(15000,store.read("game").maxBitrateKbps)
        assertEquals(0,store.read().maxBitrateKbps)
        assertTrue(all((ShadowDialog.getLatestDialog() as ConsoleDialog).body).filterIsInstance<TextView>().any { it.text.contains("15.0 Mbps") })
        (ShadowDialog.getLatestDialog() as ConsoleDialog).dismiss()
        choose(panel.region(),"BrazilSouth")
        assertEquals("BrazilSouth",store.read("game").region)
        (ShadowDialog.getLatestDialog() as ConsoleDialog).dismiss()
        choose(panel.locale(),"English (US)")
        assertEquals("en-US",store.read("game").locale)
        (ShadowDialog.getLatestDialog() as ConsoleDialog).dismiss();controller.pause().stop().destroy()
    }
    @Test fun audioAndImageControlsPersistWithoutEnablingMicrophoneCapture() {
        val controller=Robolectric.buildActivity(Activity::class.java).setup()
        val activity=controller.get().apply { setContentView(FrameLayout(this)) }
        prepare(activity)
        val store=PlaybackSettingsStore(activity);store.save(PlaybackSettings())
        var changes=0
        val panel=PlaybackSettingsPanel(activity,store,{changes++},{},{},{})
        val audio=panel.showAudio();capture(audio,"audio-options-0.31.6")
        choose(audio,"Ganho de áudio");choose(ShadowDialog.getLatestDialog() as ConsoleDialog,"175%")
        assertEquals(175,store.read().audioGain);assertFalse(store.read().microphone)
        choose(ShadowDialog.getLatestDialog() as ConsoleDialog,"Microfone nas próximas")
        choose(ShadowDialog.getLatestDialog() as ConsoleDialog,"Disponível — iniciar mudo")
        assertTrue(store.read().microphone)
        assertNull(shadowOf(activity).lastRequestedPermission)
        (ShadowDialog.getLatestDialog() as ConsoleDialog).dismiss()
        choose(panel.showImageAdjustments(),"Brilho:");all((ShadowDialog.getLatestDialog() as ConsoleDialog).body).filterIsInstance<TextView>().single { it.isClickable && it.text.toString().replace("○", "").replace("✓", "").trim()=="25%" }.performClick()
        assertEquals(25,store.read().brightness)
        assertTrue(changes>=3)
        (ShadowDialog.getLatestDialog() as ConsoleDialog).dismiss();controller.pause().stop().destroy()
    }
    @Test fun gyroStopsOnNativeTouchAndReleasesTheCameraIfSensorEventsStop() {
        val controller=Robolectric.buildActivity(Activity::class.java).setup();val activity=controller.get()
        val manager=activity.getSystemService(android.content.Context.SENSOR_SERVICE) as SensorManager
        val sensor=ShadowSensor.newInstance(Sensor.TYPE_GYROSCOPE);shadowOf(manager).addSensor(sensor)
        var changes=0;val gyro=GyroController(activity,{0},{changes++})
        gyro.start(GyroMode.ALWAYS,100,true)
        assertFalse(shadowOf(manager).hasListener(gyro));assertTrue(gyro.status.contains("toque nativo"))
        gyro.start(GyroMode.ALWAYS,100,false);assertTrue(shadowOf(manager).hasListener(gyro))
        val event=ShadowSensorManager.createSensorEvent(3);event.sensor=sensor;event.values[0]=1f;event.values[1]=-2f
        gyro.onSensorChanged(event)
        assertTrue(gyro.apply(GamepadState()).rightX>0)
        shadowOf(Looper.getMainLooper()).idleFor(101,TimeUnit.MILLISECONDS)
        assertEquals(GamepadState(),gyro.apply(GamepadState()));assertEquals(2,changes)
        gyro.stop();assertFalse(shadowOf(manager).hasListener(gyro))
        controller.pause().stop().destroy()
    }
    @Test @Config(sdk=[34]) fun controllerMotionNodeIsMatchedToItsOwnGamepad() {
        fun input(id: Int, name: String, source: Int, vendor: Int = 0x054c): InputDevice =
            ShadowInputDevice.makeInputDeviceNamed(name).also {
                ReflectionHelpers.setField(it,"mId",id)
                ReflectionHelpers.setField(it,"mVendorId",vendor)
                ReflectionHelpers.setField(it,"mProductId",0x0ce6)
                ReflectionHelpers.setField(it,"mSources",source)
            }
        val pad = input(3,"DualSense Wireless Controller",InputDevice.SOURCE_GAMEPAD)
        val motion = input(4,"DualSense Wireless Controller Motion Sensors",InputDevice.SOURCE_SENSOR)
        val other = input(5,"DualSense Wireless Controller Motion Sensors",InputDevice.SOURCE_SENSOR,vendor=1)
        val inputs = listOf(other,motion,pad)
        assertEquals(listOf(3,4),controllerMotionNodes(inputs,3).map { it.id })
        assertEquals(listOf(3,4),controllerMotionNodes(inputs,null).map { it.id })
        assertTrue(controllerMotionNodes(inputs,5).isEmpty())
        val secondPad = input(6,pad.name,InputDevice.SOURCE_GAMEPAD)
        assertEquals(listOf(3),controllerMotionNodes(inputs + secondPad,3).map { it.id })
    }
    @Test fun deviceGyroUsesTheUncalibratedSensorWithBiasRemoved() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val context = activity.get()
            val manager = context.getSystemService(SensorManager::class.java)
            val sensor = ShadowSensor.newInstance(Sensor.TYPE_GYROSCOPE_UNCALIBRATED)
            shadowOf(manager).addSensor(sensor)
            val gyro = GyroController(context,{0},{})
            gyro.start(GyroMode.ALWAYS,100,false)
            assertTrue(gyro.listening)
            val sample = ShadowSensorManager.createSensorEvent(6).apply {
                this.sensor=sensor; values[1]=.3f; values[4]=.3f
            }
            gyro.onSensorChanged(sample)
            assertEquals(0f,gyro.apply(GamepadState()).rightX,0f)
            sample.values[1]=.6f
            gyro.onSensorChanged(sample)
            assertEquals(-.3008f,gyro.apply(GamepadState()).rightX,.0001f)
            assertTrue(gyro.diagnostics().contains("SAMPLES=2; MOTION=1"))
            gyro.stop()
        } finally { activity.pause().stop().destroy() }
    }
    @Test fun categoriesAndBackKeepTheUserInTheRightSectionWithoutChangingPreferences() {
        val controller=Robolectric.buildActivity(Activity::class.java).setup()
        val activity=controller.get().apply { setContentView(FrameLayout(this)) };prepare(activity)
        val store=PlaybackSettingsStore(activity);store.save(PlaybackSettings())
        val pages=mutableListOf<SettingsPage>();var changes=0
        val panel=PlaybackSettingsPanel(activity,store,{changes++},{},{},{},onPageEntered={pages.add(it)})
        val root=panel.show();capture(root,"categorized-settings")
        val rows=all(root.body).filterIsInstance<TextView>().filter { it.isClickable }.map { it.text.toString().substringBefore('\n') }
        assertEquals(listOf("Aparência e temas","Conta e amigos","Ajuda e diagnóstico"),rows)
        root.panel.findViewWithTag<TextView>("settings-section-network").performClick()
        assertSame(root,ShadowDialog.getLatestDialog())
        choose(root,"IPv4 / IPv6")
        assertEquals(0,changes);assertEquals(PlaybackSettings(),store.read())
        choose(ShadowDialog.getLatestDialog() as ConsoleDialog,"Preferir IPv4")
        assertEquals(IpPreference.IPV4,store.read().ipPreference);assertEquals(SettingsPage.NETWORK,pages.last())
        val network=ShadowDialog.getLatestDialog() as ConsoleDialog
        network.panel.findViewWithTag<TextView>("settings-section-controls").performClick()
        choose(network,"Giroscópio")
        capture(ShadowDialog.getLatestDialog() as ConsoleDialog,"native-gyro-options-0.31.7")
        assertEquals(SettingsPage.GYRO,pages.last())
        (ShadowDialog.getLatestDialog() as ConsoleDialog).cancel();shadowOf(Looper.getMainLooper()).idle();assertEquals(SettingsPage.CONTROLS,pages.last())
        (ShadowDialog.getLatestDialog() as ConsoleDialog).dismiss();controller.pause().stop().destroy()
    }
    @Test fun nativeGyroIsExplicitOptInAndRequiresASensorWithoutRequestingPermissions() {
        val controller=Robolectric.buildActivity(Activity::class.java).setup()
        val activity=controller.get().apply { setContentView(FrameLayout(this)) };prepare(activity)
        val store=PlaybackSettingsStore(activity);store.save(PlaybackSettings())
        val panel=PlaybackSettingsPanel(activity,store,{},{},{},{})
        val manager=activity.getSystemService(android.content.Context.SENSOR_SERVICE) as SensorManager
        val sensor=ShadowSensor.newInstance(Sensor.TYPE_GYROSCOPE);shadowOf(manager).addSensor(sensor)
        choose(panel.showGyro(),"Toque nativo Xbox")
        assertFalse(store.read().gyroNativeTouch);assertEquals(GyroMode.OFF,store.read().gyroMode)
        choose(ShadowDialog.getLatestDialog() as ConsoleDialog,"Ativado — experimental")
        assertTrue(store.read().gyroNativeTouch);assertEquals(GyroMode.ALWAYS,store.read().gyroMode)
        assertTrue(PlaybackSettingsStore(activity).read().gyroNativeTouch)
        assertNull(shadowOf(activity).lastRequestedPermission)
        (ShadowDialog.getLatestDialog() as ConsoleDialog).dismiss()
        var changes=0;val gyro=GyroController(activity,{0},{changes++})
        gyro.start(GyroMode.ALWAYS,100,true,true);assertTrue(shadowOf(manager).hasListener(gyro))
        val event=ShadowSensorManager.createSensorEvent(3);event.sensor=sensor;event.values[1]=-2f
        gyro.onSensorChanged(event);assertEquals(1f,gyro.apply(GamepadState()).rightX,0f)
        gyro.start(GyroMode.ALWAYS,100,true,true)
        assertEquals(1f,gyro.apply(GamepadState()).rightX,0f)
        assertEquals(1L,gyro.samplesReceived)
        shadowOf(Looper.getMainLooper()).idleFor(101,TimeUnit.MILLISECONDS)
        assertEquals(GamepadState(),gyro.apply(GamepadState()));assertEquals(2,changes)
        gyro.start(GyroMode.LT,100,true,true);gyro.onSensorChanged(event)
        assertEquals(GamepadState(),gyro.apply(GamepadState()))
        assertEquals(1f,gyro.apply(GamepadState(leftTrigger=1f)).rightX,0f)
        gyro.stop();assertFalse(shadowOf(manager).hasListener(gyro));assertEquals(GamepadState(),gyro.apply(GamepadState()))
        assertEquals(2L,gyro.samplesReceived)
        gyro.resetDiagnostics();assertEquals(0L,gyro.samplesReceived)
        controller.pause().stop().destroy()
    }
    @Test fun verticalGyroTogglePersistsAndReversesOnlyTheSensorsContribution() {
        val controller=Robolectric.buildActivity(Activity::class.java).setup()
        val activity=controller.get().apply { setContentView(FrameLayout(this)) };prepare(activity)
        val store=PlaybackSettingsStore(activity)
        val panel=PlaybackSettingsPanel(activity,store,{},{},{},{})
        val manager=activity.getSystemService(android.content.Context.SENSOR_SERVICE) as SensorManager
        val sensor=ShadowSensor.newInstance(Sensor.TYPE_GYROSCOPE);shadowOf(manager).addSensor(sensor)
        val gyro=GyroController(activity,{android.view.Surface.ROTATION_90},{})
        try {
            assertFalse(store.read().gyroInvertY)
            gyro.start(GyroMode.ALWAYS,100,nativeTouch=false)
            val event=ShadowSensorManager.createSensorEvent(3);event.sensor=sensor
            event.values[0]=.25f;event.values[1]=.4f
            gyro.onSensorChanged(event)
            val base=GamepadState(rightX=.1f,rightY=.15f)
            val normal=gyro.apply(base)
            assertTrue(normal.rightY<base.rightY)
            choose(panel.showGyro(),"Inverter eixo vertical")
            val inverted=PlaybackSettingsStore(activity).read().gyroInvertY
            assertTrue(inverted)
            gyro.start(GyroMode.ALWAYS,100,nativeTouch=false,invertY=inverted)
            val reverse=gyro.apply(base)
            assertEquals(normal.rightX,reverse.rightX,0f)
            assertEquals(base.rightY-normal.rightY,reverse.rightY-base.rightY,.0001f)
            assertTrue(shadowOf(manager).hasListener(gyro))
            assertTrue(gyro.diagnostics().contains("INVERT_Y=true"))
            choose(ShadowDialog.getLatestDialog() as ConsoleDialog,"Inverter eixo vertical")
            assertFalse(PlaybackSettingsStore(activity).read().gyroInvertY)
        } finally {
            gyro.stop();ShadowDialog.getLatestDialog()?.dismiss();controller.pause().stop().destroy()
        }
    }
    @Test fun savingAnOlderSettingsEditorKeepsOtherFieldsFromTheNewerEditor() {
        val controller=Robolectric.buildActivity(Activity::class.java).setup()
        val activity=controller.get()
        val store=PlaybackSettingsStore(activity)
        val old=store.read()
        val second=PlaybackSettingsStore(activity)
        second.update(old,old.copy(gyroSource=GyroSource.CONTROLLER,gyroMode=GyroMode.ALWAYS,fsr=FsrMode.FSR1))
        val updated=store.update(old,old.copy(sessionReport=false,imageLab=old.imageLab.copy(neural=true)))
        assertFalse(updated.sessionReport)
        assertTrue(updated.imageLab.neural)
        assertEquals(GyroSource.CONTROLLER,updated.gyroSource)
        assertEquals(GyroMode.ALWAYS,updated.gyroMode)
        assertEquals(FsrMode.FSR1,updated.fsr)
        assertEquals(updated,PlaybackSettingsStore(activity).read())
        controller.pause().stop().destroy()
    }
}
