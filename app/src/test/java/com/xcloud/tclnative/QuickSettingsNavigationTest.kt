package com.xcloud.tclnative

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.*
import android.widget.*
import com.xcloud.tclnative.auth.CloudRegion
import com.xcloud.tclnative.i18n.*
import com.xcloud.tclnative.settings.*
import com.xcloud.tclnative.signaling.GameTitle
import com.xcloud.tclnative.ui.GameTouchView
import com.xcloud.tclnative.ui.dashboard.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.*
import org.robolectric.shadows.ShadowDialog
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],qualifiers="w1280dp-h720dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class QuickSettingsNavigationTest {
    private lateinit var controller: org.robolectric.android.controller.ActivityController<Activity>
    private lateinit var activity: Activity
    @Before fun setup() {
        controller=Robolectric.buildActivity(Activity::class.java).setup();activity=controller.get()
        activity.setContentView(FrameLayout(activity));layout(activity.window.decorView,1280,720)
        UiText.apply(activity,AppLanguage.PORTUGUESE);DashboardTheme.apply(activity,UiTheme.GREEN)
    }
    @After fun cleanup() { ShadowDialog.getLatestDialog()?.dismiss();controller.pause().stop().destroy();DashboardTheme.apply(activity,UiTheme.GREEN) }
    private fun all(view: View): List<View> = listOf(view)+if(view is ViewGroup) (0 until view.childCount).flatMap { all(view.getChildAt(it)) } else emptyList()
    private fun layout(view: View,w: Int,h: Int) { repeat(2) {
        view.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));view.layout(0,0,w,h)
        shadowOf(Looper.getMainLooper()).idle()
    } }
    private fun latest()=ShadowDialog.getLatestDialog() as ConsoleDialog
    private fun click(dialog: ConsoleDialog,label: String) = all(dialog.panel).filterIsInstance<TextView>().single { it.isClickable && it.text.toString().contains(label) }.performClick()
    private fun close(dialog: ConsoleDialog)=all(dialog.panel).single { it.tag=="close-settings" }.performClick()
    private fun capture(dialog: ConsoleDialog,name: String) {
        val root=dialog.window!!.decorView
        layout(root,dialog.window!!.attributes.width,(activity.window.decorView.height*.9).toInt())
        val image=Bitmap.createBitmap(root.width,root.height,Bitmap.Config.ARGB_8888);root.draw(Canvas(image))
        File("build/reports/navigation/$name.png").apply { parentFile!!.mkdirs() }.outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) };image.recycle()
    }
    @Test fun shortcutsAreDirectAndOnlyExplicitConfirmationEndsTheGame() {
        var toggles=0;var stats=0;var video=0;var friends=0;var settings=0;var xbox=0;var ends=0
        fun menu()=GameMenuPanel.show(activity,{}, {stats++}, {}, {ends++},hudToggle={toggles++},hudVisible=true,
            audio={},friends={friends++},video={video++},settings={settings++},xbox={xbox++})
        val first=menu();capture(first,"quick-actions-tv")
        click(first,"Ocultar controle virtual");click(menu(),"Mostrar / ocultar estatísticas")
        click(menu(),"Vídeo e conexão");click(menu(),"Amigos");click(menu(),"Configurações");click(menu(),"Botão Xbox")
        assertEquals(listOf(1,1,1,1,1,1),listOf(toggles,stats,video,friends,settings,xbox))
        click(menu(),"ENCERRAR PARTIDA");assertEquals(0,ends)
        close(latest());assertEquals(0,ends)
        click(menu(),"ENCERRAR PARTIDA");click(latest(),"ENCERRAR PARTIDA");assertEquals(1,ends)
    }
    @Test fun closeLeavesAllSettingsWhileBackReturnsOneLevelAndRestoresCategoryFocus() {
        val store=PlaybackSettingsStore(activity);val before=store.read();var changes=0;var returned=0
        val panel=PlaybackSettingsPanel(activity,store,{changes++},{},{},{},back={returned++})
        click(panel.show(),"Controles e toque");latest().cancel();shadowOf(Looper.getMainLooper()).idle()
        assertEquals("Controles e toque",(latest().window!!.currentFocus as TextView).text.toString())
        click(latest(),"Vídeo e conexão");click(latest(),"Resolução:")
        close(latest());shadowOf(Looper.getMainLooper()).idle()
        assertFalse(latest().isShowing);assertEquals(0,returned);assertEquals(0,changes);assertEquals(before,store.read())
        val root=panel.show();root.cancel();shadowOf(Looper.getMainLooper()).idle();assertEquals(1,returned)
    }
    @Test fun closeAlsoDismissesAParentKeptOpenByAPreviewWithoutCallingBack() {
        var backs=0
        val parent=ConsoleDialogs.menu(activity,"Parent",emptyList(),onBack={backs++})
        val child=ConsoleDialog(activity,"Preview").apply { setOnCancelListener { backs++ };show() }
        close(child);shadowOf(Looper.getMainLooper()).idle()
        assertFalse(child.isShowing);assertFalse(parent.isShowing);assertEquals(0,backs)
    }
    @Test fun unifiedVideoKeepsScopeVisibleAndChineseSelectionSurvivesOtherEdits() {
        val session=SessionOptionsStore(activity,"navigation-scopes")
        val scoped=SessionOptionsPanel(activity,session,listOf(CloudRegion("BrazilSouth","https://example.test")),"game","Demo") {}
        val panel=PlaybackSettingsPanel(activity,PlaybackSettingsStore(activity),{},{},{},{},sessionPanel=scoped)
        val video=panel.showVideo();capture(video,"unified-video");click(video,"Rede e sessão");capture(latest(),"game-session-scope")
        assertTrue(all(latest().body).filterIsInstance<TextView>().any { it.text.toString()=="Este jogo: Demo" })
        click(latest(),"Idioma do jogo:");click(latest(),"简体中文")
        click(latest(),"Idioma do jogo:");capture(latest(),"game-language-chinese")
        assertTrue(all(latest().body).filterIsInstance<TextView>().any { it.text.contains("✓") && it.text.contains("简体中文") })
        latest().cancel();shadowOf(Looper.getMainLooper()).idle()
        click(latest(),"Bitrate máximo:");click(latest(),"15.0 Mbps")
        click(latest(),"Região:");click(latest(),"BrazilSouth")
        assertEquals(SessionOptions(15000,"zh-CN","BrazilSouth"),session.read("game"))
        close(latest());assertEquals("zh-CN",SessionOptionsStore(activity,"navigation-scopes").read("game").locale)
        val reopen=panel.showVideo();click(reopen,"Padrão das partidas")
        click(latest(),"Idioma do jogo:");click(latest(),"English (US)")
        assertEquals("en-US",session.read().locale);assertEquals("zh-CN",session.read("game").locale)
        capture(latest(),"global-session-scope")
    }
    @Test fun longStatisticsShortcutOpensCustomizationWithoutTogglingAndMediaVolumeIsExplicit() {
        var customizations=0;var toggles=0;var updates=0
        val menu=GameMenuPanel.show(activity,{}, {toggles++}, {}, {},statisticsOptions={customizations++})
        val stats=all(menu.body).filterIsInstance<TextView>().single { it.text.contains("Mostrar / ocultar estatísticas") }
        assertTrue(stats.performLongClick());assertEquals(1,customizations);assertEquals(0,toggles)
        val audio=activity.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
        val panel=PlaybackSettingsPanel(activity,PlaybackSettingsStore(activity),{},{},{},{},statisticsChanged={updates++})
        click(panel.showAudio(),"Volume de mídia:")
        all(latest().body).filterIsInstance<TextView>().single { it.text.toString().replace("✓", "").replace("○", "").trim()=="0" }.performClick()
        assertEquals(0,audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC))
        close(latest());click(panel.showDiagnostics(),"Estatísticas durante a partida")
        click(latest(),"Escolher indicadores");click(latest(),"Tempo restante de nuvem")
        assertEquals(1,updates)
    }
    @Test fun controllerLongXClearsOnlyTheSearchFieldWithoutSubmittingOrLeaving() {
        var searches=0
        val search=ConsoleDialogs.search(activity,"Genshin") { searches++ }
        val field=all(search.body).filterIsInstance<EditText>().single()
        search.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_BUTTON_X))
        assertEquals("Genshin",field.text.toString())
        search.dispatchKeyEvent(KeyEvent(0,600,KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_BUTTON_X,1))
        assertEquals("",field.text.toString());assertEquals(0,searches);assertTrue(search.isShowing)
        close(search);assertEquals(0,searches)
    }
    @Test fun menuIsAnAccessibleNativeButtonAndRemainsAvailableWithNativeTouchOrHiddenHud() {
        var menus=0;var pointers=0
        val view=GameTouchView(activity,{},{pointers+=it.size},{x,y->x to y},{menus++})
        activity.setContentView(view);layout(view,780,360)
        for(mode in listOf(TouchMode.NATIVE,TouchMode.VIRTUAL)) {
            view.configure(mode,GameTitle("demo","Demo"),com.xcloud.tclnative.touch.HudSettings(autoHide=com.xcloud.tclnative.touch.HudAutoHide.PHYSICAL));view.onWindowFocusChanged(true)
            if(mode==TouchMode.VIRTUAL) { view.physicalInput(com.xcloud.tclnative.webrtc.GamepadState(buttons=16));assertFalse(view.controlsVisible) }
            layout(view,780,360)
            val button=all(view).filterIsInstance<TextView>().single { it.contentDescription=="Atalhos da partida" }
            assertTrue(button.isShown);assertTrue(button.width>=48*activity.resources.displayMetrics.density)
            assertTrue(button.height>=48*activity.resources.displayMetrics.density)
            val now=android.os.SystemClock.uptimeMillis()
            for(action in listOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP)) MotionEvent.obtain(now,now,action,
                button.left+button.width/2f,button.top+button.height/2f,0).let { assertTrue(view.dispatchTouchEvent(it));it.recycle() }
            shadowOf(Looper.getMainLooper()).idle()
        }
        assertEquals(2,menus);assertEquals(0,pointers)
    }
    @Test fun phoneAndConsoleThemesHaveReachableCloseAndTouchTargets() {
        for((w,h) in listOf(780 to 360,360 to 780,1280 to 720)) for(theme in listOf(UiTheme.GREEN,UiTheme.XBOX_360,UiTheme.XBOX_SERIES)) {
            layout(activity.window.decorView,w,h);DashboardTheme.apply(activity,theme)
            val dialog=GameMenuPanel.show(activity,{},{},{},{},hudToggle={},audio={},friends={},settings={},nativeTouch={},touchSelection={},xbox={})
            capture(dialog,"quick-${w}x${h}-${theme.name.lowercase()}")
            val close=all(dialog.panel).single { it.tag=="close-settings" }
            assertTrue(close.width>=48);assertTrue(close.height>=48)
            assertTrue(close.left>=0 && close.right<=dialog.panel.width)
            val buttons=all(dialog.body).filterIsInstance<TextView>().filter { it.isClickable }
            assertTrue(buttons.all { it.height>=48 })
            if(w==780) buttons.forEach { button ->
                // Compare hit bounds rather than TextView's internally scrolled text rectangle.
                val viewport=dialog.body.parent as ScrollView
                val origin=IntArray(2);viewport.getLocationOnScreen(origin)
                val frame=android.graphics.Rect(origin[0],origin[1],origin[0]+viewport.width,origin[1]+viewport.height)
                button.getLocationOnScreen(origin)
                val target=android.graphics.Rect(origin[0],origin[1],origin[0]+button.width,origin[1]+button.height)
                assertTrue("$theme $w x $h: ${button.text}; viewport=$frame, target=$target",frame.contains(target))
            }
            close(dialog)
        }
    }
    @Test fun accountActionsCannotReplaceTheCatalogOrLogOutDuringAGame() {
        val panel=PlaybackSettingsPanel(activity,PlaybackSettingsStore(activity),{},{},{},{},allowAccountChanges=false)
        val labels=all(panel.showAccount().body).filterIsInstance<TextView>().filter { it.isClickable }.map { it.text.toString() }
        assertFalse(labels.any { it=="Trocar conta" || it=="Atualizar catálogo" })
    }
    @Test fun playStationQuickMenusKeepResumeCloseAndExplicitEndConfirmation() {
        for(theme in listOf(UiTheme.PS4,UiTheme.PS5,UiTheme.PS4_NIGHT,UiTheme.PS5_NIGHT)) {
            DashboardTheme.apply(activity,theme);var ends=0;var toggles=0
            fun menu()=GameMenuPanel.show(activity,{},{},{},{ends++},hudToggle={toggles++},audio={},friends={},settings={})
            val first=menu();capture(first,"${theme.name.lowercase()}-quick-menu")
            all(first.panel).filterIsInstance<TextView>().filter { it.isClickable && it.text.toString() in listOf("RETOMAR PARTIDA","ENCERRAR PARTIDA") }.forEach { action ->
                assertTrue("$theme action text is clipped",action.layout.getLineBottom(action.layout.lineCount-1)<=action.height-action.paddingTop-action.paddingBottom)
            }
            click(first,"Mostrar controle virtual");assertEquals(1,toggles);assertEquals(0,ends)
            close(menu());assertEquals(0,ends)
            click(menu(),"ENCERRAR PARTIDA");assertEquals(0,ends)
            close(latest());assertEquals(0,ends)
            click(menu(),"ENCERRAR PARTIDA");click(latest(),"ENCERRAR PARTIDA");assertEquals(1,ends)
        }
    }
}
