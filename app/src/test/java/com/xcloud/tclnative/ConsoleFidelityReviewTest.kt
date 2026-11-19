package com.xcloud.tclnative

import android.app.Activity
import android.graphics.*
import android.os.Looper
import android.view.*
import android.widget.*
import com.xcloud.tclnative.catalog.*
import com.xcloud.tclnative.renderer.AiPqSurfaceRenderer
import com.xcloud.tclnative.settings.*
import com.xcloud.tclnative.signaling.*
import com.xcloud.tclnative.ui.*
import com.xcloud.tclnative.ui.dashboard.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.*
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],qualifiers="w640dp-h300dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class ConsoleFidelityReviewTest {
    private fun all(v: View): List<View> = listOf(v)+if(v is ViewGroup) (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else emptyList()
    private fun layout(v: View,w: Int,h: Int,exact: Boolean=true) {
        (v as? GameDashboardView)?.awaitCatalog()
        repeat(3) {
            v.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,if(exact) View.MeasureSpec.EXACTLY else View.MeasureSpec.AT_MOST))
            v.layout(0,0,v.measuredWidth,v.measuredHeight);shadowOf(Looper.getMainLooper()).idleFor(160,TimeUnit.MILLISECONDS)
        }
    }
    private fun capture(v: View,name: String) {
        val b=Bitmap.createBitmap(v.width,v.height,Bitmap.Config.ARGB_8888);val canvas=Canvas(b)
        try { v.draw(canvas);File("build/reports/themes/fidelity/$name.png").apply { parentFile.mkdirs() }.outputStream().use { b.compress(Bitmap.CompressFormat.PNG,100,it) } }
        finally { canvas.setBitmap(null);b.recycle() }
    }
    private fun image(name: String)=javaClass.getResourceAsStream("/console-demo/$name.jpg")!!.use { BitmapFactory.decodeStream(it)!! }
    private fun seed(a: Activity,games: List<GameTitle>,scale: Float) {
        val backend=ArtworkBackend.get(a);backend.cache.evictAll()
        fun cache(url: String,source: Bitmap,size: ArtworkSize) {
            val requested=backend.policy.sizeFor(size.width,size.height)
            val fitted=requested.fit(source.width,source.height)
            val bitmap=Bitmap.createScaledBitmap(source,fitted.width,fitted.height,true)
            backend.cache.put(ArtworkRequest.create(url,requested.width,requested.height),bitmap)
        }
        games.forEach { game ->
            val art=image(game.titleId)
            cache(game.tileUrl!!,art,ArtworkSize.forDisplay((220*scale).toInt(),(220*scale).toInt()))
            cache(game.tileUrl!!,art,ArtworkSize(192,320))
        }
        val selected=games.last()
        cache(selected.tileUrl!!,image(selected.titleId),ArtworkSize(720,1080))
        cache("https://demo.invalid/forza-hero",image("forza-hero"),ArtworkSize(1024,576))
    }
    private fun games()=listOf("cold-war" to "Call of Duty: Black Ops Cold War","fortnite" to "Fortnite","sea-of-thieves" to "Sea of Thieves","halo" to "Halo 5: Guardians","battlefield" to "Battlefield 6").map { (id,name) ->
        GameTitle(id,name,tileUrl="https://demo.invalid/$id",posterUrl="https://demo.invalid/$id")
    }
    @After fun reset() { val a=RuntimeEnvironment.getApplication();DashboardTheme.apply(a,UiTheme.GREEN);AppearanceSettingsStore(a).save(UiTheme.GREEN) }
    @Test fun compareEveryExistingThemeAndBothReferenceCompositionsInLandscape() {
        val c=Robolectric.buildActivity(Activity::class.java).setup();val a=c.get()
        try {
            for((w,h) in listOf(1280 to 720,1280 to 591)) {
                a.setContentView(FrameLayout(a));layout(a.window.decorView,w,h)
                for(theme in UiTheme.entries) {
                    DashboardTheme.apply(a,theme);AppearanceSettingsStore(a).saveMotion(BackgroundMotion.OFF)
                    val data=if(theme.scene==ConsoleScene.PS5) listOf(GameTitle("forza","Forza Horizon 5 Standard Edition",tileUrl="https://demo.invalid/forza",posterUrl="https://demo.invalid/forza",heroUrl="https://demo.invalid/forza-hero")) else games()
                    seed(a,data,minOf(w/1280f,h/720f))
                    val home=GameDashboardView(a,GameLibrary(a,"fidelity-${theme.name}-$h"),{},{},{},{},onFriends={})
                    try {
                        val holder=FrameLayout(a);holder.addView(home,FrameLayout.LayoutParams(w,h));a.setContentView(holder);layout(a.window.decorView,w,h)
                        home.setGames(data);home.selectTab(LibraryTab.ALL)
                        home.setProfile(XboxProfile("Jogador real","Demo de teste",subscription="ULTIMATE"));home.setCloudPlaytime(CloudPlaytime(CloudPlaytimeStatus.LIMITED,42*60))
                        layout(home,w,h);home.restoreFocus();layout(home,w,h)
                        if(theme.scene==ConsoleScene.PS4) repeat(4) { home.handleKey(KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_RIGHT));layout(home,w,h) }
                        capture(home,"${theme.name}-$h-home")
                        if(theme.scene.playStation) {
                            val title=all(home).filterIsInstance<TextView>().filter { it.isShown && it.parent===home && it.text.toString().equals(home.selectedGame?.name,true) }.maxBy { it.textSize }
                            assertTrue("Title is too high for reference composition",title.top>h*.36f)
                            if(theme.scene==ConsoleScene.PS5) {
                                val start=all(home).filterIsInstance<TextView>().single { it.isClickable && it.text=="Jogar na nuvem" }
                                assertTrue(start.top>title.bottom);assertTrue(start.left<w*.15f)
                            }
                        }
                        home.pauseArtwork()
                    } finally { home.close() }
                    val settings=PlaybackSettingsPanel(a,PlaybackSettingsStore(a),{},{},{},{})
                    for((name,open) in listOf("settings" to { settings.show() },"video" to { settings.showVideo() },"audio" to { settings.showAudio() })) {
                        val d=open();try {
                            layout(d.panel,if(theme.scene.playStation) w else (w*.9f).toInt(),h,false);capture(d.panel,"${theme.name}-$h-$name");assertImmersive(d.window!!)
                            if(theme.scene.playStation && name=="settings") {
                                val categories=all(d.body).filterIsInstance<TextView>().filter { it.isClickable }
                                assertEquals(6,categories.size)
                                val viewport=d.body.parent as ScrollView
                                for(item in categories) {
                                    val bounds=Rect(0,0,item.width,item.height);viewport.offsetDescendantRectToMyCoords(item,bounds)
                                    assertTrue("Setting category clipped: ${item.text}",Rect(0,0,viewport.width,viewport.height).contains(bounds))
                                }
                            }
                        } finally { d.dismiss() }
                    }
                    if(theme.scene.playStation) {
                        seed(a,data,minOf(w/1280f,h/720f))
                        val q = GameLoadingView(a) {}
                        try {
                            q.begin(data.last(), 0)
                            q.updateQueue(QueueStatus(0, 1000, QueueEstimate(73), 1000), 1000)
                            layout(q, w, h)
                            capture(q, "${theme.name}-$h-queue")
                            q.updateProgress("ICE:Connecting", nowMs = 5000)
                            layout(q, w, h)
                            assertTrue(all(q).filterIsInstance<TextView>().any { it.visibility == View.VISIBLE && it.text.toString() == QueueStatus.duration(5) })
                            capture(q, "${theme.name}-$h-connecting")
                        } finally { q.close() }
                        val d=GameMenuPanel.show(a,{},{},{},{},hudToggle={},audio={});try { layout(d.panel,d.window!!.attributes.width,h,false);capture(d.panel,"${theme.name}-$h-game-menu");assertImmersive(d.window!!) } finally { d.dismiss() }
                    }
                }
            }
        } finally { c.pause().stop().destroy() }
    }
    @Suppress("DEPRECATION") private fun assertImmersive(w: Window,edgeToEdge: Boolean=false) {
        val sdk=android.os.Build.VERSION.SDK_INT
        val flags=w.decorView.systemUiVisibility
        assertTrue(flags and View.SYSTEM_UI_FLAG_FULLSCREEN!=0)
        assertTrue(flags and View.SYSTEM_UI_FLAG_HIDE_NAVIGATION!=0)
        assertTrue(flags and View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY!=0)
        assertTrue(w.attributes.flags and WindowManager.LayoutParams.FLAG_FULLSCREEN!=0)
        if(sdk>=28) assertEquals(
            if(edgeToEdge && sdk>=30) WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            else WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES,w.attributes.layoutInDisplayCutoutMode)
        if(edgeToEdge) {
            assertEquals(Color.TRANSPARENT,w.navigationBarColor)
            if(sdk>=30) {
                assertEquals(0,w.attributes.fitInsetsTypes)
                assertFalse(org.robolectric.util.ReflectionHelpers.getField<Boolean>(w,"mDecorFitsSystemWindows"))
            } else assertTrue(flags and View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION!=0)
        }
    }
    @Test @Config(sdk=[28,30,34]) fun panelsKeepSystemBarsHiddenOnShowFocusReturnAndNestedNavigation() {
        val c=Robolectric.buildActivity(Activity::class.java).setup();val a=c.get();a.setContentView(FrameLayout(a));layout(a.window.decorView,1280,591)
        try { for(theme in UiTheme.entries) {
            DashboardTheme.apply(a,theme);ImmersiveWindow.apply(a.window,edgeToEdge=true);assertImmersive(a.window,edgeToEdge=true)
            val first=ConsoleDialogs.menu(a,"Configurações",listOf(ConsoleAction("Vídeo") {}))
            val full=theme.scene.playStation
            try { assertImmersive(first.window!!,full);first.window!!.decorView.systemUiVisibility=0;first.onWindowFocusChanged(true);assertImmersive(first.window!!,full)
                val child=ConsoleDialogs.menu(a,"Vídeo",listOf(ConsoleAction("Resolução") {}));assertImmersive(child.window!!,full);child.cancel();first.onWindowFocusChanged(true);assertImmersive(first.window!!,full)
            } finally { first.dismiss() }
        } } finally { c.pause().stop().destroy() }
    }
    @Test fun compactMenuKeepsOriginalCircleDiameterAndNativeTouchTarget() {
        val c=Robolectric.buildActivity(Activity::class.java).setup();val a=c.get();var opens=0
        val v=GameTouchView(a,{},{},{x,y -> x to y},{opens++});a.setContentView(v);v.configure(TouchMode.NATIVE,GameTitle("demo","Demo"));layout(v,1280,591)
        try {
            val b=all(v).filterIsInstance<TextView>().single { it.contentDescription=="Atalhos da partida" }
            assertTrue(b.right<v.width/2 && b.bottom<v.height/2)
            assertTrue(b.width>=48*a.resources.displayMetrics.density && b.height>=48*a.resources.displayMetrics.density)
            val bitmap=Bitmap.createBitmap(b.width,b.height,Bitmap.Config.ARGB_8888);val canvas=Canvas(bitmap)
            try {
                b.background.setBounds(0,0,b.width,b.height);b.background.draw(canvas)
                val row=(0 until b.width).filter { Color.alpha(bitmap.getPixel(it,b.height/2))>0 }
                assertTrue("Menu artwork grew beyond its original screen-relative diameter",row.last()-row.first()<=591*.068f)
            } finally { canvas.setBitmap(null);bitmap.recycle() }
            val now=android.os.SystemClock.uptimeMillis()
            for(action in listOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP)) { val e=MotionEvent.obtain(now,now,action,b.left+b.width/2f,b.top+b.height/2f,0);try { assertTrue(v.dispatchTouchEvent(e)) } finally { e.recycle() } }
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1,opens);capture(v,"compact-game-menu")
        } finally { c.pause().stop().destroy() }
    }
    @Test @Config(sdk=[30],qualifiers="w780dp-h360dp-land-xxhdpi")
    fun landscapeCutoutKeepsFullArtworkAndSafeInteractiveAreasOnBothSides() {
        val c=Robolectric.buildActivity(Activity::class.java).setup();val a=c.get()
        val w=2340;val h=1080
        val root=FrameLayout(a);a.setContentView(root);ImmersiveWindow.apply(a.window,edgeToEdge=true)
        layout(a.window.decorView,w,h);DashboardTheme.apply(a,UiTheme.PS5)
        assertImmersive(a.window,edgeToEdge=true)
        val home=GameDashboardView(a,GameLibrary(a,"cutout-home"),{},{},{},{})
        val queue=GameLoadingView(a) {}
        val transition=SessionTransitionView(a)
        val video=AiPqSurfaceRenderer(a).apply { aspect=VideoAspect.STRETCH }
        var opened=0
        val touch=GameTouchView(a,{},{},{x,y->x to y},{opened++})
        fun layoutWithCutout(view: View,w: Int,h: Int,insets: WindowInsets) {
            layout(view,w,h)
            // The harness window has no cutout. Inject window-relative insets after it lays out.
            view.dispatchApplyWindowInsets(insets)
            view.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY))
            view.layout(0,0,view.measuredWidth,view.measuredHeight)
        }
        try {
            val demo=GameTitle("forza","Forza Horizon 5",tileUrl="https://demo.invalid/forza",
                posterUrl="https://demo.invalid/forza",heroUrl="https://demo.invalid/forza-hero")
            seed(a,listOf(demo),h/720f)
            home.setGames(listOf(demo));home.selectTab(LibraryTab.ALL)
            queue.begin(demo);transition.showStartup(demo,"Preparando seu jogo…")
            touch.configure(TouchMode.NATIVE,demo);touch.setBackgroundColor(0xff304d48.toInt())
            for(left in listOf(true,false)) {
                val side=if(left) "left" else "right"
                val safe=if(left) Rect(80,0,0,0) else Rect(0,0,80,0)
                val cutout=DisplayCutout(safe,listOf(if(left) Rect(0,500,80,580) else Rect(w-80,500,w,580)))
                val insets=WindowInsets.Builder().setDisplayCutout(cutout).build()
                for((view,name) in listOf(home to "home",queue to "queue",transition to "startup")) {
                    root.removeAllViews();root.addView(view,FrameLayout.LayoutParams(-1,-1))
                    layoutWithCutout(view,w,h,insets)
                    assertEquals(name,safe.left,view.paddingLeft);assertEquals(name,safe.right,view.paddingRight)
                    val background=view.getChildAt(1)
                    assertEquals(Rect(0,0,w,h),Rect(background.left,background.top,background.right,background.bottom))
                    capture(view,"edge-$side-$name")
                }
                root.removeAllViews();root.addView(video,FrameLayout.LayoutParams(-1,-1));root.addView(touch,FrameLayout.LayoutParams(-1,-1))
                layoutWithCutout(root,w,h,insets)
                assertEquals(w,video.width);assertEquals(h,video.height)
                assertTrue(ImmersiveWindow.diagnostics(a.window,root).contains("content=${w}x$h"))
                val arrow=touch.getChildAt(0)
                assertTrue(arrow.left>=safe.left && arrow.right<touch.width/2)
                val now=android.os.SystemClock.uptimeMillis()
                for(action in listOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP)) {
                    val event=MotionEvent.obtain(now,now,action,arrow.left+arrow.width/2f,arrow.top+arrow.height/2f,0)
                    try { assertTrue(touch.dispatchTouchEvent(event)) } finally { event.recycle() }
                }
                capture(touch,"edge-$side-arrow");shadowOf(Looper.getMainLooper()).idle()
                val menu=GameMenuPanel.show(a,{},{},{},{},audio={},hudToggle={})
                try {
                    assertImmersive(menu.window!!)
                    val params=menu.window!!.attributes
                    assertEquals(Gravity.TOP or Gravity.LEFT,params.gravity)
                    val base=menu.panel.paddingLeft
                    layoutWithCutout(menu.panel,params.width,params.height,insets)
                    assertTrue(menu.panel.paddingLeft>=base+safe.left)
                    capture(menu.panel,"edge-$side-live-menu")
                } finally { menu.dismiss() }
            }
            assertEquals(2,opened)
        } finally { home.close();queue.close();transition.close();video.release();c.pause().stop().destroy() }
    }
    @Test @Config(sdk=[30]) fun fullscreenSearchAppliesKeyboardInsetsWithoutAddingThemToTheCutout() {
        val c=Robolectric.buildActivity(Activity::class.java).setup();val a=c.get()
        a.setContentView(FrameLayout(a));layout(a.window.decorView,1280,600);DashboardTheme.apply(a,UiTheme.PS5)
        val dialog=ConsoleDialogs.search(a,"") {}
        try {
            assertImmersive(dialog.window!!,edgeToEdge=true)
            val panel=dialog.panel
            val base=Rect(panel.paddingLeft,panel.paddingTop,panel.paddingRight,panel.paddingBottom)
            val type=WindowInsets.Type.ime()
            val cutout=DisplayCutout(Rect(50,0,0,0),listOf(Rect(0,260,50,340)))
            val hidden=WindowInsets.Builder().setDisplayCutout(cutout).build()
            val shown=WindowInsets.Builder(hidden).setInsets(type,Insets.of(0,0,0,220)).setVisible(type,true).build()
            panel.dispatchApplyWindowInsets(shown)
            assertEquals(base.left+50,panel.paddingLeft)
            assertEquals(base.bottom+220,panel.paddingBottom)
            panel.dispatchApplyWindowInsets(hidden)
            assertEquals(base.left+50,panel.paddingLeft)
            assertEquals(base.bottom,panel.paddingBottom)
        } finally { dialog.dismiss();c.pause().stop().destroy() }
    }
    @Test fun compactCloseAcceptsNativeTapOutsideItsVisibleArtwork() {
        val c=Robolectric.buildActivity(Activity::class.java).setup();val a=c.get()
        a.setContentView(FrameLayout(a));layout(a.window.decorView,1280,591);DashboardTheme.apply(a,UiTheme.PS5)
        val dialog=PlaybackSettingsPanel(a,PlaybackSettingsStore(a),{},{},{},{}).show()
        try {
            layout(dialog.panel,1280,591,false)
            val close=all(dialog.panel).single { it.tag=="close-settings" }
            assertTrue(close.height<48*a.resources.displayMetrics.density)
            val bounds=Rect(0,0,close.width,close.height);dialog.panel.offsetDescendantRectToMyCoords(close,bounds)
            val now=android.os.SystemClock.uptimeMillis()
            val point=Point(bounds.centerX(),bounds.bottom+8)
            val under=all(dialog.panel).filter { view ->
                val rect=Rect(0,0,view.width,view.height)
                if(view!==dialog.panel) dialog.panel.offsetDescendantRectToMyCoords(view,rect)
                view.isClickable && rect.contains(point.x,point.y)
            }.map { "${it.javaClass.simpleName}:${(it as? TextView)?.text}" }
            var pressedDown=false
            for(action in listOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP)) {
                val event=MotionEvent.obtain(now,now,action,point.x.toFloat(),point.y.toFloat(),0)
                try { assertTrue(dialog.panel.dispatchTouchEvent(event)) } finally { event.recycle() }
                if(action==MotionEvent.ACTION_DOWN) pressedDown=close.isPressed
            }
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse("The transparent touch area around X must close the panel: close=$bounds point=$point pressed=$pressedDown under=$under",dialog.isShowing)
        } finally { dialog.dismiss();c.pause().stop().destroy() }
    }
}
