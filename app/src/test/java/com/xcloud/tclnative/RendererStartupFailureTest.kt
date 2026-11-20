package com.xcloud.tclnative

import android.os.Looper
import android.content.pm.PackageManager
import android.hardware.input.InputManager
import android.util.SparseArray
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.xcloud.tclnative.auth.XCloudSession
import com.xcloud.tclnative.renderer.AiPqSurfaceRenderer
import com.xcloud.tclnative.settings.*
import com.xcloud.tclnative.signaling.GameTitle
import com.xcloud.tclnative.signaling.XCloudSignalingClient
import com.xcloud.tclnative.ui.MainActivity
import com.xcloud.tclnative.ui.PlaybackCoordinator
import com.xcloud.tclnative.ui.dashboard.ConsoleDialog
import com.xcloud.tclnative.ui.dashboard.GameLoadingView
import com.xcloud.tclnative.webrtc.AndroidGamepadPolicy
import com.xcloud.tclnative.webrtc.ControllerRouter
import com.xcloud.tclnative.webrtc.GamepadState
import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowInputDevice
import org.robolectric.shadows.ShadowKeyEvent
import org.robolectric.shadow.api.Shadow
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import java.util.concurrent.TimeUnit

class RejectingRendererActivity: MainActivity() {
    val attempted=mutableListOf<FsrMode>()
    override fun prepareSessionRenderer(settings: PlaybackSettings): org.webrtc.EglBase {
        attempted+=settings.fsr
        throw IllegalStateException("synthetic renderer rejection")
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],qualifiers="w1280dp-h720dp-land-television-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class RendererStartupFailureTest {
    private fun all(view: View): List<View> = listOf(view)+if(view is ViewGroup)
        (0 until view.childCount).flatMap { all(view.getChildAt(it)) } else emptyList()
    private fun playback(activity: MainActivity) = ReflectionHelpers.getField<PlaybackCoordinator>(activity, "playback")
    private val launch = MainActivity::class.java.getDeclaredMethod("launchCloudGame", XCloudSignalingClient::class.java,
        GameTitle::class.java, TouchMode::class.java, Boolean::class.javaPrimitiveType).apply { isAccessible = true }

    @Test @Config(shadows=[SparseInputManagerShadow::class])
    fun tvShortcutKeepsRemoteUsableUntilGamepadInputEvenWithAnIdleJoystickDevice() {
        val inputManager=ReflectionHelpers.callStaticMethod<InputManager>(InputManager::class.java,"getInstance")
        val previousDevices=ReflectionHelpers.getField<SparseArray<InputDevice>?>(inputManager,"mInputDevices")
        val remoteSource=InputDevice.SOURCE_GAMEPAD or InputDevice.SOURCE_DPAD
        val gamepadSource=InputDevice.SOURCE_GAMEPAD or InputDevice.SOURCE_JOYSTICK
        fun device(id: Int, name: String, sources: Int) = ShadowInputDevice.makeInputDeviceNamed(name).also { device ->
            ReflectionHelpers.setField(device,"mId",id)
            ReflectionHelpers.setField(device,"mSources",sources)
            ReflectionHelpers.setField(device,"mMotionRanges",ArrayList<InputDevice.MotionRange>())
            if(sources==gamepadSource) listOf(MotionEvent.AXIS_X, MotionEvent.AXIS_Y).forEach { axis ->
                ReflectionHelpers.callInstanceMethod<Unit>(InputDevice::class.java,device,"addMotionRange",
                    ClassParameter.from(Int::class.javaPrimitiveType,axis),
                    ClassParameter.from(Int::class.javaPrimitiveType,InputDevice.SOURCE_JOYSTICK),
                    ClassParameter.from(Float::class.javaPrimitiveType,-1f),ClassParameter.from(Float::class.javaPrimitiveType,1f),
                    ClassParameter.from(Float::class.javaPrimitiveType,0f),ClassParameter.from(Float::class.javaPrimitiveType,0f),
                    ClassParameter.from(Float::class.javaPrimitiveType,0f))
            }
        }
        val installed=SparseArray<InputDevice>().apply {
            put(1,device(1,"Idle internal joystick fixture",gamepadSource))
            put(2,device(2,"TV remote fixture",remoteSource))
            put(8,device(8,"Gamepad fixture",gamepadSource))
        }
        ReflectionHelpers.setField(inputManager,"mInputDevices",installed)
        val c=Robolectric.buildActivity(RejectingRendererActivity::class.java).setup()
        val activity=c.get()
        shadowOf(activity.packageManager).setSystemFeature(PackageManager.FEATURE_LEANBACK,true)
        val router=ReflectionHelpers.getField<ControllerRouter>(activity,"controllers")
        val route=MainActivity::class.java.getDeclaredMethod("controllerSlot",Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,Boolean::class.javaPrimitiveType).apply { isAccessible=true }
        fun key(id: Int, source: Int, code: Int) = KeyEvent(0,0,KeyEvent.ACTION_DOWN,code,0,0,id,0,0,source).also {
            Shadow.extract<ShadowKeyEvent>(it).setDevice(installed.get(id))
        }
        fun slot(event: KeyEvent) = route.invoke(activity,event.deviceId,event.source,AndroidGamepadPolicy.confirmsGamepad(event))
        try {
            val signaling=XCloudSignalingClient(XCloudSession("test-token","https://example.test","demo"))
            launch.invoke(activity,signaling,GameTitle("demo","Demo",posterUrl="",tileUrl=""),TouchMode.OFF,true)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1,activity.attempted.size)
            assertNull(router.deviceFor(0))
            val down = key(2,remoteSource,KeyEvent.KEYCODE_DPAD_DOWN)
            assertEquals(0,slot(down))
            assertEquals(0,slot(key(1,gamepadSource,KeyEvent.KEYCODE_DPAD_DOWN)))
            assertEquals(0,slot(key(2,remoteSource,KeyEvent.KEYCODE_DPAD_CENTER)))
            assertNull(router.deviceFor(0))
            assertFalse(AndroidGamepadPolicy.confirmsJoystick(GamepadState(leftX=.02f)))
            assertTrue(AndroidGamepadPolicy.confirmsJoystick(GamepadState(leftX=.6f)))

            router.update(0,GamepadState(buttons=512))
            assertEquals(0,slot(key(8,gamepadSource,KeyEvent.KEYCODE_BUTTON_A)))
            assertEquals(8,router.deviceFor(0))
            assertEquals(GamepadState(),router.physical(0))
            assertNull(slot(down))
            assertEquals(0,slot(key(8,InputDevice.SOURCE_DPAD,KeyEvent.KEYCODE_DPAD_LEFT)))
            router.remove(8)
            assertEquals(0,slot(down))
        } finally {
            ReflectionHelpers.setField(inputManager,"mInputDevices",previousDevices)
            (ShadowDialog.getLatestDialog() as? ConsoleDialog)?.dismiss()
            c.pause().stop().destroy()
        }
    }

    @Test fun cancellingAQueuedLaunchDoesNotBypassAnEarlierCleanup() {
        MockWebServer().use { server ->
            val controller = Robolectric.buildActivity(RejectingRendererActivity::class.java).setup()
            val activity = controller.get()
            val signaling = XCloudSignalingClient(XCloudSession("test-token", server.url("/").toString(), "demo"))
            val cleanup = CompletableDeferred<Unit>()
            val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
            val closing = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                try { awaitCancellation() } finally { withContext(NonCancellable) { cleanup.await() } }
            }
            try {
                ReflectionHelpers.setField(playback(activity), "job", closing)
                launch.invoke(activity, signaling, GameTitle("second", "Second"), TouchMode.OFF, false)
                launch.invoke(activity, signaling, GameTitle("third", "Third"), TouchMode.OFF, false)
                val latest = checkNotNull(playback(activity).job)
                shadowOf(Looper.getMainLooper()).idle()
                assertTrue(closing.isCancelled)
                assertFalse(latest.isCompleted)
                assertTrue(activity.attempted.isEmpty())

                cleanup.complete(Unit)
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (!latest.isCompleted && System.nanoTime() < deadline) {
                    shadowOf(Looper.getMainLooper()).idle()
                    Thread.sleep(5)
                }
                assertTrue(latest.isCompleted)
                assertEquals(1, activity.attempted.size)
            } finally {
                cleanup.complete(Unit)
                scope.cancel()
                (ShadowDialog.getLatestDialog() as? ConsoleDialog)?.dismiss()
                controller.pause().stop().destroy()
            }
        }
    }

    @Test fun changingUpscalerAcrossAttemptsNeverLeavesLoadingOpenWhenRendererPreparationThrows() {
        val server=MockWebServer();server.start()
        val controller=Robolectric.buildActivity(RejectingRendererActivity::class.java).setup()
        val activity=controller.get()
        val signaling=XCloudSignalingClient(XCloudSession("test-token",server.url("/").toString(),"demo"))
        val game=GameTitle("demo-title","Demo game",posterUrl="",tileUrl="")
        try {
            for(mode in FsrMode.entries) {
                (ShadowDialog.getLatestDialog() as? ConsoleDialog)?.dismiss()
                ReflectionHelpers.setField(activity,"playbackSettings",PlaybackSettings(fsr=mode,touchMode=TouchMode.OFF))
                launch.invoke(activity,signaling,game,TouchMode.OFF,false)
                val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5)
                do {
                    shadowOf(Looper.getMainLooper()).idle();Thread.sleep(5)
                    val job=playback(activity).job
                } while(job?.isCompleted==false && System.nanoTime()<deadline)
                val job=playback(activity).job
                assertTrue("Renderer preparation must finish via the session error path",job?.isCompleted==true)
                val queue=ReflectionHelpers.getField<GameLoadingView>(activity,"queuePanel")
                assertEquals(View.GONE,queue.visibility)
                val dialog=ShadowDialog.getLatestDialog() as ConsoleDialog
                val text=all(dialog.panel).filterIsInstance<TextView>().joinToString("\n") { it.text }
                assertTrue(text,text.contains("RENDERER:"));assertTrue(text,text.contains("Upscaling=$mode"))
                assertTrue(text,text.contains("NOT_STARTED"))
                val saved=com.xcloud.tclnative.diagnostics.StartupDiagnosticsStore(activity).read()!!
                assertEquals(game.name,saved.game)
                assertTrue(saved.text,saved.text.contains("STARTUP/NO_VIDEO"))
                assertTrue(saved.text,saved.text.contains("failure=RENDERER/"))
                assertTrue(saved.text,saved.text.contains("REQUESTED_PROFILE/"))
                assertFalse(saved.text.contains("test-token"))
                val timeline = checkNotNull(saved.timeline)
                assertEquals(com.xcloud.tclnative.diagnostics.PlaybackOrigin.CATALOG, timeline.origin)
                assertTrue(timeline.transports.single().stages.isEmpty())
                assertNull(ReflectionHelpers.getField<AiPqSurfaceRenderer>(activity,"surfaceRenderer")
                    .let { ReflectionHelpers.getField<Any?>(it,"renderer") })
            }
            assertEquals(FsrMode.entries.toList(),activity.attempted)
            assertEquals(0,server.requestCount)
        } finally {
            (ShadowDialog.getLatestDialog() as? ConsoleDialog)?.dismiss()
            controller.pause().stop().destroy();server.shutdown()
        }
    }
}
