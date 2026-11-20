package com.xcloud.tclnative

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Looper
import android.os.SystemClock
import android.view.DisplayCutout
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager.LayoutParams
import android.widget.TextView
import com.xcloud.tclnative.i18n.AppLanguage
import com.xcloud.tclnative.i18n.UiText
import com.xcloud.tclnative.settings.GameShortcutId
import com.xcloud.tclnative.settings.GameShortcutSettings
import com.xcloud.tclnative.settings.TouchMode
import com.xcloud.tclnative.settings.UiTheme
import com.xcloud.tclnative.signaling.GameTitle
import com.xcloud.tclnative.ui.GameTouchView
import com.xcloud.tclnative.ui.dashboard.ConsoleDialog
import com.xcloud.tclnative.ui.dashboard.ConsoleDialogs
import com.xcloud.tclnative.ui.dashboard.DashboardTheme
import com.xcloud.tclnative.ui.dashboard.GameMenuPanel
import com.xcloud.tclnative.ui.dashboard.GameNetworkPanel
import com.xcloud.tclnative.ui.dashboard.GameShortcutBar
import com.xcloud.tclnative.ui.dashboard.GameShortcutEditor
import com.xcloud.tclnative.webrtc.StreamMetrics
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
@Config(sdk = [30], qualifiers = "w780dp-h360dp-land-xxhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GameShortcutBarTest {
    private val width = 2340
    private val height = 1080

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun layout(view: View, insets: WindowInsets) {
        view.dispatchApplyWindowInsets(insets)
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, width, height)
        idle()
    }

    private fun all(view: View): List<View> = listOf(view) + if (view is ViewGroup) {
        (0 until view.childCount).flatMap { all(view.getChildAt(it)) }
    } else emptyList()

    private fun control(root: View, name: String) = all(root).single { it.contentDescription == name }

    private fun bounds(root: ViewGroup, child: View) = Rect(0, 0, child.width, child.height).also {
        root.offsetDescendantRectToMyCoords(child, it)
    }

    private fun tap(root: View, x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(now, now, action, x, y, 0)
            try { assertTrue(root.dispatchTouchEvent(event)) } finally { event.recycle() }
        }
        idle()
    }

    private fun press(dialog: GameShortcutBar, key: Int) {
        assertTrue(dialog.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key)))
        idle()
    }

    private fun capture(name: String, gameplay: View, overlay: View? = null) {
        val screen = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val scene = javaClass.getResourceAsStream("/console-demo/forza-hero.jpg")!!.use { BitmapFactory.decodeStream(it)!! }
        try {
            val canvas = Canvas(screen)
            canvas.drawBitmap(scene, null, Rect(0, 0, width, height), null)
            gameplay.draw(canvas)
            overlay?.draw(canvas)
            File("build/reports/navigation/$name.png").apply { parentFile?.mkdirs() }.outputStream().use {
                assertTrue(screen.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally {
            scene.recycle()
            screen.recycle()
        }
    }

    @Test fun editToolbarPersistsSlotsAndNetworkPanelShowsLiveNumbersWithOptionalWarnings() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        UiText.apply(activity, AppLanguage.PORTUGUESE)
        DashboardTheme.apply(activity, UiTheme.XBOX_SERIES)
        val settings = GameShortcutSettings(activity)
        val insets = WindowInsets.Builder().build()
        val game = View(activity)
        activity.setContentView(game)
        layout(activity.window.decorView, insets)
        lateinit var bar: GameShortcutBar
        lateinit var network: GameNetworkPanel
        var statistics = 0
        fun open(position: GameShortcutBar.Position) {
            val back = { open(bar.position) }
            val shortcuts = GameMenuPanel.shortcuts(image = {}, audio = {}, hudToggle = {}, statistics = { statistics++ },
                diagnostics = {}, network = {
                    network = GameNetworkPanel(activity, settings.networkWarnings, { settings.networkWarnings = it }, back)
                    network.dialog.show()
                })
            bar = GameShortcutBar(activity, shortcuts, position, Rect(), {}, back, settings.slots()) {
                GameShortcutEditor(activity, settings, back).show()
            }.apply { show() }
            layout(bar.window!!.decorView, insets)
        }
        fun barControl(label: String) = control(bar.window!!.decorView, label)
        fun clickPanel(label: String) {
            val dialog = ShadowDialog.getLatestDialog() as ConsoleDialog
            assertTrue(all(dialog.panel).filterIsInstance<TextView>().single { it.isClickable && it.text.toString() == label }.performClick())
            idle()
        }
        fun capturePanel(name: String, dialog: ConsoleDialog) {
            val window = dialog.window!!
            val root = window.decorView
            root.measure(View.MeasureSpec.makeMeasureSpec(window.attributes.width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.AT_MOST))
            root.layout(0, 0, root.measuredWidth, root.measuredHeight)
            capture(name, game, root)
        }
        try {
            settings.restoreSlots()
            settings.networkWarnings = false
            open(GameShortcutBar.Position())
            assertTrue(barControl("Condições de rede").isShown)
            capture("live-shortcuts-with-network", game, bar.window!!.decorView)
            barControl("Condições de rede").performClick()
            val readings = all(network.dialog.body).filterIsInstance<TextView>()
            assertEquals(4, readings.count { it.text == "—" })
            network.update(StreamMetrics(rttMs = 44.0, jitterMs = 3.0, packetLossPercent = 0.0, bitrateMbps = 5.5))
            assertTrue(readings.map { it.text.toString() }.containsAll(listOf("44 ms", "3 ms", "0%", "5,5 Mbps")))
            assertFalse(settings.networkWarnings)
            clickPanel("Avisos de rede: Desativados")
            assertTrue(settings.networkWarnings)
            capturePanel("live-network-conditions", network.dialog)
            network.update(null)
            assertEquals(4, readings.count { it.text == "—" })
            network.dialog.cancel(); idle()
            assertEquals("Condições de rede", bar.currentFocus?.contentDescription)

            barControl("Mais opções").performClick()
            barControl("Editar atalhos").performClick()
            clickPanel("Atalho 1: Imagem")
            clickPanel("○  Estatísticas")
            clickPanel("Atalho 2: Áudio")
            clickPanel("○  Rede")
            clickPanel("Atalho 3: Controle virtual")
            clickPanel("○  Sem atalho")
            assertEquals(listOf(GameShortcutId.STATISTICS, GameShortcutId.NETWORK, null, GameShortcutId.AUDIO),
                GameShortcutSettings(activity).slots())
            capturePanel("live-shortcut-editor", ShadowDialog.getLatestDialog() as ConsoleDialog)
            clickPanel("VOLTAR")
            assertTrue(bar.position.more)
            assertTrue(barControl("Imagem e tela").isShown)
            press(bar, KeyEvent.KEYCODE_BUTTON_B)
            assertFalse(barControl("Imagem e tela").isShown)
            assertTrue(barControl("Mostrar / ocultar estatísticas").isShown)
            barControl("Mostrar / ocultar estatísticas").performClick()
            assertEquals(1, statistics)
            open(GameShortcutBar.Position(more = true))
            barControl("Editar atalhos").performClick()
            clickPanel("Restaurar padrão")
            assertEquals(GameShortcutSettings.DEFAULT_SLOTS, settings.slots())
            assertTrue(settings.networkWarnings)
        } finally {
            settings.restoreSlots()
            settings.networkWarnings = false
            ShadowDialog.getLatestDialog()?.dismiss()
            controller.pause().stop().destroy()
            DashboardTheme.apply(activity, UiTheme.GREEN)
        }
    }

    @Test fun cutoutTouchAndControllerNavigateThreeStatesWithoutSendingGameInput() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val safeArea = Rect(80, 0, 0, 0)
        val cutout = DisplayCutout(safeArea, listOf(Rect(0, 500, 80, 580)))
        val insets = WindowInsets.Builder().setDisplayCutout(cutout).build()
        var pointers = 0
        var statistics = 0
        var ended = 0
        var hudVisible = false
        lateinit var gameplay: GameTouchView
        lateinit var bar: GameShortcutBar
        fun open(position: GameShortcutBar.Position) {
            val back = { open(bar.position) }
            val shortcuts = GameMenuPanel.shortcuts(image = {
                ConsoleDialogs.menu(activity, "Imagem e tela", emptyList(), onBack = back)
            }, audio = {}, hud = {}, hudToggle = { hudVisible = !hudVisible }, hudVisible = hudVisible,
                statistics = { statistics++ }, diagnostics = {},
                recovery = {}, statisticsOptions = {}, controllerProfiles = {}, xbox = {})
            gameplay.setMenuCovered(true)
            bar = GameShortcutBar(activity, shortcuts, position, safeArea, { ended++ }, back).apply {
                setOnDismissListener { if (bar === this) gameplay.setMenuCovered(false) }
                show()
            }
            layout(bar.window!!.decorView, insets)
        }

        DashboardTheme.apply(activity, UiTheme.XBOX_SERIES)
        gameplay = GameTouchView(activity, {}, { pointers += it.size }, { x, y -> x to y }, {
            open(GameShortcutBar.Position())
        })
        activity.setContentView(gameplay)
        gameplay.configure(TouchMode.NATIVE, GameTitle("forza", "Forza Horizon 5"))
        layout(activity.window.decorView, insets)
        val arrow = gameplay.getChildAt(0)
        try {
            assertTrue(arrow.left >= cutout.safeInsetLeft)
            capture("live-shortcuts-closed", gameplay)
            tap(gameplay, arrow.left + arrow.width / 2f, arrow.top + arrow.height / 2f)
            assertTrue(bar.isShowing)
            assertEquals(View.INVISIBLE, arrow.visibility)
            val window = bar.window!!
            assertEquals(LayoutParams.MATCH_PARENT, window.attributes.width)
            assertEquals(LayoutParams.MATCH_PARENT, window.attributes.height)
            assertEquals(0, window.attributes.flags and LayoutParams.FLAG_DIM_BEHIND)
            assertEquals(0, window.attributes.fitInsetsTypes)
            val root = window.decorView as ViewGroup
            val more = control(root, "Mais opções")
            val image = control(root, "Imagem e tela")
            val resume = control(root, "Retomar partida")
            val virtual = control(root, "Mostrar controle virtual") as TextView
            assertTrue(virtual.isShown)
            assertFalse(control(root, "HUD e controles deste jogo").isShown)
            assertEquals(0, (0 until virtual.layout.lineCount).sumOf { virtual.layout.getEllipsisCount(it) })
            for (view in listOf(resume, image, control(root, "Áudio e microfone"), virtual, more)) {
                val target = bounds(root, view)
                assertTrue("${view.contentDescription} under cutout: $target", target.left >= cutout.safeInsetLeft)
                assertTrue(target.right < width * .6f)
                assertTrue(target.width() >= 144 && target.height() >= 144)
            }
            capture("live-shortcuts-expanded", gameplay, root)
            val originalLeft = bounds(root, resume).left
            bar.setCutout(Rect(0, 0, 80, 0))
            layout(root, insets)
            assertEquals(originalLeft - 80, bounds(root, resume).left)
            resume.requestFocus()
            // ViewRootImpl normally applies D-pad focus search after dispatching the key.
            assertTrue(resume.focusSearch(View.FOCUS_RIGHT).requestFocus())
            assertEquals(image, bar.currentFocus)
            press(bar, KeyEvent.KEYCODE_BUTTON_A)
            ShadowDialog.getLatestDialog().cancel()
            idle()
            assertEquals("Imagem e tela", bar.currentFocus?.contentDescription)

            val returnedRoot = bar.window!!.decorView as ViewGroup
            val moreTarget = bounds(returnedRoot, control(returnedRoot, "Mais opções"))
            tap(returnedRoot, moreTarget.exactCenterX(), moreTarget.exactCenterY())
            assertTrue(bar.position.more)
            layout(returnedRoot, insets)
            assertTrue(control(returnedRoot, "HUD e controles deste jogo").isShown)
            val stats = control(returnedRoot, "Mostrar / ocultar estatísticas")
            assertTrue(bounds(returnedRoot, stats).top >= moreTarget.bottom)
            capture("live-shortcuts-more", gameplay, returnedRoot)
            press(bar, KeyEvent.KEYCODE_BUTTON_B)
            assertFalse(bar.position.more)
            assertEquals("Mais opções", bar.currentFocus?.contentDescription)
            press(bar, KeyEvent.KEYCODE_ENTER)
            assertTrue(bar.position.more)
            layout(returnedRoot, insets)
            val statsTarget = bounds(returnedRoot, stats)
            tap(returnedRoot, statsTarget.exactCenterX(), statsTarget.exactCenterY())
            assertEquals(1, statistics)
            assertFalse(bar.isShowing)
            assertEquals(View.VISIBLE, arrow.visibility)
            assertEquals(0, pointers)

            open(GameShortcutBar.Position())
            control(bar.window!!.decorView, "Mostrar controle virtual").performClick()
            assertTrue(hudVisible)
            open(GameShortcutBar.Position())
            val hide = control(bar.window!!.decorView, "Ocultar controle virtual") as TextView
            assertTrue(hide.isShown)
            assertEquals(0, (0 until hide.layout.lineCount).sumOf { hide.layout.getEllipsisCount(it) })
            hide.performClick()
            assertFalse(hudVisible)

            fun requestExit() {
                open(GameShortcutBar.Position(more = true))
                control(bar.window!!.decorView, "Encerrar partida").performClick()
                assertEquals(0, ended)
            }
            requestExit()
            ShadowDialog.getLatestDialog().cancel()
            idle()
            assertTrue(bar.position.more)
            assertEquals("Encerrar partida", bar.currentFocus?.contentDescription)
            press(bar, KeyEvent.KEYCODE_BUTTON_X)
            assertFalse(bar.isShowing)
            requestExit()
            val confirmation = ShadowDialog.getLatestDialog() as ConsoleDialog
            all(confirmation.panel).filterIsInstance<TextView>().single { it.isClickable && it.text == "ENCERRAR PARTIDA" }.performClick()
            assertEquals(1, ended)
            assertEquals(0, pointers)
        } finally {
            ShadowDialog.getLatestDialog()?.dismiss()
            controller.pause().stop().destroy()
            DashboardTheme.apply(activity, UiTheme.GREEN)
        }
    }
}
