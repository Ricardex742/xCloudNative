package com.xcloud.tclnative

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.xcloud.tclnative.catalog.*
import com.xcloud.tclnative.i18n.*
import com.xcloud.tclnative.settings.*
import com.xcloud.tclnative.signaling.*
import com.xcloud.tclnative.ui.dashboard.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.*
import org.robolectric.shadows.ShadowDialog
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk=[28],qualifiers="w1280dp-h720dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class NativePlayStationSurfacesTest {
    @org.junit.After fun resetAppearance() {
        val app=org.robolectric.RuntimeEnvironment.getApplication()
        UiText.apply(app,AppLanguage.PORTUGUESE);DashboardTheme.apply(app,UiTheme.GREEN)
        AppearanceSettingsStore(app).saveMotion(BackgroundMotion.OFF)
    }
    private val themes=listOf(UiTheme.PS4,UiTheme.PS5,UiTheme.PS4_NIGHT,UiTheme.PS5_NIGHT)
    private fun all(v: View): List<View> = listOf(v)+if(v is ViewGroup) (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else emptyList()
    private fun layout(v: View,w: Int=1280,h: Int=720) {
        // The catalog worker runs on a real thread. Advancing the paused Main clock alone
        // does not finish a resumed catalog or its pending focus request on a busy runner.
        (v as? GameDashboardView)?.awaitCatalog()
        repeat(3) {
        v.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));v.layout(0,0,w,h)
        shadowOf(Looper.getMainLooper()).idleFor(32,TimeUnit.MILLISECONDS)
    } }
    private fun capture(v: View,name: String) {
        val b=Bitmap.createBitmap(v.width,v.height,Bitmap.Config.ARGB_8888);v.draw(Canvas(b))
        File("build/reports/themes/native-ps-$name.png").apply { parentFile!!.mkdirs() }.outputStream().use { b.compress(Bitmap.CompressFormat.PNG,100,it) };b.recycle()
    }
    private fun inside(root: ViewGroup,target: View) {
        val r=Rect(0,0,target.width,target.height);root.offsetDescendantRectToMyCoords(target,r)
        assertTrue("${target.contentDescription ?: (target as? TextView)?.text} is outside ${root.width}x${root.height}: $r",Rect(0,0,root.width,root.height).contains(r))
    }
    private fun game()=GameTitle("NATIVE-PS","A very long real catalog title: Anniversary Edition — Part Two",gamePass=true,seriesOptimized=true)
    @Test fun ps4HomeKeepsTheSuppliedScreenshotHierarchyAndVisibleFocusRoutes() {
        val c=Robolectric.buildActivity(Activity::class.java).setup();val a=c.get()
        try {
            for(theme in listOf(UiTheme.PS4,UiTheme.PS4_NIGHT)) {
                DashboardTheme.apply(a,theme)
                val title="Native reference title"
                val view=GameDashboardView(a,GameLibrary(a,"native-reference-${theme.name}"),{},{},{},{})
                try {
                    a.setContentView(view);view.setGames((0..3).map { game().copy(titleId="REF-$it",name=if(it==0) title else "Other $it") });view.selectTab(LibraryTab.ALL)
                    view.awaitCatalog();layout(view);view.restoreFocus();layout(view)
                    val grid=all(view).filterIsInstance<RecyclerView>().single()
                    val selected=grid.findViewHolderForAdapterPosition(0)!!.itemView
                    val neighbor=grid.findViewHolderForAdapterPosition(1)!!.itemView
                    val cover=all(selected).filterIsInstance<FixedArtworkView>().single().parent as View
                    val other=all(neighbor).filterIsInstance<FixedArtworkView>().single().parent as View
                    assertTrue(kotlin.math.abs(cover.width-cover.height)<=2)
                    assertTrue(kotlin.math.abs(other.width-other.height)<=2)
                    assertTrue("Selected PS4 cover must be enlarged",cover.width>other.width*1.2f)
                    val label=all(view).filterIsInstance<TextView>().single { it.isShown && it.text.toString()==title }
                    val bounds=Rect(0,0,cover.width,cover.height);view.offsetDescendantRectToMyCoords(cover,bounds)
                    assertTrue("Title belongs below the covers in the supplied screenshot",label.top>=bounds.bottom+30)
                    assertTrue(label.left<bounds.right)
                    val library=all(view).filterIsInstance<TextView>().single { it.isShown && it.isClickable && it.text.toString()=="Biblioteca" }
                    assertTrue(library.top<grid.top)
                    val down=view.findViewById<View>(selected.nextFocusDownId)
                    assertNotNull(down);assertTrue("Down must not point at a hidden launch button",down.isShown && down.isFocusable)
                    capture(view,"${theme.name.lowercase()}-console-reference")
                } finally { view.close() }
            }
        } finally { c.pause().stop().destroy() }
    }
    @Test fun ps5LibraryTileFollowsCatalogResultsAndSearchWithoutAViewportResize() {
        val c=Robolectric.buildActivity(Activity::class.java).setup();val a=c.get();DashboardTheme.apply(a,UiTheme.PS5)
        val view=GameDashboardView(a,GameLibrary(a,"native-async-shelf"),{},{},{},{})
        try {
            a.setContentView(view);layout(view)
            val grid=all(view).filterIsInstance<RecyclerView>().single()
            val library=all(view).single { it.isShown && it.isClickable && it.contentDescription=="Biblioteca" }
            val emptyX=library.left
            fun measureBeforeWorkerReturns() {
                view.measure(View.MeasureSpec.makeMeasureSpec(1280,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(720,View.MeasureSpec.EXACTLY));view.layout(0,0,1280,720)
            }
            view.setGames((0..5).map { game().copy(titleId="SHELF-$it",name="Native shelf $it") });view.selectTab(LibraryTab.ALL);measureBeforeWorkerReturns();layout(view)
            fun followsLast(count: Int) {
                assertEquals(count,grid.adapter!!.itemCount)
                val last=grid.findViewHolderForAdapterPosition(count-1)!!.itemView
                val bounds=Rect(0,0,last.width,last.height);view.offsetDescendantRectToMyCoords(last,bounds)
                assertTrue("Library overlaps a cover",library.left>=bounds.right)
                assertTrue("Library must follow the loaded shelf",library.left-bounds.right<=40)
                inside(view,library)
            }
            followsLast(6);val fullX=library.left;assertTrue(fullX<emptyX)
            view.setSearch("Native shelf 0");measureBeforeWorkerReturns();layout(view);followsLast(1);assertTrue(library.left<fullX)
            view.setSearch("no matching game");measureBeforeWorkerReturns();layout(view);assertEquals(0,grid.adapter!!.itemCount);assertEquals(emptyX,library.left)
            view.setSearch("");measureBeforeWorkerReturns();layout(view);followsLast(6);assertEquals(fullX,library.left)
        } finally { view.close();c.pause().stop().destroy() }
    }
    @Test fun ps5ScreenshotQuickCardsAndOptionsOpenSharedSettingsWithRealQuota() {
        val c=Robolectric.buildActivity(Activity::class.java).setup();val a=c.get();UiText.apply(a,AppLanguage.PORTUGUESE)
        try {
            for(theme in listOf(UiTheme.PS5,UiTheme.PS5_NIGHT)) {
                DashboardTheme.apply(a,theme);val opened=mutableListOf<SettingsPage>()
                val view=GameDashboardView(a,GameLibrary(a,"native-quick-${theme.name}"),{},{},{},{},onThemeSettings={ opened+=it })
                try {
                    a.setContentView(view);view.setGames(listOf(game()));view.selectTab(LibraryTab.ALL);layout(view)
                    val options=all(view).filterIsInstance<TextView>().single { it.isShown && it.isClickable && it.text.toString()=="•••" }
                    inside(view,options)
                    val controls=all(view).single { it.isShown && it.isClickable && it.contentDescription=="Controles e toque" }
                    val video=all(view).single { it.isShown && it.isClickable && it.contentDescription=="Vídeo e conexão" }
                    val account=all(view).single { it.isShown && it.isClickable && it.contentDescription?.toString()?.startsWith("Conta e amigos.")==true }
                    listOf(controls,video,account).forEach { inside(view,it) }
                    controls.performClick();video.performClick();assertEquals(listOf(SettingsPage.CONTROLS,SettingsPage.VIDEO),opened);opened.clear()
                    view.setCloudPlaytime(CloudPlaytime());layout(view)
                    assertTrue(all(view).filterIsInstance<TextView>().any { it.isShown && it.text.toString()=="—" })
                    assertFalse(all(view).filterIsInstance<TextView>().any { it.isShown && it.text.contains("ilimitado",true) })
                    view.setCloudPlaytime(CloudPlaytime(CloudPlaytimeStatus.LIMITED,42*60));layout(view)
                    assertTrue(all(view).filterIsInstance<TextView>().any { it.isShown && it.text.toString()=="42min" })
                    for(label in listOf("Controles e toque","Vídeo e conexão")) {
                        options.performClick();val dialog=ShadowDialog.getLatestDialog() as ConsoleDialog
                        layout(dialog.panel)
                        all(dialog.body).filterIsInstance<TextView>().single { it.text.toString()==label }.performClick()
                    }
                    assertEquals(listOf(SettingsPage.CONTROLS,SettingsPage.VIDEO),opened)
                    capture(view,"${theme.name.lowercase()}-game-hub")
                } finally { view.close() }
            }
        } finally { DashboardTheme.apply(a,UiTheme.GREEN);c.pause().stop().destroy() }
    }
    @Test fun queueAndTransitionsKeepRealStatesAndAccessibleCancelAtTvPhoneAndPortraitSizes() {
        val c=Robolectric.buildActivity(Activity::class.java).setup();val a=c.get();UiText.apply(a,AppLanguage.PORTUGUESE)
        try {
            for(theme in themes) {
                DashboardTheme.apply(a,theme)
                var cancels=0;val queue=GameLoadingView(a) { cancels++ };a.setContentView(queue)
                try {
                    queue.begin(game(),0);queue.updateQueue(QueueStatus(0,7000,QueueEstimate(787),0),8000)
                    for((w,h) in listOf(1280 to 720,1920 to 1080,1452 to 689,720 to 1280)) {
                        layout(queue,w,h)
                        val cancel=all(queue).single { it.contentDescription=="Cancelar espera e voltar ao catálogo" }
                        assertTrue(cancel.width>=48*a.resources.displayMetrics.density && cancel.height>=48*a.resources.displayMetrics.density)
                        inside(queue,cancel);assertTrue(cancel.isFocusable && cancel.isClickable)
                        assertFalse(all(queue).any { it is android.webkit.WebView })
                        assertEquals(LoadingPhase.QUEUED,queue.phase)
                        val labels=all(queue).filterIsInstance<TextView>().map { it.text.toString() }
                        assertTrue(labels.any { it.contains("13 min 7s") });assertFalse(labels.any { it.contains('%') })
                        capture(queue,"${theme.name.lowercase()}-queue-${w}x$h")
                    }
                    queue.render(128000)
                    assertTrue(all(queue).filterIsInstance<TextView>().any { it.text.contains("13 min 7s") })
                    queue.updateProgress("SDP: negotiation");assertEquals(LoadingPhase.CONNECTING,queue.phase)
                    queue.updateProgress("VIDEO: first packet");assertEquals(LoadingPhase.VIDEO,queue.phase)
                    queue.cancelFromController();assertEquals(1,cancels)
                } finally { queue.close() }
                val transition=SessionTransitionView(a);a.setContentView(transition)
                try {
                    transition.showStartup(game(),"Etapa real de conexão");layout(transition)
                    assertTrue(all(transition).filterIsInstance<TextView>().any { it.text==game().name })
                    assertTrue(all(transition).filterIsInstance<TextView>().any { it.text=="Etapa real de conexão" })
                    capture(transition,"${theme.name.lowercase()}-startup")
                    transition.showExit(game());layout(transition);assertTrue(transition.exiting)
                    capture(transition,"${theme.name.lowercase()}-exit")
                    transition.dismiss();assertEquals(View.GONE,transition.visibility)
                } finally { transition.close() }
            }
        } finally { c.pause().stop().destroy() }
    }
    @Test fun sixSettingsCategoriesAndDeepPanelsRemainNativeAcrossAllInterfaceLanguages() {
        val c=Robolectric.buildActivity(Activity::class.java).setup();val a=c.get();a.setContentView(FrameLayout(a));layout(a.window.decorView)
        try {
            val store=PlaybackSettingsStore(a);val original=store.read()
            for(theme in listOf(UiTheme.PS4,UiTheme.PS5)) for(language in AppLanguage.entries) {
                UiText.apply(a,language);DashboardTheme.apply(a,theme)
                val panel=PlaybackSettingsPanel(a,store,{fail("Presentation must not alter playback")},{},{},{})
                val root=panel.show();layout(root.panel,1100,648)
                val categories=all(root.body).filterIsInstance<TextView>().filter { it.isClickable }
                assertEquals(6,categories.size)
                assertFalse(all(root.panel).any { it is android.webkit.WebView })
                assertTrue(categories.all { it.width>0 && it.height>=48*a.resources.displayMetrics.density })
                capture(root.panel,"${theme.name.lowercase()}-${language.name.lowercase()}-settings")
                root.dismiss()
                val deep=panel.showAdvancedImage();layout(deep.panel,1100,648)
                assertTrue(all(deep.body).any { it.isClickable });capture(deep.panel,"${theme.name.lowercase()}-${language.name.lowercase()}-advanced-image")
                all(deep.panel).single { it.tag=="close-settings" }.performClick()
                assertFalse(deep.isShowing);assertEquals(original,store.read())
            }
        } finally { UiText.apply(a,AppLanguage.PORTUGUESE);c.pause().stop().destroy() }
    }
    @Test @Config(qualifiers="w800dp-h360dp-land-xxhdpi")
    fun landscapePhoneMenusKeepHudAndCloseAccessibleWithoutEndingTheGame()=phoneMenus()
    @Test @Config(qualifiers="w360dp-h800dp-port-xxhdpi")
    fun portraitPhoneMenusKeepHudAndCloseAccessibleWithoutEndingTheGame()=phoneMenus()
    private fun phoneMenus() {
        val c=Robolectric.buildActivity(Activity::class.java).setup();val a=c.get();UiText.apply(a,AppLanguage.PORTUGUESE)
        val metrics=a.resources.displayMetrics;val w=metrics.widthPixels;val h=metrics.heightPixels
        a.setContentView(FrameLayout(a));layout(a.window.decorView,w,h)
        try {
            for(theme in themes) {
                DashboardTheme.apply(a,theme);var ends=0;var toggles=0
                fun menu(): ConsoleDialog {
                    val d=GameMenuPanel.show(a,{},{},{},{ends++},hudToggle={toggles++},audio={},friends={},settings={})
                    val requested=d.window!!.attributes.width;layout(d.panel,if(requested>0) requested else w,h)
                    return d
                }
                val first=menu()
                val hud=all(first.panel).filterIsInstance<TextView>().single { it.isClickable && it.text.toString()=="Mostrar controle virtual" }
                val close=all(first.panel).single { it.tag=="close-settings" }
                for(target in listOf(hud,close)) {
                    val hit=ConsoleHitTargets.bounds(first.panel,target)
                    assertTrue("$theme touch target",hit.width()>=48*metrics.density && hit.height()>=48*metrics.density)
                    inside(first.panel,target)
                }
                all(first.panel).filterIsInstance<TextView>().filter { it.isClickable && it.text.toString() in listOf("RETOMAR PARTIDA","ENCERRAR PARTIDA") }.forEach { action ->
                    assertNotNull(action.layout)
                    assertTrue("$theme action text must fit",action.layout.getLineBottom(action.layout.lineCount-1)<=action.height-action.paddingTop-action.paddingBottom)
                }
                capture(first.panel,"${theme.name.lowercase()}-game-menu-${w}x$h")
                hud.performClick();assertEquals(1,toggles);assertEquals(0,ends)
                val second=menu();all(second.panel).single { it.tag=="close-settings" }.performClick()
                assertFalse(second.isShowing);assertEquals(0,ends)
                val third=menu();third.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_BUTTON_B))
                assertFalse(third.isShowing);assertEquals(0,ends)
            }
        } finally { c.pause().stop().destroy() }
    }
    @Test @Config(qualifiers="w800dp-h360dp-land-xxhdpi")
    fun phonePrimaryActionsKeep48DpTargetsAndSelectionAcrossPanelsAndPlaybackPauses() {
        val c=Robolectric.buildActivity(Activity::class.java).setup();val a=c.get();UiText.apply(a,AppLanguage.PORTUGUESE)
        try {
            for(theme in themes) {
                DashboardTheme.apply(a,theme);val played=mutableListOf<String>();var menus=0
                val view=GameDashboardView(a,GameLibrary(a,"native-phone-${theme.name}"),{ played+=it.titleId },{},{menus++},{})
                try {
                    a.setContentView(view);view.setGames((0..20).map { game().copy(titleId="PHONE-$it",name="Jogo $it") });view.selectTab(LibraryTab.ALL)
                    layout(view,2400,1080)
                    view.restoreFocus();layout(view,2400,1080)
                    val touch=48*a.resources.displayMetrics.density
                    for(label in listOf("Pesquisar jogos","Configurações",if(theme.scene==ConsoleScene.PS5) "Opções:" else "FAVORITAR")) {
                        val target=all(view).single { it.isShown && it.contentDescription?.toString()?.startsWith(label)==true }
                        val hit=ConsoleHitTargets.bounds(view,target)
                        assertTrue("$theme $label touch area $hit < $touch",hit.width()>=touch && hit.height()>=touch);inside(view,target)
                    }
                    val grid=all(view).filterIsInstance<RecyclerView>().single();val adapter=grid.adapter
                    assertNotNull(view.selectedGame);val selected=view.selectedGame!!.titleId
                    view.handleKey(KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_BUTTON_START));assertEquals(1,menus);assertTrue(played.isEmpty())
                    val settings=ConsoleDialogs.menu(a,"Painel",listOf(ConsoleAction("Voltar") {}));settings.cancel();view.restoreFocus();layout(view,2400,1080)
                    assertEquals(selected,view.selectedGame!!.titleId);assertTrue(played.isEmpty())
                    view.pauseArtwork();val layer=all(view).filterIsInstance<ConsoleThemeBackdrop>().single();assertFalse(layer.framePending)
                    view.resumeArtwork();view.restoreFocus();layout(view,2400,1080)
                    assertSame(adapter,grid.adapter);assertEquals(selected,view.selectedGame!!.titleId);assertTrue(played.isEmpty())
                    assertTrue("$theme catalog must regain focus after playback",grid.hasFocus())
                    repeat(24) { view.handleKey(KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_RIGHT));layout(view,2400,1080) }
                    assertFalse(view.isCatalogUpdating);layout(view,2400,1080)
                    assertEquals("$theme held D-pad must reach the last game","PHONE-20",view.selectedGame!!.titleId)
                    val focused=view.findFocus();assertNotNull(focused);inside(view,focused!!)
                    var rebinds=0
                    val observer=object: RecyclerView.AdapterDataObserver() {
                        override fun onItemRangeChanged(positionStart: Int,itemCount: Int,payload: Any?) { rebinds++ }
                        override fun onChanged() { rebinds++ }
                    }
                    adapter!!.registerAdapterDataObserver(observer)
                    AppearanceSettingsStore(a).saveMotion(BackgroundMotion.ON)
                    layer.onWindowFocusChanged(true);layer.onVisibilityAggregated(true)
                    val ticks=layer.renderedTicks;shadowOf(Looper.getMainLooper()).idleFor(500,TimeUnit.MILLISECONDS)
                    assertTrue("$theme background did not advance",layer.renderedTicks>ticks);assertEquals(0,rebinds)
                    layer.onWindowFocusChanged(false);assertFalse(layer.framePending)
                    val stopped=layer.renderedTicks;shadowOf(Looper.getMainLooper()).idleFor(500,TimeUnit.MILLISECONDS);assertEquals(stopped,layer.renderedTicks)
                    adapter.unregisterAdapterDataObserver(observer)
                    capture(view,"${theme.name.lowercase()}-phone-xxhdpi")
                } finally { view.close() }
            }
        } finally { c.pause().stop().destroy() }
    }
}
