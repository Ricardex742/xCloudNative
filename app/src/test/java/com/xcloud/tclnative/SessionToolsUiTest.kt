package com.xcloud.tclnative

import android.app.Activity
import android.graphics.*
import android.os.Looper
import android.view.*
import android.widget.*
import com.xcloud.tclnative.i18n.*
import com.xcloud.tclnative.settings.*
import com.xcloud.tclnative.ui.dashboard.*
import com.xcloud.tclnative.webrtc.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.*
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowToast
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],qualifiers="w1280dp-h720dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class SessionToolsUiTest {
    @Test fun sessionStartNoticeDisplaysConfirmedTimeAndDisappearsAfterFiveSeconds() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val notice = com.xcloud.tclnative.ui.SessionStartNotice(activity)
        activity.setContentView(notice)
        layout(notice)
        try {
            notice.show(null)
            assertTrue(all(notice).filterIsInstance<TextView>().any { it.text.toString().contains("Tempo da sessão não informado") })
            notice.measure(View.MeasureSpec.makeMeasureSpec(155, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(280, View.MeasureSpec.AT_MOST))
            notice.layout(0, 0, notice.measuredWidth, notice.measuredHeight)
            val heading = notice.getChildAt(0) as TextView
            assertTrue(heading.lineCount > 1)
            assertEquals("Sua sessão foi iniciada", heading.text.toString())
            assertEquals(heading.length(), heading.layout.getLineEnd(heading.lineCount - 1))
            assertEquals(0, heading.layout.getEllipsisCount(heading.lineCount - 1))
            assertTrue(heading.bottom < notice.height)
            for ((seconds, expected) in listOf(1800L to "30 min", 7200L to "2 h", 12000L to "3 h 20 min")) {
                notice.update(seconds)
                assertTrue(all(notice).filterIsInstance<TextView>().any { it.text.toString() == "Tempo restante na sessão: $expected" })
            }
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(4_999))
            assertEquals(View.VISIBLE, notice.visibility)
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(1))
            assertEquals(View.GONE, notice.visibility)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Before fun setup() {
        val app=RuntimeEnvironment.getApplication()
        app.getSharedPreferences("stream_statistics",0).edit().clear().commit()
        UiText.apply(app,AppLanguage.PORTUGUESE);DashboardTheme.apply(app,UiTheme.GREEN)
    }
    @After fun cleanup() { ShadowDialog.getLatestDialog()?.dismiss();DashboardTheme.apply(RuntimeEnvironment.getApplication(),UiTheme.GREEN) }
    private fun all(v: View): List<View> = listOf(v)+if(v is ViewGroup) (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else emptyList()
    private fun layout(v: View,w: Int=1280,h: Int=720) {
        repeat(3) { v.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));v.layout(0,0,w,h);shadowOf(Looper.getMainLooper()).idle() }
    }
    private fun click(d: ConsoleDialog,text: String)=all(d.panel).filterIsInstance<TextView>().single { it.isClickable && it.text.contains(text) }.performClick()
    private fun latest()=ShadowDialog.getLatestDialog() as ConsoleDialog
    private fun capture(v: View,name: String) {
        val bitmap=Bitmap.createBitmap(v.width,v.height,Bitmap.Config.ARGB_8888);v.draw(Canvas(bitmap))
        File("build/reports/dashboard/session-tools-$name.png").apply { parentFile!!.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
    }
    private fun capture(d: ConsoleDialog,name: String) {
        val root=d.window!!.decorView;layout(root,d.window!!.attributes.width,648);capture(root,name)
    }
    @Test fun openingHudSettingsPreservesNativeTouchAndBothModeChoicesRemainAvailable() {
        val c=Robolectric.buildActivity(Activity::class.java).setup();val a=c.get();a.setContentView(FrameLayout(a));layout(a.window.decorView)
        try {
            val game=com.xcloud.tclnative.signaling.GameTitle("TOUCH-DEMO","Touch game",supportedInputTypes=setOf("NativeTouch"))
            val store=com.xcloud.tclnative.touch.GameHudStore(a);val before=store.read(game.titleId)
            var native=true
            fun panel()=GameHudPanel(a,game,store,{},{},{},{},
                activate=if(native) ({ native=false }) else null,
                nativeTouch=if(native) null else ({ native=true }))
            val first=panel().show();assertTrue(native)
            capture(first,"touch-mode-native")
            assertFalse(all(first.panel).filterIsInstance<TextView>().any { it.isClickable && it.text.contains("Ocultar HUD") })
            click(first,"Usar controles na tela");assertFalse(native)
            val hud=panel().show();capture(hud,"touch-mode-hud")
            click(hud,TouchMode.NATIVE.label);assertTrue(native)
            assertEquals(before,store.read(game.titleId))
        } finally { c.pause().stop().destroy() }
    }
    @Test fun waitingForNativeTouchOffersAManualHudAndQuotaDetailsShowTheSafeFailureReason() {
        val c=Robolectric.buildActivity(Activity::class.java).setup();val a=c.get();a.setContentView(FrameLayout(a));layout(a.window.decorView)
        try {
            var selected=0
            val wait=NativeTouchPanel.waiting(a) { selected++ };capture(wait,"native-touch-waiting")
            assertEquals(0,selected);click(wait,"CONTINUAR AGUARDANDO");assertEquals(0,selected)
            click(NativeTouchPanel.waiting(a) { selected++ },"USAR CONTROLES NA TELA");assertEquals(1,selected)
            val info=com.xcloud.tclnative.catalog.CloudPlaytime(failure=com.xcloud.tclnative.catalog.PlaytimeFailure.HTTP,httpStatus=401).diagnostics()
            val panel=PlaybackSettingsPanel(a,PlaybackSettingsStore(a),{},{},{},{},playtimeInformation={info})
            click(panel.showAccount(),"Detalhes do tempo restante")
            val details=latest();capture(details,"playtime-details")
            assertTrue(all(details.panel).filterIsInstance<TextView>().any { it.text.contains("HTTP 401") })
        } finally { c.pause().stop().destroy() }
    }
    @Test fun endedAllocationHasNoReconnectActionAndReturnsToTheCatalog() {
        val c=Robolectric.buildActivity(Activity::class.java).setup();val a=c.get();a.setContentView(FrameLayout(a));layout(a.window.decorView)
        try {
            var reloads=0;var exits=0
            val dialog=TransmissionRecoveryPanel.failed(a,{reloads++},{exits++},{},sessionEnded=true)
            capture(dialog,"session-ended")
            assertFalse(all(dialog.panel).filterIsInstance<TextView>().any { it.isClickable && it.text.contains("RECONECTAR",true) })
            click(dialog,"VOLTAR AO CATÁLOGO");assertEquals(1,exits);assertEquals(0,reloads)
        } finally { c.pause().stop().destroy() }
    }
    @Test fun eachIndicatorCanBeChosenWithoutLeavingTheDialogAndAtLeastOneStaysEnabled() {
        val c=Robolectric.buildActivity(Activity::class.java).setup();val a=c.get();a.setContentView(FrameLayout(a));layout(a.window.decorView)
        try {
            val store=StatisticsSettingsStore(a);var changes=0
            val panel=StatisticsSettingsPanel(a,store,{changes++})
            val home=panel.show();capture(home,"statistics-settings")
            click(home,"Escolher indicadores");val fields=latest();capture(fields,"statistics-fields")
            click(fields,"Ping (RTT)");click(fields,"Bitrate")
            assertTrue(fields.isShowing);assertEquals(setOf(StatisticField.FPS),store.read().fields)
            val row=all(fields.panel).filterIsInstance<TextView>().single { it.isClickable && it.text.contains("FPS do vídeo") }
            row.requestFocus();row.performClick()
            assertEquals(setOf(StatisticField.FPS),store.read().fields);assertEquals("Selecione pelo menos um indicador.",ShadowToast.getTextOfLatestToast())
            assertTrue(row.hasFocus());assertEquals(2,changes)
            click(fields,"Codec recebido");assertEquals(setOf(StatisticField.FPS,StatisticField.CODEC),store.read().fields)
            click(fields,"Tempo restante de nuvem")
            assertEquals(setOf(StatisticField.FPS,StatisticField.CODEC,StatisticField.PLAYTIME),store.read().fields)
            click(fields,"VOLTAR");click(latest(),"Posição:");click(latest(),"Inferior direito")
            assertEquals(StatisticsPosition.BOTTOM_RIGHT,store.read().position)
            click(latest(),"Modo:");click(latest(),"Completo")
            assertTrue(store.read().detailed);assertTrue(latest().isShowing)
            latest().dismiss()
        } finally { c.pause().stop().destroy() }
    }
    @Test fun overlayPositionsFitPhoneAndTvAndTheDefaultIsOnlyOneLine() {
        val c=Robolectric.buildActivity(Activity::class.java).setup();val a=c.get()
        try {
            for((w,h) in listOf(1280 to 720,1452 to 689)) for(position in StatisticsPosition.entries) {
                val settings=StatisticsSettings(position=position,textSize=14)
                val root=FrameLayout(a).apply { setBackgroundColor(0xff18302b.toInt()) }
                val overlay=TextView(a).apply { typeface=Typeface.MONOSPACE;setTextColor(Color.WHITE)
                    text=CustomStatistics.format(StreamMetrics(fps=60.0,rttMs=43.0,bitrateMbps=10.5),InputMetrics(),settings)
                    isClickable=false;isFocusable=false
                }
                root.addView(overlay);StatisticsOverlayStyle.apply(overlay,settings);StatisticsOverlayStyle.constrain(overlay,w,h);a.setContentView(root);layout(root,w,h)
                assertTrue(overlay.left>=0);assertTrue(overlay.right<=w);assertTrue(overlay.top>=0);assertTrue(overlay.bottom<=h)
                assertEquals(1,overlay.lineCount);assertFalse(overlay.isClickable)
                if(position==StatisticsPosition.BOTTOM_LEFT || position==StatisticsPosition.BOTTOM_RIGHT) assertTrue(overlay.top>h/2)
                else assertTrue(overlay.bottom<h/2)
                if(position==StatisticsPosition.TOP_RIGHT || position==StatisticsPosition.BOTTOM_RIGHT) assertTrue(overlay.right>w/2)
                capture(root,"overlay-${w}x$h-${position.name.lowercase()}")
            }
        } finally { c.pause().stop().destroy() }
    }
    @Test fun recoveryRequiresAnExplicitReloadAndBackNeverDisconnectsTheGame() {
        val c=Robolectric.buildActivity(Activity::class.java).setup();val a=c.get();a.setContentView(FrameLayout(a));layout(a.window.decorView)
        try {
            var repairs=0;var reloads=0;var backs=0;var exits=0
            val repair=TransmissionRecoveryPanel.show(a,{repairs++},{reloads++},{backs++});capture(repair,"recovery-options")
            click(repair,"Recuperar imagem");assertEquals(1,repairs);assertEquals(0,reloads)
            val menu=TransmissionRecoveryPanel.show(a,{repairs++},{reloads++},{backs++})
            click(menu,"Recarregar transmissão");val confirm=latest();capture(confirm,"reload-confirmation")
            assertEquals(0,reloads);assertTrue(all(confirm.panel).filterIsInstance<TextView>().any { it.text.contains("mesma sessão Xbox") })
            confirm.cancel();shadowOf(Looper.getMainLooper()).idle();assertEquals(0,reloads)
            click(latest(),"Recarregar transmissão");click(latest(),"RECARREGAR TRANSMISSÃO");assertEquals(1,reloads)
            val failure=TransmissionRecoveryPanel.failed(a,{reloads++},{exits++},{backs++});capture(failure,"retry")
            failure.cancel();shadowOf(Looper.getMainLooper()).idle();assertEquals(0,exits);assertTrue(backs>0)
            val explicit=TransmissionRecoveryPanel.failed(a,{reloads++},{exits++},{backs++})
            click(explicit,"ENCERRAR PARTIDA");assertEquals(1,exits)
        } finally { c.pause().stop().destroy() }
    }
    @Test fun gameMenuExposesCustomizationAndRecoveryAlongsideTheFastToggle() {
        val c=Robolectric.buildActivity(Activity::class.java).setup();val a=c.get();a.setContentView(FrameLayout(a));layout(a.window.decorView)
        try {
            var toggle=0;var settings=0;var repair=0
            fun menu()=GameMenuPanel.show(a,{}, {toggle++}, {}, {},statisticsOptions={settings++},recovery={repair++})
            click(menu(),"Mostrar / ocultar estatísticas");assertEquals(1,toggle)
            click(menu(),"Personalizar estatísticas");assertEquals(1,settings)
            val last=menu();capture(last,"game-menu");click(last,"Recuperar / recarregar transmissão");assertEquals(1,repair)
        } finally { c.pause().stop().destroy() }
    }
    @Test fun largeDetailedTextStaysWithinTheAvailableHeight() {
        val c=Robolectric.buildActivity(Activity::class.java).setup();val a=c.get()
        try {
            val root=FrameLayout(a)
            val overlay=TextView(a).apply { text=(1..50).joinToString("\n") { "Diagnostic line $it" };typeface=Typeface.MONOSPACE }
            root.addView(overlay);StatisticsOverlayStyle.apply(overlay,StatisticsSettings(detailed=true,textSize=24,position=StatisticsPosition.BOTTOM_RIGHT))
            StatisticsOverlayStyle.constrain(overlay,1280,320);a.setContentView(root);layout(root,1280,320)
            assertTrue(overlay.maxLines in 1..12);assertTrue(overlay.top>=0);assertTrue(overlay.bottom<=320)
            assertTrue(overlay.lineCount<50)
        } finally { c.pause().stop().destroy() }
    }
}
