package com.xcloud.tclnative

import android.app.Activity
import android.graphics.*
import android.os.Looper
import android.view.*
import android.widget.*
import androidx.recyclerview.widget.RecyclerView
import com.xcloud.tclnative.catalog.*
import com.xcloud.tclnative.i18n.*
import com.xcloud.tclnative.settings.*
import com.xcloud.tclnative.signaling.*
import com.xcloud.tclnative.ui.dashboard.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.*
import java.io.File
import java.util.concurrent.TimeUnit

/** Native phone review fixtures. The surrounding bars/cutout are simulated, not device measurements. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],qualifiers="w360dp-h760dp-port-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class PlayStationPhoneReviewTest {
    data class Phone(val name: String,val w: Int,val h: Int,val density: Int,val safe: Rect) {
        val qualifiers get()="w${w/density}dp-h${h/density}dp-${if(w>h) "land" else "port"}-${if(density==2) "xhdpi" else "xxhdpi"}"
        val cw get()=w-safe.left-safe.right
        val ch get()=h-safe.top-safe.bottom
    }
    private val phones=listOf(
        Phone("720-bars",1280,720,2,Rect(0,48,0,48)),
        Phone("720-landscape",1520,720,2,Rect(34,0,34,31)),
        Phone("1080-bars",2400,1080,3,Rect(72,72,72,72)),
        Phone("1080-landscape",2400,1080,3,Rect(72,0,72,72))
    )
    private val longName="Call of Duty®: Black Ops 6 — Cross-Gen Bundle para Xbox Series X|S e Xbox One"
    private fun all(v: View): List<View> = listOf(v)+if(v is ViewGroup) (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else emptyList()
    private fun measure(v: View,w: Int,h: Int,exactHeight: Boolean=true) {
        (v as? GameDashboardView)?.awaitCatalog()
        repeat(3) {
            v.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,if(exactHeight) View.MeasureSpec.EXACTLY else View.MeasureSpec.AT_MOST))
            v.layout(0,0,v.measuredWidth,v.measuredHeight);shadowOf(Looper.getMainLooper()).idleFor(32,TimeUnit.MILLISECONDS)
        }
    }
    private fun attach(a: Activity,v: View,p: Phone) {
        val frame=FrameLayout(a);a.setContentView(frame)
        frame.addView(v,FrameLayout.LayoutParams(p.cw,p.ch).apply { leftMargin=p.safe.left;topMargin=p.safe.top })
        measure(a.window.decorView,p.w,p.h);measure(v,p.cw,p.ch)
        assertEquals(p.cw,v.width);assertEquals(p.ch,v.height)
    }
    private fun capture(v: View,p: Phone,name: String,offset: Point=Point()) {
        val b=Bitmap.createBitmap(p.w,p.h,Bitmap.Config.ARGB_8888);val c=Canvas(b);c.drawColor(Color.BLACK)
        val save=c.save();c.translate((p.safe.left+offset.x).toFloat(),(p.safe.top+offset.y).toFloat());c.clipRect(0,0,v.width,v.height);v.draw(c);c.restoreToCount(save)
        val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.WHITE;textSize=12f*p.density }
        if(p.safe.top>0) c.drawText("12:00",16f*p.density,17f*p.density,paint)
        if(p.safe.bottom>0) { paint.strokeWidth=4f*p.density;c.drawLine(p.w/2f-36*p.density,(p.h-10*p.density).toFloat(),p.w/2f+36*p.density,(p.h-10*p.density).toFloat(),paint) }
        val dir=File("build/reports/themes/phone-review").apply { mkdirs() }
        File(dir,"${p.name}-$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG,100,it) };b.recycle()
        val rows=all(v).filterIsInstance<TextView>().filter { it.isShown }.map { t ->
            val r=if(v is ViewGroup) bounds(v,t) else Rect(0,0,t.width,t.height)
            val l=t.layout
            "${t.text.toString().replace('\n',' ')}\t$r\tfont=${t.textSize/p.density}\tlines=${l?.lineCount}\ttextBottom=${l?.let { if(it.lineCount>0) it.getLineBottom(it.lineCount-1) else 0 }}\tavailable=${t.height-t.compoundPaddingTop-t.compoundPaddingBottom}\tellipses=${l?.let { (0 until it.lineCount).sumOf { n -> it.getEllipsisCount(n) } }}"
        }
        File(dir,"${p.name}-$name.txt").writeText(rows.joinToString("\n"))
    }
    private fun dialog(d: ConsoleDialog,p: Phone,name: String) {
        val requested=d.window!!.attributes.width
        measure(d.panel,if(requested>0) minOf(requested,p.cw) else p.cw,p.ch,false)
        val a=d.window!!.attributes
        val x=when(a.gravity and Gravity.HORIZONTAL_GRAVITY_MASK) { Gravity.RIGHT -> p.cw-d.panel.width-a.x;Gravity.CENTER_HORIZONTAL -> (p.cw-d.panel.width)/2+a.x;else -> a.x }
        val y=when(a.gravity and Gravity.VERTICAL_GRAVITY_MASK) { Gravity.BOTTOM -> p.ch-d.panel.height-a.y;Gravity.CENTER_VERTICAL -> (p.ch-d.panel.height)/2+a.y;else -> a.y }
        capture(d.panel,p,name,Point(x.coerceAtLeast(0),y.coerceAtLeast(0)))
    }
    @After fun reset() { val a=RuntimeEnvironment.getApplication();AppearanceSettingsStore(a).save(UiTheme.GREEN);DashboardTheme.apply(a,UiTheme.GREEN);UiText.apply(a,AppLanguage.PORTUGUESE) }
    @Test fun nativePhoneReviewCaptures() {
        for(p in phones) {
            RuntimeEnvironment.setQualifiers(p.qualifiers)
            val controller=Robolectric.buildActivity(Activity::class.java).setup();val a=controller.get();UiText.apply(a,AppLanguage.PORTUGUESE)
            try {
                for(theme in listOf(UiTheme.PS4,UiTheme.PS5,UiTheme.PS4_NIGHT,UiTheme.PS5_NIGHT)) {
                    AppearanceSettingsStore(a).save(theme);DashboardTheme.apply(a,theme);AppearanceSettingsStore(a).saveMotion(BackgroundMotion.OFF)
                    val frame=FrameLayout(a);a.setContentView(frame);measure(a.window.decorView,p.w,p.h)
                    fun mount(v: View) {
                        frame.removeAllViews();frame.addView(v,FrameLayout.LayoutParams(p.cw,p.ch).apply { leftMargin=p.safe.left;topMargin=p.safe.top })
                        measure(frame,p.w,p.h);measure(v,p.cw,p.ch)
                    }
                    val library=GameLibrary(a,"phone-review-${theme.name}-${p.name}")
                    val data=(0..5).map { GameTitle("REVIEW-$it",if(it==0) longName else "Jogo $it") }
                    val home=GameDashboardView(a,library,{},{},{},{})
                    try {
                        home.setGames(data);home.selectTab(LibraryTab.ALL);home.setProfile(XboxProfile("Jogador","Perfil de demonstração",subscription="ULTIMATE"));home.setCloudPlaytime(CloudPlaytime(CloudPlaytimeStatus.LIMITED,42*60))
                        mount(home);home.restoreFocus();home.awaitCatalog();measure(home,p.cw,p.ch);capture(home,p,"${theme.name}-home");assertHomeGeometry(home)
                        home.handleKey(KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_BUTTON_Y));home.selectTab(LibraryTab.FAVORITES);measure(home,p.cw,p.ch);capture(home,p,"${theme.name}-favorites")
                        val browse=all(home).filterIsInstance<TextView>().single { it.isShown && it.isClickable && it.text.toString()==UiText.text("Biblioteca") };browse.performClick()
                        val chooser=org.robolectric.shadows.ShadowDialog.getLatestDialog() as ConsoleDialog;dialog(chooser,p,"${theme.name}-library");chooser.dismiss()
                        home.setSearch("Call of Duty");measure(home,p.cw,p.ch);capture(home,p,"${theme.name}-search-results")
                        home.setSearch("Missing game");measure(home,p.cw,p.ch);capture(home,p,"${theme.name}-empty-catalog")
                        home.setSearch("");measure(home,p.cw,p.ch);home.selectTab(LibraryTab.FAVORITES);measure(home,p.cw,p.ch)
                        val search=ConsoleDialogs.search(a,"") {};dialog(search,p,"${theme.name}-search");search.dismiss()
                        val profile=all(home).single { it.isClickable && it.contentDescription?.toString()?.startsWith(home.profileSnapshot().displayName)==true };profile.performClick()
                        val account=org.robolectric.shadows.ShadowDialog.getLatestDialog() as ConsoleDialog;dialog(account,p,"${theme.name}-profile");account.dismiss()
                        val panel=PlaybackSettingsPanel(a,PlaybackSettingsStore(a),{},{},{},{})
                        val settings=panel.show();dialog(settings,p,"${theme.name}-settings");settings.dismiss()
                        val panels=listOf("video" to { panel.showVideo() },"advanced" to { panel.showAdvancedImage() },"controls" to { panel.showControls() },"physical" to { panel.showPhysical() },"audio" to { panel.showAudio() },"appearance" to { panel.showAppearance() },"account" to { panel.showAccount() },"diagnostics" to { panel.showDiagnostics() })
                        for((label,open) in panels) { val d=open();dialog(d,p,"${theme.name}-$label");d.dismiss() }
                        val menu=GameMenuPanel.show(a,{},{},{},{},hudToggle={},audio={},friends={},settings={});dialog(menu,p,"${theme.name}-game-menu");menu.dismiss()
                        home.pauseArtwork()
                        val queue=GameLoadingView(a) {}
                        try { queue.begin(data[0],0);queue.updateQueue(QueueStatus(0,7000,QueueEstimate(787),0),8000);mount(queue);capture(queue,p,"${theme.name}-queue");assertPrimaryTitle(queue)
                            all(queue).filterIsInstance<TextView>().filter { it.isShown && it.text.isNotEmpty() && it.text.toString()!=longName }.forEach(::fits) } finally { queue.close() }
                        val transition=SessionTransitionView(a)
                        try { transition.showStartup(data[0],"Preparando a sessão…");mount(transition);capture(transition,p,"${theme.name}-startup");assertPrimaryTitle(transition);transition.showExit(data[0]);measure(transition,p.cw,p.ch);capture(transition,p,"${theme.name}-exit") } finally { transition.close() }
                        mount(home);home.resumeArtwork();home.restoreFocus();measure(home,p.cw,p.ch);capture(home,p,"${theme.name}-return")
                    } finally { home.close() }
                }
            } finally { controller.pause().stop().destroy() }
        }
    }
    private fun bounds(root: ViewGroup,v: View): Rect {
        val origin=IntArray(2);val child=IntArray(2);root.getLocationOnScreen(origin);v.getLocationOnScreen(child)
        return Rect(child[0]-origin[0],child[1]-origin[1],child[0]-origin[0]+v.width,child[1]-origin[1]+v.height)
    }
    private fun fits(t: TextView) {
        val l=t.layout ?: error("Missing native text layout: ${t.text}")
        assertTrue("Clipped height: ${t.text}; ${l.height} > ${t.height-t.compoundPaddingTop-t.compoundPaddingBottom}",l.height<=t.height-t.compoundPaddingTop-t.compoundPaddingBottom)
        assertTrue("Clipped/ellipsized text: ${t.text}",(0 until l.lineCount).all { l.getEllipsisCount(it)==0 } && l.getLineEnd(l.lineCount-1)>=t.text.length)
    }
    private fun assertPrimaryTitle(root: ViewGroup): TextView {
        val t=all(root).filterIsInstance<TextView>().filter { it.isShown && it.text.toString().equals(longName,true) }.maxBy { it.textSize }
        fits(t);assertTrue("Primary title outside usable area",Rect(0,0,root.width,root.height).contains(bounds(root,t)))
        return t
    }
    private fun assertHomeGeometry(home: GameDashboardView) {
        val title=assertPrimaryTitle(home)
        val platform=all(home).filterIsInstance<TextView>().single { it.isShown && it.text.toString()=="XBOX CLOUD GAMING" }
        assertTrue("Game title overlaps platform",bounds(home,platform).bottom<=bounds(home,title).top)
        all(home).filterIsInstance<TextView>().filter { it.isShown && it.text.toString().equals(longName,true) && it!==title && it.parent !is RecyclerView }.forEach { other ->
            assertFalse("Cover caption overlaps platform",Rect.intersects(bounds(home,other),bounds(home,platform)))
        }
        val full=all(home).filterIsInstance<TextView>().single { it.isShown && it.isClickable && it.contentDescription?.startsWith(LibraryTab.ALL.label)==true }
        fits(full);assertTrue(full.height>=48*home.resources.displayMetrics.density)
        if(DashboardTheme.palette.theme.scene==ConsoleScene.PS5) {
            val grid=all(home).filterIsInstance<RecyclerView>().single()
            val cover=all(grid).filterIsInstance<FixedArtworkView>().firstOrNull()?.parent as? View
            val caption=all(home).filterIsInstance<TextView>().firstOrNull { it.parent===home && it!==title && it.text.toString()==longName }
            if(cover!=null && caption!=null) assertTrue("Caption overlaps cover",bounds(home,caption).top>=bounds(home,cover).bottom)
        }
    }
    private fun tap(root: ViewGroup,v: View) {
        val r=bounds(root,v);val now=android.os.SystemClock.uptimeMillis()
        for(action in listOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP)) {
            val e=MotionEvent.obtain(now,now+16,action,r.exactCenterX(),r.exactCenterY(),0)
            try { assertTrue("Native touch was not handled",root.dispatchTouchEvent(e)) } finally { e.recycle() }
        }
        shadowOf(Looper.getMainLooper()).idleFor(32,TimeUnit.MILLISECONDS)
    }
    @Test fun allGamesIsDirectAndTouchDpadEnterBackAndXPreserveCatalogSelection() {
        for(p in phones.take(2)) {
            RuntimeEnvironment.setQualifiers(p.qualifiers)
            val c=Robolectric.buildActivity(Activity::class.java).setup();val a=c.get();UiText.apply(a,AppLanguage.PORTUGUESE)
            try { for(theme in listOf(UiTheme.PS4,UiTheme.PS5)) {
                DashboardTheme.apply(a,theme);val played=mutableListOf<String>()
                val library=GameLibrary(a,"all-games-${p.name}-${theme.name}")
                library.saveCloudRecent(listOf("INPUT-2","INPUT-3"));library.toggleFavorite("INPUT-4")
                val home=GameDashboardView(a,library,{ played+=it.titleId },{},{},{})
                try {
                    attach(a,home,p);home.setGames((0..20).map { GameTitle("INPUT-$it","Jogo %02d".format(it)) });home.selectTab(LibraryTab.RECENT)
                    measure(a.window.decorView,p.w,p.h);measure(home,p.cw,p.ch)
                    val grid=all(home).filterIsInstance<RecyclerView>().single();assertEquals(2,grid.adapter!!.itemCount)
                    val tab=all(home).filterIsInstance<TextView>().single { it.isShown && it.isClickable && it.contentDescription?.startsWith(LibraryTab.ALL.label)==true }
                    tap(home,tab);measure(home,p.cw,p.ch);assertEquals(21,grid.adapter!!.itemCount)
                    home.restoreFocus();measure(home,p.cw,p.ch)
                    repeat(20) { home.handleKey(KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_RIGHT));measure(home,p.cw,p.ch) }
                    assertEquals("INPUT-20",home.selectedGame!!.titleId)
                    assertTrue(Rect(0,0,home.width,home.height).contains(bounds(home,home.findFocus()!!)))
                    // PS5 leaves the carousel at its final right-side library shortcut.
                    home.restoreFocus();measure(home,p.cw,p.ch)
                    home.handleKey(KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_BUTTON_A));assertEquals(listOf("INPUT-20"),played)
                    home.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_ENTER));home.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_ENTER))
                    assertEquals(listOf("INPUT-20","INPUT-20"),played)
                    val panel=PlaybackSettingsPanel(a,PlaybackSettingsStore(a),{},{},{},{})
                    val root=panel.show();dialog(root,p,"${theme.name}-input-root")
                    val controls=all(root.body).filterIsInstance<TextView>().single { it.isClickable && it.text.toString()=="Controles e toque" }
                    controls.requestFocus();root.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_BUTTON_A))
                    val child=org.robolectric.shadows.ShadowDialog.getLatestDialog() as ConsoleDialog;assertFalse(root.isShowing)
                    child.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_BUTTON_B));shadowOf(Looper.getMainLooper()).idle()
                    val returned=org.robolectric.shadows.ShadowDialog.getLatestDialog() as ConsoleDialog
                    assertEquals("Controles e toque",(returned.window!!.currentFocus as TextView).text.toString())
                    returned.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_BUTTON_X));assertFalse(returned.isShowing)
                    home.pauseArtwork();assertFalse(all(home).filterIsInstance<ConsoleThemeBackdrop>().single().framePending)
                    home.resumeArtwork();home.restoreFocus();measure(home,p.cw,p.ch)
                    assertEquals("INPUT-20",home.selectedGame!!.titleId);assertTrue(grid.hasFocus())
                    home.handleKey(KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_BUTTON_B));assertFalse(grid.hasFocus())
                } finally { home.close() }
            } } finally { c.pause().stop().destroy() }
        }
    }
    @Test fun translatedLandscapePanelsWrapInsteadOfClippingAndScrollToTheLastSettings() {
        val p=phones.first();RuntimeEnvironment.setQualifiers(p.qualifiers)
        val c=Robolectric.buildActivity(Activity::class.java).setup();val a=c.get();a.setContentView(FrameLayout(a));measure(a.window.decorView,p.w,p.h)
        try { for(theme in listOf(UiTheme.PS4,UiTheme.PS5)) for(language in AppLanguage.entries) {
            DashboardTheme.apply(a,theme);UiText.apply(a,language)
            val panel=PlaybackSettingsPanel(a,PlaybackSettingsStore(a),{},{},{},{})
            for((name,open) in listOf("root" to { panel.show() },"advanced" to { panel.showAdvancedImage() },"video" to { panel.showVideo() })) {
                val d=open();dialog(d,p,"${theme.name}-${language.name}-$name")
                try {
                    all(d.body).filterIsInstance<TextView>().filter { it.isClickable }.forEach(::fits)
                    val last=all(d.body).last { it.isFocusable && it.isClickable };last.requestFocus()
                    last.requestRectangleOnScreen(Rect(0,0,last.width,last.height),true);measure(d.panel,d.panel.width,p.ch,false)
                    val scroll=d.body.parent as ScrollView;val viewport=bounds(d.panel,scroll);val target=bounds(d.panel,last)
                    assertTrue("Last setting inaccessible: $theme $language $target $viewport",viewport.contains(target))
                } finally { d.dismiss() }
            }
        } } finally { c.pause().stop().destroy() }
    }
    @Test fun longTitleFitsLandscapeWithLargeSystemFontAndVisibleSystemBars() {
        for(p in phones) {
            RuntimeEnvironment.setQualifiers(p.qualifiers)
            val a=RuntimeEnvironment.getApplication();val configuration=android.content.res.Configuration(a.resources.configuration).apply { fontScale=1.3f }
            @Suppress("DEPRECATION") a.resources.updateConfiguration(configuration,a.resources.displayMetrics)
            val c=Robolectric.buildActivity(Activity::class.java).setup();val activity=c.get();assertEquals(1.3f,activity.resources.configuration.fontScale,.01f)
            try { for(theme in listOf(UiTheme.PS4,UiTheme.PS5)) {
                DashboardTheme.apply(activity,theme)
                val home=GameDashboardView(activity,GameLibrary(activity,"large-${theme.name}-${p.name}"),{},{},{},{})
                try {
                    attach(activity,home,p);home.setGames(listOf(GameTitle("LARGE",longName)));home.selectTab(LibraryTab.ALL);measure(home,p.cw,p.ch)
                    capture(home,p,"${theme.name}-large-font");assertHomeGeometry(home)
                } finally { home.close() }
            } } finally { c.pause().stop().destroy();configuration.fontScale=1f;@Suppress("DEPRECATION") a.resources.updateConfiguration(configuration,a.resources.displayMetrics) }
        }
    }
    @Test fun artworkUsesBoundedNativeLoadingAndTextRemainsReadableOverABrightHero() {
        val p=phones[1];RuntimeEnvironment.setQualifiers(p.qualifiers)
        val c=Robolectric.buildActivity(Activity::class.java).setup();val a=c.get()
        val server=okhttp3.mockwebserver.MockWebServer().apply { start() }
        val source=Bitmap.createBitmap(1280,720,Bitmap.Config.RGB_565).apply { eraseColor(Color.WHITE) }
        val bytes=java.io.ByteArrayOutputStream().use { source.compress(Bitmap.CompressFormat.PNG,100,it);source.recycle();it.toByteArray() }
        server.dispatcher=object: okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest)=okhttp3.mockwebserver.MockResponse().setBody(okio.Buffer().write(bytes))
        }
        try { for(theme in listOf(UiTheme.PS5,UiTheme.PS5_NIGHT)) {
            AppearanceSettingsStore(a).save(theme);DashboardTheme.apply(a,theme);AppearanceSettingsStore(a).saveMotion(BackgroundMotion.OFF)
            val home=GameDashboardView(a,GameLibrary(a,"bright-${theme.name}"),{},{},{},{})
            try {
                attach(a,home,p)
                home.setGames((0..59).map { GameTitle("BRIGHT-$it",if(it==0) longName else "Jogo %02d".format(it),tileUrl=server.url("/${theme.name}-$it.png").toString(),heroUrl=server.url("/${theme.name}-hero.png").toString()) })
                home.selectTab(LibraryTab.ALL);measure(home,p.cw,p.ch);home.restoreFocus();measure(home,p.cw,p.ch)
                val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(8)
                fun heroReady()=all(home).filterIsInstance<FixedArtworkView>().any { it.width==home.width && it.drawable is android.graphics.drawable.BitmapDrawable }
                while(!heroReady() && System.nanoTime()<end) { shadowOf(Looper.getMainLooper()).idleFor(32,TimeUnit.MILLISECONDS);Thread.sleep(10) }
                assertTrue("Native hero did not load",heroReady());measure(home,p.cw,p.ch)
                assertTrue("Loading should fetch visible thumbnails, not the whole catalog",server.requestCount<30)
                all(home).filterIsInstance<FixedArtworkView>().mapNotNull { (it.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap }.forEach { assertTrue(it.allocationByteCount<=1024*576*4) }
                capture(home,p,"${theme.name}-bright-artwork");assertHomeGeometry(home)
                val backdrop=all(home).filterIsInstance<ConsoleThemeBackdrop>().single()
                val bitmap=Bitmap.createBitmap(home.width,home.height,Bitmap.Config.ARGB_8888);backdrop.draw(Canvas(bitmap))
                try {
                    val title=bounds(home,assertPrimaryTitle(home))
                    for(x in listOf(title.left+4,title.centerX(),title.right-4)) {
                        val contrast=androidx.core.graphics.ColorUtils.calculateContrast(Color.WHITE,bitmap.getPixel(x,title.top+4))
                        assertTrue("Insufficient title contrast: $theme $contrast",contrast>=4.5)
                    }
                } finally { bitmap.recycle() }
                home.pauseArtwork();assertFalse(backdrop.framePending)
                home.visibility=View.GONE;shadowOf(Looper.getMainLooper()).idleFor(500,TimeUnit.MILLISECONDS);assertFalse(backdrop.framePending)
            } finally { home.close() }
        } } finally { server.shutdown();c.pause().stop().destroy() }
    }
}
