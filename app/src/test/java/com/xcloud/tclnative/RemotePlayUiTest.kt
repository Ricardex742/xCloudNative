package com.xcloud.tclnative

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.xcloud.tclnative.catalog.GameLibrary
import com.xcloud.tclnative.catalog.XboxProfile
import com.xcloud.tclnative.i18n.AppLanguage
import com.xcloud.tclnative.i18n.UiText
import com.xcloud.tclnative.settings.UiTheme
import com.xcloud.tclnative.signaling.GameTitle
import com.xcloud.tclnative.signaling.RemoteConsole
import com.xcloud.tclnative.ui.dashboard.DashboardTheme
import com.xcloud.tclnative.ui.dashboard.ConsoleBrandView
import com.xcloud.tclnative.ui.dashboard.ConsoleDialog
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
import org.robolectric.shadows.ShadowDialog
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w1280dp-h720dp-land-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RemotePlayUiTest {
    private fun children(root: View): List<View> = listOf(root) +
        if (root is ViewGroup) (0 until root.childCount).flatMap { children(root.getChildAt(it)) } else emptyList()

    private fun layout(view: View, width: Int = 1280, height: Int = 660) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, width, height)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun snapshot(view: View, name: String) {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        File("build/reports/themes/remote-setup-$name.png").apply { parentFile!!.mkdirs() }.outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }

    @Test fun serviceMenuSupportsControllerFocusAndExplicitConnect() {
        val lifecycle = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = lifecycle.get()
        UiText.apply(activity, AppLanguage.PORTUGUESE)
        DashboardTheme.apply(activity, UiTheme.XBOX_SERIES)
        val consoles = RemoteConsole.parse("""{"results":[
            {"serverId":"s1","deviceName":"Xbox da sala","consoleType":"XboxSeriesS","powerState":"On"},
            {"serverId":"s2","deviceName":"Xbox do quarto","consoleType":"XboxOne","powerState":"Off"}]}""")
        var connected: RemoteConsole? = null
        var panel: RemotePlayPanel? = null
        val dashboard = GameDashboardView(activity, GameLibrary(activity, "remote-play-test"),
            {}, {}, {}, {}, onRemotePlay = {
                panel = RemotePlayPanel(activity) {}.also {
                    it.dialog.show()
                    it.showConsoles(consoles, null, {}) { console -> connected = console }
                }
            })
        try {
            activity.setContentView(dashboard)
            layout(dashboard)
            val selector = children(dashboard).single { it.contentDescription == "Cloud Gaming" } as TextView
            val brand = children(dashboard).filterIsInstance<ConsoleBrandView>().single()
            assertEquals(View.VISIBLE, brand.visibility)
            assertEquals("▾", selector.text.toString())
            assertTrue(selector.left >= brand.left + brand.artworkRight(brand.width, brand.height))
            assertTrue(selector.left - (brand.left + brand.artworkRight(brand.width, brand.height)) < 12f)
            assertTrue(selector.top < brand.bottom && selector.bottom > brand.top)
            assertEquals(0, dashboard.top)
            snapshot(dashboard, "series-selector")
            dashboard.focusServiceSelector()
            assertTrue(selector.hasFocus())
            val accept = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_A)
            assertTrue(dashboard.handleKey(accept))
            assertTrue(selector.isSelected)
            assertTrue(dashboard.handleKey(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_B)))
            assertFalse(selector.isSelected)
            assertNull(panel)
            assertTrue(dashboard.handleKey(accept))
            assertTrue(dashboard.handleKey(accept))
            val cards = children(panel!!.dialog.body).filterIsInstance<TextView>()
            val connect = cards.single { it.text == "Conectar" }
            assertNull(connected)
            cards.single { it.text.startsWith("Xbox do quarto\n") }.requestFocus()
            assertEquals(View.GONE, connect.visibility)
            cards.single { it.text.startsWith("Xbox da sala\n") }.requestFocus()
            assertEquals(View.VISIBLE, connect.visibility)
            connect.performClick()
            assertSame(consoles[0], connected)
            assertFalse(selector.isSelected)
        } finally {
            panel?.dialog?.dismiss()
            dashboard.close()
            DashboardTheme.apply(activity, UiTheme.GREEN)
            lifecycle.pause().stop().destroy()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @Test fun missingConsolesOpenFullscreenPreparationAndGuideWithOfficialActions() {
        val lifecycle = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = lifecycle.get()
        UiText.apply(activity, AppLanguage.PORTUGUESE)
        DashboardTheme.apply(activity, UiTheme.GREEN)
        val opened = mutableListOf<String>()
        var retried = false
        val panel = RemotePlayPanel(activity) { opened += it }
        try {
            panel.dialog.show()
            panel.showConsoles(emptyList(), null, { retried = true }, { fail("Unexpected stream") })
            layout(panel.dialog.panel)
            assertEquals(-1, panel.dialog.window!!.attributes.width)
            assertEquals(-1, panel.dialog.window!!.attributes.height)
            fun action(dialog: ConsoleDialog, label: String) =
                children(dialog.panel).filterIsInstance<TextView>().single {
                    it.isClickable && it.visibility == View.VISIBLE && it.text.toString().equals(label, ignoreCase = true)
                }
            assertTrue(children(panel.dialog.body).filterIsInstance<TextView>().any { it.text == "Prepare-se para jogar" })
            snapshot(panel.dialog.panel, "preparation")
            action(panel.dialog, "Obter ajuda").performClick()
            assertTrue(opened.single().startsWith("https://support.xbox.com/"))
            action(panel.dialog, "Configurar console").performClick()
            val guide = ShadowDialog.getLatestDialog() as ConsoleDialog
            layout(guide.panel)
            assertTrue(children(guide.body).filterIsInstance<TextView>().any {
                it.text == "Configurações › Dispositivos e conexões › Recursos remotos"
            })
            snapshot(guide.panel, "guide")
            action(guide, "Configurar no Xbox.com").performClick()
            assertEquals("https://www.xbox.com/pt-br/play/consoles", opened.last())
            guide.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_B))
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(guide.isShowing)
            assertTrue(panel.dialog.isShowing)
            action(panel.dialog, "Atualizar").performClick()
            assertTrue(retried)
        } finally {
            panel.dialog.dismiss()
            lifecycle.pause().stop().destroy()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @Test fun genericThemeUsesCompactProfileWithoutMovingTheCatalogIntoTheHeader() {
        val lifecycle = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = lifecycle.get()
        UiText.apply(activity, AppLanguage.PORTUGUESE)
        DashboardTheme.apply(activity, UiTheme.GREEN)
        val dashboard = GameDashboardView(activity, GameLibrary(activity, "compact-profile"),
            {}, {}, {}, {}, onRemotePlay = {})
        try {
            activity.setContentView(dashboard)
            dashboard.setProfile(XboxProfile(displayName="Demo Xbox", gamertag="Perfil Demo", subscription="ULTIMATE"))
            layout(dashboard)
            val account = children(dashboard).filterIsInstance<TextView>().single { it.text == "DEMO XBOX" }
            val profile = account.parent as View
            val gear = children(profile).single { it.contentDescription == "Configurações" }
            assertTrue(profile.left > dashboard.width / 2)
            assertTrue(profile.width < 380)
            assertEquals(22f, account.textSize)
            assertTrue(gear.right <= profile.width)
            val selector = children(dashboard).single { it.contentDescription == "Cloud Gaming" } as TextView
            assertEquals("Cloud Gaming   ▾", selector.text.toString())
            assertEquals(0, dashboard.top)
            assertSame(dashboard, selector.parent)
            assertTrue(selector.left > profile.left + account.right)
            assertTrue(selector.top < profile.top + account.bottom)
            assertTrue(selector.right <= dashboard.width)
            assertEquals(selector.id, profile.nextFocusRightId)
            snapshot(dashboard, "compact-profile")
            val x = (selector.left + selector.right) / 2f
            val y = (selector.top + selector.bottom) / 2f
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                val tap = MotionEvent.obtain(0, 0, action, x, y, 0)
                try { assertTrue(dashboard.dispatchTouchEvent(tap)) } finally { tap.recycle() }
            }
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(selector.isSelected)
        } finally {
            dashboard.close()
            lifecycle.pause().stop().destroy()
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
