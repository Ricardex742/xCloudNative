package com.xcloud.tclnative

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.RecyclerView
import com.xcloud.tclnative.catalog.GameLibrary
import com.xcloud.tclnative.catalog.LibraryTab
import com.xcloud.tclnative.catalog.XboxProfile
import com.xcloud.tclnative.catalog.CatalogCard
import com.xcloud.tclnative.catalog.StoreGameDetails
import com.xcloud.tclnative.catalog.XboxAchievement
import com.xcloud.tclnative.catalog.XboxGameProgress
import com.xcloud.tclnative.settings.*
import com.xcloud.tclnative.signaling.GameTitle
import com.xcloud.tclnative.ui.dashboard.*
import okhttp3.mockwebserver.*
import okio.Buffer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowDialog
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],qualifiers="w1280dp-h720dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class AppearanceUiTest {
    @Before fun defaults() {
        val app=RuntimeEnvironment.getApplication()
        app.getSharedPreferences("app_appearance",0).edit().clear().commit()
        DashboardTheme.apply(app,UiTheme.GREEN)
    }
    @After fun reset() {
        (ShadowDialog.getLatestDialog() as? ConsoleDialog)?.dismiss()
        defaults()
    }
    private fun all(view: View): List<View> = listOf(view)+if(view is ViewGroup)
        (0 until view.childCount).flatMap { all(view.getChildAt(it)) } else emptyList()
    private fun choose(dialog: ConsoleDialog,text: String) {
        all(dialog.body).filterIsInstance<TextView>().single { it.isClickable && it.text.contains(text) }.performClick()
    }
    private fun layout(view: View) {
        (view as? GameDashboardView)?.awaitCatalog()
        repeat(2) {
            view.measure(View.MeasureSpec.makeMeasureSpec(1280,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(720,View.MeasureSpec.EXACTLY))
            view.layout(0,0,1280,720);shadowOf(Looper.getMainLooper()).idle()
        }
    }
    private fun capture(view: View,name: String): IntArray {
        val bitmap=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        File("build/reports/themes/$name.png").apply { parentFile.mkdirs() }.outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG,100,it)
        }
        val pixels=IntArray(view.width*view.height)
        bitmap.getPixels(pixels,0,view.width,0,0,view.width,view.height);bitmap.recycle()
        return pixels
    }
    private fun capture(dialog: ConsoleDialog,name: String) {
        dialog.panel.measure(View.MeasureSpec.makeMeasureSpec(1100,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(720,View.MeasureSpec.AT_MOST))
        dialog.panel.layout(0,0,dialog.panel.measuredWidth,dialog.panel.measuredHeight)
        capture(dialog.panel,name)
    }
    private fun await(condition: () -> Boolean) {
        val deadline=System.currentTimeMillis()+10000
        while(!condition() && System.currentTimeMillis()<deadline) {
            Thread.sleep(15);shadowOf(Looper.getMainLooper()).idle()
        }
        assertTrue("Artwork did not reach the native view",condition())
    }

    @Test fun originalGreenRemainsTheDefaultAndEachChoicePersistsWithoutChangingStreamSettings() {
        val app=RuntimeEnvironment.getApplication();val store=AppearanceSettingsStore(app)
        val playback=PlaybackSettingsStore(app);val before=playback.read()
        assertEquals(UiTheme.GREEN,store.read())
        assertEquals(6, UiTheme.XBOX_SERIES.colors.size)
        assertEquals(listOf(UiTheme.XBOX_SERIES), UiTheme.layouts.filter { it.scene == ConsoleScene.XBOX_SERIES })
        for(theme in UiTheme.entries) {
            store.save(theme);assertEquals(theme,AppearanceSettingsStore(app).read())
            assertEquals(before,playback.read())
        }
        app.getSharedPreferences("app_appearance",0).edit().putString("theme","unknown-theme").commit()
        assertEquals(UiTheme.GREEN,AppearanceSettingsStore(app).read())
        for(role in UiColor.entries) assertEquals(role.original,UiPalette(UiTheme.GREEN)[role])
        store.save(UiTheme.SERIES_PURPLE)
        store.selectLayout(UiTheme.PS5)
        assertEquals(UiTheme.SERIES_PURPLE, store.selectLayout(UiTheme.XBOX_SERIES))
    }

    @Test fun everyPaletteKeepsTextAndFocusReadableOnItsPanels() {
        val detailsAccents = mutableSetOf<Int>()
        for(theme in UiTheme.entries) {
            val palette=UiPalette(theme)
            for(background in listOf(UiColor.SURFACE,UiColor.PANEL_TOP,UiColor.PANEL_BOTTOM)) {
                assertTrue("${theme.label} focus/text contrast",ColorUtils.calculateContrast(palette[UiColor.ACCENT],palette[background])>=4.5)
                assertTrue("${theme.label} secondary text contrast",ColorUtils.calculateContrast(palette[UiColor.MUTED],palette[background])>=4.5)
            }
            val details = GameDetailsStyle(palette)
            assertTrue("${theme.label} details action contrast",
                ColorUtils.calculateContrast(android.graphics.Color.WHITE, details.action(primary = true, focused = true)) >= 4.5)
            if (theme.scene == ConsoleScene.XBOX_SERIES) detailsAccents.add(details.accent)
        }
        assertEquals(UiTheme.XBOX_SERIES.colors.size, detailsAccents.size)
    }

    @Test fun gameDetailsUsesActiveThemeForPanelsActionsAndProgress() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val scope = CoroutineScope(Dispatchers.Main.immediate + Job())
        val backend = ArtworkBackend.get(activity)
        fun art(file: String, w: Int, h: Int): String {
            val url = "https://example.test/details/$file"
            val size = backend.policy.sizeFor(w, h)
            val source = javaClass.getResourceAsStream("/console-demo/$file")!!.use { android.graphics.BitmapFactory.decodeStream(it) }
            val fit = size.fit(source.width, source.height)
            val bitmap = Bitmap.createScaledBitmap(source, fit.width, fit.height, true)
            if (bitmap !== source) source.recycle()
            backend.cache.put(ArtworkRequest.create(url, size.width, size.height), bitmap)
            return url
        }
        val cover = art("forza.jpg", 384, 576)
        val hero = art("forza-hero.jpg", 256, 160)
        val previews = listOf(art("forza-hero.jpg", 640, 360))
        val game = GameTitle("forza-demo", "Forza Horizon 5", productId = "DEMOFORZATIL",
            posterUrl = cover, heroUrl = hero)
        val hashes = mutableSetOf<Int>()
        try {
            layout(activity.window.decorView)
            for (theme in listOf(UiTheme.SERIES_BLUE, UiTheme.PS4, UiTheme.PS5_NIGHT, UiTheme.XBOX_360)) {
                DashboardTheme.apply(activity, theme)
                val card = GameDetailsPanel(activity, CatalogCard(game), scope,
                    loadDetails = { StoreGameDetails("Xbox Game Studios", "Explore o México em corridas.", "L", previews, 2021) },
                    onPlay = {}, onShortcut = {}, onSettings = {}, onStore = {},
                    loadProgress = { XboxGameProgress(10, 500, 1, 50,
                        listOf(XboxAchievement("Bem-vindo ao Horizon", 10, "Chegue ao festival.", true, 100))) }
                ).dialog
                try {
                    card.show()
                    val root = card.window!!.decorView
                    layout(root)
                    await { card.detailsBackdrop?.drawable != null }
                    layout(root)
                    val progress = all(card.body).filterIsInstance<android.widget.ProgressBar>().single()
                    assertEquals(theme.accent, progress.progressTintList?.defaultColor)
                    assertEquals(theme.accent, card.gameDetailsStyle.accent)
                    hashes += capture(root, "game-details-${theme.name.lowercase()}").contentHashCode()
                } finally { card.dismiss() }
            }
            assertEquals(4, hashes.size)
        } finally { scope.cancel(); controller.pause().stop().destroy() }
    }

    @Test fun selectingAndCancellingThemeMenusUpdatesAppearanceOnlyAndReturnsToItsCategory() {
        val controller=Robolectric.buildActivity(Activity::class.java).setup();val a=controller.get()
        val media=PlaybackSettingsStore(a);val before=media.read();var changed=0
        val pages=mutableListOf<SettingsPage>()
        val panel=PlaybackSettingsPanel(a,media,{changed++},{},{},{},onPageEntered={pages.add(it)})
        try {
            val root=panel.show();capture(root,"settings");choose(root,"Aparência e temas")
            choose(ShadowDialog.getLatestDialog() as ConsoleDialog,"Tema do aplicativo")
            val options=ShadowDialog.getLatestDialog() as ConsoleDialog
            capture(options,"theme-picker")
            val entries = all(options.body).filterIsInstance<TextView>().filter { it.isClickable }.map { it.text.toString() }
            assertEquals(9, entries.size)
            assertEquals(1, entries.count { it.contains("PlayStation 4") })
            assertEquals(1, entries.count { it.contains("PlayStation 5") })
            assertFalse(entries.any { it.contains("Noturno") || it.contains("Prata Platina") })
            assertEquals(UiTheme.GREEN,AppearanceSettingsStore(a).read())
            options.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_BUTTON_B))
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(SettingsPage.APPEARANCE,pages.last());assertEquals(UiTheme.GREEN,AppearanceSettingsStore(a).read())
            choose(ShadowDialog.getLatestDialog() as ConsoleDialog,"Cores")
            choose(ShadowDialog.getLatestDialog() as ConsoleDialog,"Prata Platina")
            assertEquals(UiTheme.PLATINUM_SILVER,AppearanceSettingsStore(a).read())
            assertEquals(UiTheme.PLATINUM_SILVER,DashboardTheme.palette.theme)
            assertEquals(SettingsPage.APPEARANCE,pages.last());assertEquals(before,media.read());assertEquals(0,changed)
            DashboardTheme.apply(a,UiTheme.GREEN)
            DashboardTheme.install(a)
            assertEquals(UiTheme.PLATINUM_SILVER,DashboardTheme.palette.theme)
            ShadowDialog.getLatestDialog().dismiss()
            a.getSharedPreferences("app_appearance", android.content.Context.MODE_PRIVATE).edit()
                .putString("theme", "PS4_NIGHT").commit()
            DashboardTheme.install(a)
            choose(panel.showAppearance(),"Tema do aplicativo")
            choose(ShadowDialog.getLatestDialog() as ConsoleDialog,"PlayStation 5")
            assertEquals(UiTheme.PS5,AppearanceSettingsStore(a).read())
            choose(ShadowDialog.getLatestDialog() as ConsoleDialog,"Cores")
            val colors = ShadowDialog.getLatestDialog() as ConsoleDialog
            assertEquals(2, all(colors.body).filterIsInstance<TextView>().count { it.isClickable })
            choose(colors,"Noturno")
            assertEquals(UiTheme.PS5_NIGHT,AppearanceSettingsStore(a).read())
            choose(ShadowDialog.getLatestDialog() as ConsoleDialog,"Tema do aplicativo")
            choose(ShadowDialog.getLatestDialog() as ConsoleDialog,"PlayStation 4")
            assertEquals(UiTheme.PS4_NIGHT,AppearanceSettingsStore(a).read())
            assertEquals(UiTheme.PS4_NIGHT,DashboardTheme.palette.theme)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun allThemesRepaintTheRealCatalogWithoutReloadingItAndCanRestoreTheExactGreenImage() {
        val server=MockWebServer().apply {
            dispatcher=object: Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val index=request.path!!.substringAfter("poster-").substringBefore('.').toInt()
                    val bytes=javaClass.getResourceAsStream("/showcase/poster-$index.png")!!.use { it.readBytes() }
                    return MockResponse().setHeader("Content-Type","image/png").setBody(Buffer().write(bytes))
                }
            }
            start()
        }
        val controller=Robolectric.buildActivity(Activity::class.java).setup();val a=controller.get()
        val games=listOf("007 First Light","1000xRESIST","15 in 1 Solitaire","If You're a Ghost Call Me Here","30 Birds","33 Immortals")
            .mapIndexed { i,name -> GameTitle("THEME-$i",name,"THEME-PRODUCT-$i",posterUrl=server.url("/poster-$i.png").toString(),
                tileUrl=server.url("/poster-$i.png").toString(),seriesOptimized=i!=2) }
        val library=GameLibrary(a,"theme-demo").apply { toggleFavorite(games[1].titleId) }
        val view=GameDashboardView(a,library,{}, {}, {}, {})
        try {
            a.setContentView(view);view.setGames(games);view.selectTab(LibraryTab.ALL)
            view.setProfile(XboxProfile("JOGADOR","PERFIL DEMO",subscription="ULTIMATE"))
            view.setGamePassProducts(setOf("THEME-PRODUCT-1","THEME-PRODUCT-5"))
            layout(view);view.restoreFocus();layout(view)
            val grid=all(view).filterIsInstance<RecyclerView>().single();val adapter=grid.adapter
            await { grid.childCount==6 && (0 until 6).all { i -> all(grid.getChildAt(i)).filterIsInstance<FixedArtworkView>().any { it.drawable is BitmapDrawable } } }
            shadowOf(Looper.getMainLooper()).idleFor(200,java.util.concurrent.TimeUnit.MILLISECONDS)
            await { all(view).filterIsInstance<FixedArtworkView>().single { it.contentDescription=="Ícone de ${games[0].name}" }.drawable is BitmapDrawable }
            assertEquals(6,adapter!!.itemCount)
            val selected=view.selectedGame?.titleId
            val original=capture(view,"green-original")
            val signatures=mutableSetOf<Int>()
            for(theme in UiTheme.entries) {
                AppearanceSettingsStore(a).save(theme)
                DashboardTheme.apply(a,theme,a.findViewById(android.R.id.content))
                layout(view)
                assertSame(adapter,grid.adapter);assertEquals(if(theme.scene==ConsoleScene.PS5) 5 else 6,adapter.itemCount)
                assertEquals(selected,view.selectedGame?.titleId);assertTrue(library.isFavorite(games[1].titleId))
                val name=theme.name.lowercase()
                val pixels=capture(view,name);signatures.add(pixels.contentHashCode())
            }
            assertEquals(UiTheme.entries.size,signatures.size)
            DashboardTheme.apply(a,UiTheme.GREEN,a.findViewById(android.R.id.content));layout(view)
            assertArrayEquals(original,capture(view,"green-restored"))
        } finally {
            view.close();controller.pause().stop().destroy();server.shutdown()
        }
    }
}
