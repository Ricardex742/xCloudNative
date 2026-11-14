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
import com.xcloud.tclnative.ui.dashboard.ConsoleDialog
import com.xcloud.tclnative.ui.dashboard.GameLoadingView
import kotlinx.coroutines.Job
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
    @Test fun changingUpscalerAcrossAttemptsNeverLeavesLoadingOpenWhenRendererPreparationThrows() {
        val server=MockWebServer();server.start()
        val controller=Robolectric.buildActivity(RejectingRendererActivity::class.java).setup()
        val activity=controller.get()
        val signaling=XCloudSignalingClient(XCloudSession("test-token",server.url("/").toString(),"demo"))
        val launch=MainActivity::class.java.getDeclaredMethod("launchCloudGame",XCloudSignalingClient::class.java,
            GameTitle::class.java,TouchMode::class.java).apply { isAccessible=true }
        val game=GameTitle("demo-title","Demo game",posterUrl="",tileUrl="")
        try {
            for(mode in FsrMode.entries) {
                (ShadowDialog.getLatestDialog() as? ConsoleDialog)?.dismiss()
                ReflectionHelpers.setField(activity,"playbackSettings",PlaybackSettings(fsr=mode,touchMode=TouchMode.OFF))
                launch.invoke(activity,signaling,game,TouchMode.OFF)
                val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5)
                do {
                    shadowOf(Looper.getMainLooper()).idle();Thread.sleep(5)
                    val job=ReflectionHelpers.getField<Job?>(activity,"gameJob")
                } while(job?.isCompleted==false && System.nanoTime()<deadline)
                val job=ReflectionHelpers.getField<Job?>(activity,"gameJob")
                assertTrue("Renderer preparation must finish via the session error path",job?.isCompleted==true)
                val queue=ReflectionHelpers.getField<GameLoadingView>(activity,"queuePanel")
                assertEquals(View.GONE,queue.visibility)
                val dialog=ShadowDialog.getLatestDialog() as ConsoleDialog
                val text=all(dialog.panel).filterIsInstance<TextView>().joinToString("\n") { it.text }
                assertTrue(text,text.contains("RENDERER:"));assertTrue(text,text.contains("Upscaling=$mode"))
                assertTrue(text,text.contains("NOT_STARTED"))
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
