package com.xcloud.tclnative

import android.app.Activity
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.xcloud.tclnative.catalog.GameLibrary
import com.xcloud.tclnative.catalog.LibraryTab
import com.xcloud.tclnative.i18n.AppLanguage
import com.xcloud.tclnative.i18n.UiText
import com.xcloud.tclnative.settings.UiTheme
import com.xcloud.tclnative.signaling.GameTitle
import com.xcloud.tclnative.signaling.RemoteConsole
import com.xcloud.tclnative.ui.dashboard.DashboardTheme
import com.xcloud.tclnative.ui.dashboard.GameDashboardView
import com.xcloud.tclnative.ui.dashboard.GameLoadingView
import com.xcloud.tclnative.ui.dashboard.RemotePlayPanel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w1280dp-h720dp-land-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RemotePlayUiTest {
    private fun children(root: View): List<View> = listOf(root) +
        if (root is ViewGroup) (0 until root.childCount).flatMap { children(root.getChildAt(it)) } else emptyList()

    @Test fun xboxSeriesTabAndConsolePickerSupportFocusAndExplicitConnect() {
        val lifecycle = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = lifecycle.get()
        UiText.apply(activity, AppLanguage.PORTUGUESE)
        DashboardTheme.apply(activity, UiTheme.XBOX_SERIES)
        val consoles = RemoteConsole.parse("""{"results":[
            {"serverId":"s1","deviceName":"Xbox da sala","consoleType":"XboxSeriesS","powerState":"On"},
            {"serverId":"s2","deviceName":"Xbox do quarto","consoleType":"XboxOne","powerState":"Off"}]}""")
        var connected: RemoteConsole? = null
        var panel: RemotePlayPanel? = null
        val dashboard = GameDashboardView(activity, GameLibrary(activity, "remote-play-test"), {}, {}, {}, {},
            onRemotePlay = {
                panel = RemotePlayPanel(activity).also {
                    it.dialog.show()
                    it.showConsoles(consoles, null, {}) { console -> connected = console }
                }
            })
        try {
            activity.setContentView(dashboard)
            dashboard.selectTab(LibraryTab.ALL)
            dashboard.measure(View.MeasureSpec.makeMeasureSpec(1280, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY))
            dashboard.layout(0, 0, 1280, 720)
            val catalog = children(dashboard).single { it.contentDescription?.startsWith("TODOS OS JOGOS, selecionado") == true }
            val entry = children(dashboard).single { it.contentDescription == "Meus consoles" }
            assertTrue(entry.left >= catalog.right)
            assertEquals(entry.id, catalog.nextFocusRightId)
            entry.requestFocus()
            assertTrue(dashboard.handleKey(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_A)))
            val cards = children(panel!!.dialog.body).filterIsInstance<TextView>()
            val connect = cards.single { it.text == "Conectar" }
            assertNull(connected)
            cards.single { it.text.startsWith("Xbox do quarto\n") }.requestFocus()
            assertEquals(View.GONE, connect.visibility)
            cards.single { it.text.startsWith("Xbox da sala\n") }.requestFocus()
            assertEquals(View.VISIBLE, connect.visibility)
            connect.performClick()
            assertSame(consoles[0], connected)
        } finally {
            panel?.dialog?.dismiss()
            dashboard.close()
            DashboardTheme.apply(activity, UiTheme.GREEN)
            lifecycle.pause().stop().destroy()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @Test fun consoleLoadingHasRemoteLabelsAndRestoresCloudQueueOnNextLaunch() {
        val lifecycle = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = lifecycle.get()
        UiText.apply(activity, AppLanguage.PORTUGUESE)
        val loading = GameLoadingView(activity, {})
        try {
            loading.begin(GameTitle("remote-play", "Xbox"), 0L, remotePlay = true)
            val values = children(loading).filterIsInstance<TextView>()
            assertTrue(values.any { it.text == "CONECTANDO AO CONSOLE" && it.visibility == View.VISIBLE })
            assertEquals(View.GONE, values.single { it.tag == 0 }.visibility)
            loading.dismiss()
            loading.begin(GameTitle("cloud-title", "Cloud game"), 0L)
            assertTrue(values.any { it.text == "PREPARANDO SEU JOGO" && it.visibility == View.VISIBLE })
            assertEquals(View.VISIBLE, values.single { it.tag == 0 }.visibility)
        } finally {
            loading.close()
            lifecycle.pause().stop().destroy()
        }
    }
}
