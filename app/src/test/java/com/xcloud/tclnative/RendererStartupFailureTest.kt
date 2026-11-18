package com.xcloud.tclnative

import android.os.Looper
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
import org.robolectric.util.ReflectionHelpers
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
