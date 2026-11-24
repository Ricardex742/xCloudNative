package com.xcloud.tclnative

import android.app.Activity
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import androidx.core.view.allViews
import androidx.recyclerview.widget.RecyclerView
import com.xcloud.tclnative.catalog.GameLibrary
import com.xcloud.tclnative.catalog.FriendPresence
import com.xcloud.tclnative.catalog.LibraryTab
import com.xcloud.tclnative.catalog.StoreContentNotice
import com.xcloud.tclnative.catalog.StoreGameDetails
import com.xcloud.tclnative.catalog.XboxAchievement
import com.xcloud.tclnative.catalog.XboxFriend
import com.xcloud.tclnative.catalog.XboxGameProgress
import com.xcloud.tclnative.catalog.XboxProfile
import com.xcloud.tclnative.signaling.GameTitle
import com.xcloud.tclnative.ui.dashboard.GameDashboardView
import com.xcloud.tclnative.ui.dashboard.DashboardTheme
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class DashboardTest {
    @Test fun returnToPlayUsesCloudOrderKeepsCachedHistoryOnFailureAndClearsConfirmedEmpty() {
        val controller=Robolectric.buildActivity(Activity::class.java).setup()
        val activity=controller.get()
        val library=GameLibrary(activity,"cloud-history-ui")
        library.recordPlayed("game-0",9999)
        library.saveCloudRecent(listOf("game-7","game-2"))
        val view=GameDashboardView(activity,library,{}, {}, {}, {})
        fun all(node: View): List<View> = listOf(node)+if(node is ViewGroup)
            (0 until node.childCount).flatMap { all(node.getChildAt(it)) } else emptyList()
        try {
            activity.setContentView(view); view.setGames(games); layout(view,1280,720)
            assertEquals(LibraryTab.RECENT,view.activeTab)
            val grid=all(view).filterIsInstance<RecyclerView>().single()
            assertEquals(2,grid.adapter!!.itemCount)
            assertEquals("game-7",view.selectedGame?.titleId)
            assertTrue(all(view).filterIsInstance<TextView>().any { it.text.toString()=="VOLTAR A JOGAR" })
            view.setRecentHistory(listOf("game-3","game-1")); layout(view,1280,720)
            assertEquals("game-3",view.selectedGame?.titleId)
            view.beginRecentRefresh(); view.setRecentHistory(null); layout(view,1280,720)
            assertEquals(2,grid.adapter!!.itemCount)
            assertEquals("game-3",view.selectedGame?.titleId)
            assertTrue(all(view).filterIsInstance<TextView>().any { it.text.contains("HISTÓRICO SALVO") })
            view.setRecentHistory(emptyList()); layout(view,1280,720)
            assertEquals(0,grid.adapter!!.itemCount); assertNull(view.selectedGame)
            view.setRecentHistory(null); layout(view,1280,720)
            assertTrue(all(view).filterIsInstance<TextView>().any { it.text.contains("Não foi possível consultar") })
        } finally { view.close(); controller.pause().stop().destroy() }
    }
    @Test fun magnifierOpensSearchAndOnlyActivatingTheFieldShowsTheKeyboard() {
        val controller=Robolectric.buildActivity(Activity::class.java).setup()
        val activity=controller.get()
        shadowOf(activity.packageManager).setSystemFeature(android.content.pm.PackageManager.FEATURE_TOUCHSCREEN,true)
        var played=0; var options=0
        lateinit var view: GameDashboardView
        view=GameDashboardView(activity,GameLibrary(activity,"phone-search"),{played++},{
            com.xcloud.tclnative.ui.dashboard.ConsoleDialogs.search(activity,view.searchQuery) { view.setSearch(it) }
        },{options++},{})
        activity.setContentView(view); view.setGames(games); layout(view,1280,720)
        val magnifier=(0 until view.childCount).map(view::getChildAt).single { it.contentDescription=="Pesquisar jogos" }
        assertTrue(magnifier.right<=106)
        assertTrue(magnifier.width>=80)
        magnifier.performClick(); shadowOf(Looper.getMainLooper()).idle()
        val dialog=org.robolectric.shadows.ShadowDialog.getLatestDialog() as com.xcloud.tclnative.ui.dashboard.ConsoleDialog
        fun all(node: View): List<View> = listOf(node)+if(node is ViewGroup)
            (0 until node.childCount).flatMap { all(node.getChildAt(it)) } else emptyList()
        val field=all(dialog.body).filterIsInstance<android.widget.EditText>().single()
        assertTrue(field.hasFocus())
        val ime=activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        assertFalse(shadowOf(ime).isSoftInputVisible)
        field.performClick(); shadowOf(Looper.getMainLooper()).idle()
        assertTrue(shadowOf(ime).isSoftInputVisible)
        field.setText("Halo Infinite"); field.onEditorAction(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH)
        layout(view,1280,720)
        assertEquals("Halo Infinite",view.searchQuery)
        assertEquals(LibraryTab.ALL,view.activeTab)
        val grid=all(view).filterIsInstance<RecyclerView>().single()
        assertEquals(1,grid.adapter!!.itemCount); assertEquals(0,played); assertEquals(0,options)
        view.close(); controller.pause().stop().destroy()
    }
    private val games = listOf("Forza Horizon 5", "33 Immortals", "Battlefield 1", "Diablo IV",
        "Gears of War", "Delta Force", "Sea of Thieves", "Halo Infinite", "Grounded", "Minecraft",
        "Microsoft Flight Simulator", "Indiana Jones").mapIndexed { i, name -> GameTitle("game-$i", name, "product-$i", seriesOptimized = i % 2 == 0) }

    private fun layout(view: View, w: Int = 1280, h: Int = 948) {
        (view as? GameDashboardView)?.awaitCatalog()
        view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, w, h)
        shadowOf(Looper.getMainLooper()).idle()
        // The test Activity's window traversal uses its default display; lay out the component
        // once more at the requested export dimensions after draining that traversal.
        view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, w, h)
    }
    private fun key(view: GameDashboardView, code: Int) {
        assertTrue(view.handleKey(KeyEvent(KeyEvent.ACTION_DOWN, code)))
        assertTrue(view.handleKey(KeyEvent(KeyEvent.ACTION_UP, code)))
        view.awaitCatalog()
    }
    @Test fun shoulderTabsWrapAndFavoritesSurviveViewRecreationWithoutLeakingBetweenAccounts() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        activity.getSharedPreferences("library_"+GameLibrary.accountKey("account-a"), 0).edit().clear().commit()
        val library = GameLibrary(activity, "account-a")
        library.saveCloudRecent(listOf(games.first().titleId))
        val view = GameDashboardView(activity, library, {}, {}, {}, {})
        activity.setContentView(view); view.setGames(games); layout(view)
        assertEquals(LibraryTab.RECENT, view.activeTab)
        key(view, KeyEvent.KEYCODE_BUTTON_Y)
        view.awaitFavoriteWrites()
        assertTrue(games.first().titleId in GameLibrary(activity, "account-a").favorites)
        assertTrue(GameLibrary(activity, "account-b").favorites.isEmpty())
        key(view, KeyEvent.KEYCODE_BUTTON_L1)
        assertEquals(LibraryTab.ALL,view.activeTab)
        key(view, KeyEvent.KEYCODE_BUTTON_R1)
        assertEquals(LibraryTab.RECENT, view.activeTab)
        key(view, KeyEvent.KEYCODE_BUTTON_R1)
        assertEquals(LibraryTab.FAVORITES, view.activeTab)
        assertEquals(games.first().titleId, view.selectedGame?.titleId)
        key(view, KeyEvent.KEYCODE_BUTTON_Y)
        view.awaitFavoriteWrites()
        assertNull(view.selectedGame)
        assertTrue(library.favorites.isEmpty())
        view.close(); controller.pause().stop().destroy()
    }
    @Test fun aSlowFavoriteCommitKeepsTheOldSnapshotAndLetsTheCatalogNavigate() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val rejectCommit = AtomicBoolean(false)
        val commitThread = AtomicReference<Thread>()
        val target = "library_" + GameLibrary.accountKey("delayed-favorite")
        val preference = activity.getSharedPreferences(target, 0)
        val wrapped = object : SharedPreferences by preference {
            override fun edit(): SharedPreferences.Editor {
                val editor = preference.edit()
                return object : SharedPreferences.Editor by editor {
                    private var savesFavorites = false
                    override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor {
                        savesFavorites = key == "favorites"
                        editor.putStringSet(key, values)
                        return this
                    }
                    override fun commit(): Boolean {
                        if (savesFavorites) {
                            commitThread.set(Thread.currentThread())
                            entered.countDown()
                            check(release.await(5, TimeUnit.SECONDS))
                            if (rejectCommit.get()) {
                                editor.apply()
                                return false
                            }
                        }
                        return editor.commit()
                    }
                }
            }
        }
        val context = object : ContextWrapper(activity) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                if (name == target) wrapped else super.getSharedPreferences(name, mode)
        }
        val library = GameLibrary(context, "delayed-favorite")
        val title = games.first().titleId
        library.saveCloudRecent(listOf(title))
        val acknowledgements = mutableListOf<Boolean>()
        val view = GameDashboardView(activity, library, {}, {}, {}, {}, onFavoritesChanged = {
            acknowledgements += library.isFavorite(title)
        })
        try {
            activity.setContentView(view); view.setGames(games); layout(view)
            key(view, KeyEvent.KEYCODE_BUTTON_Y)
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertNotSame(Thread.currentThread(), commitThread.get())
            assertFalse(library.isFavorite(title))
            assertTrue(acknowledgements.isEmpty())
            key(view, KeyEvent.KEYCODE_BUTTON_Y)
            key(view, KeyEvent.KEYCODE_BUTTON_L1)
            assertEquals(LibraryTab.ALL, view.activeTab)
            release.countDown()
            view.awaitFavoriteWrites()
            assertEquals(listOf(true, false), acknowledgements)
            assertFalse(library.isFavorite(title))

            rejectCommit.set(true)
            view.selectTab(LibraryTab.RECENT); layout(view)
            key(view, KeyEvent.KEYCODE_BUTTON_Y)
            view.awaitFavoriteWrites()
            assertEquals(listOf(true, false), acknowledgements)
            assertFalse(library.isFavorite(title))
            assertEquals(emptySet<String>(), preference.getStringSet("favorites", emptySet()))
        } finally {
            release.countDown()
            view.close(); controller.pause().stop().destroy()
        }
    }
    @Test
    @Config(qualifiers = "w1280dp-h600dp-land-mdpi")
    fun holdingACoverShowsDetailsWithIndependentDescriptionAndGallery() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        var played: GameTitle? = null
        var shortcut: GameTitle? = null
        var options: GameTitle? = null
        var openedStore: String? = null
        var progressLoads = 0
        val images = com.xcloud.tclnative.ui.dashboard.ArtworkBackend.get(activity)
        fun fixture(name: String, file: String, width: Int, height: Int): String {
            val url = "https://example.test/$name"
            val size = images.policy.sizeFor(width, height)
            val original = javaClass.getResourceAsStream("/console-demo/$file")!!.use { android.graphics.BitmapFactory.decodeStream(it) }
            val fitted = size.fit(original.width, original.height)
            val bitmap = Bitmap.createScaledBitmap(original, fitted.width, fitted.height, true)
            if (bitmap !== original) original.recycle()
            images.cache.put(com.xcloud.tclnative.ui.dashboard.ArtworkRequest.create(url, size.width, size.height), bitmap)
            return url
        }
        val poster = fixture("poster", "forza.jpg", 384, 576)
        val hero = fixture("hero", "forza-hero.jpg", 256, 160)
        val friendAvatar = fixture("avatar", "forza.jpg", 128, 128)
        val previews = List(4) { i -> fixture("preview-$i", "forza-hero.jpg", 640, 360) }
        val body = "Explore as estradas, descubra paisagens e participe de corridas. ".repeat(50)
        val view = GameDashboardView(activity, GameLibrary(activity, "selection"), { played = it }, {}, {}, {},
            onGameShortcut = { shortcut = it }, onLoadGameDetails = {
                assertEquals("TESTGAME0260", it)
                StoreGameDetails("Editora", body, "10", previews, 2026,
                    listOf(StoreContentNotice.VIOLENCE), listOf(StoreContentNotice.IN_GAME_PURCHASES))
            }, onLoadGameProgress = {
                assertEquals("42", it)
                progressLoads++
                XboxGameProgress(430, 800, 6, 15, listOf(
                    XboxAchievement("Primeira corrida", 20, "Vença uma corrida.", true, 100),
                    XboxAchievement("Colecionador", 15, "Compre três carros.", false, 33, 64.37),
                    XboxAchievement("Explorador", 50, "Descubra uma estrada.", false, 0),
                    XboxAchievement("Piloto de teste", 10, "Complete o tutorial.", false, 10)
                ))
            }, onGameOptions = { options = it }, onStoreProduct = { openedStore = it },
            onLoadFriends = {
                listOf(XboxFriend("7", "Corredor", FriendPresence.ONLINE, null, "42", friendAvatar),
                    XboxFriend("8", "Em outro jogo", FriendPresence.ONLINE, null, "43"),
                    XboxFriend("9", "Fora de linha", FriendPresence.OFFLINE, null, "42"))
            })
        activity.setContentView(view)
        view.setGames(games + (12..260).map {
            GameTitle("cloud-$it", "Jogo $it", "TESTGAME%04d".format(it), posterUrl = poster.takeIf { _ -> it == 260 },
                heroUrl = hero.takeIf { _ -> it == 260 }, xboxTitleId = "42".takeIf { _ -> it == 260 })
        })
        layout(activity.window.decorView, 1280, 600)
        view.setSearch("Jogo 260"); layout(view, 1280, 600)
        val grid = (0 until view.childCount).map(view::getChildAt).filterIsInstance<RecyclerView>().single()
        assertEquals(1, grid.adapter!!.itemCount)
        assertTrue(grid.getChildAt(0).performLongClick())
        assertNull(played)
        val details = currentGameDetails()
        val root = details.window!!.decorView
        layout(root, 1280, 600)
        assertEquals("Editora · 2026", details.detailsPublisher?.text.toString())
        assertTrue(details.detailsRatingNotes!!.text.contains("Compras no jogo"))
        val description = details.body.findViewWithTag<TextView>("game-details-description")
        val progress = details.body.findViewWithTag<View>("game-progress")
        val cover = details.body.findViewWithTag<View>("game-details-cover")
        val gallery = details.body.findViewWithTag<android.widget.LinearLayout>("game-details-gallery")
        assertTrue(details.wideDetails)
        assertTrue(cover.width > 0 && gallery.width > cover.width)
        assertEquals(2, gallery.childCount)
        assertEquals(2, (gallery.getChildAt(0) as ViewGroup).childCount)
        assertEquals(2, (gallery.getChildAt(1) as ViewGroup).childCount)
        assertEquals(View.VISIBLE, progress.visibility)
        assertEquals(View.GONE, description.visibility)
        assertEquals("430 / 800   ·   53%", details.body.findViewWithTag<TextView>("game-progress-score").text.toString())
        assertTrue(details.body.allViews.filterIsInstance<TextView>().any { it.text.toString() == "Corredor" })
        assertFalse(details.body.allViews.filterIsInstance<TextView>().any { it.text.toString() == "Em outro jogo" })
        details.body.findViewWithTag<View>("game-progress-refresh").performClick()
        assertEquals(2, progressLoads)
        layout(root, 1280, 600)
        val list = details.body.findViewWithTag<RecyclerView>("game-progress-list")
        assertEquals(4, list.adapter?.itemCount)
        assertTrue(list.findViewWithTag<View>("game-progress-row-1").performClick())
        layout(root, 1280, 600)
        val achievementDescription = list.findViewWithTag<View>("game-progress-row-1")
            .findViewWithTag<TextView>("game-progress-description")
        assertTrue(achievementDescription.text.contains("Compre três carros."))
        assertTrue(achievementDescription.text.contains("33%"))
        assertEquals(View.VISIBLE, achievementDescription.visibility)
        assertFalse(list.allViews.any { it is android.widget.ImageView })
        assertTrue(gallery.height > 0)
        val thumbnail = (gallery.getChildAt(0) as ViewGroup).getChildAt(0)
        assertTrue(thumbnail.width > 0 && thumbnail.height > 0)
        val backdrop = details.detailsBackdrop!!
        val backdropDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (backdrop.drawable == null && System.nanoTime() < backdropDeadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        val blurred = (backdrop.drawable as android.graphics.drawable.BitmapDrawable).bitmap
        assertEquals(144, blurred.width)
        assertTrue(blurred.height <= 96)
        layout(root, 1280, 600)
        val bitmap = android.graphics.Bitmap.createBitmap(root.width, root.height, android.graphics.Bitmap.Config.ARGB_8888)
        root.draw(android.graphics.Canvas(bitmap))
        File("build/reports/dashboard/game-details-wide.png").apply { parentFile!!.mkdirs() }.outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
        details.panel.findViewWithTag<View>("game-details-progress").performClick()
        layout(root, 1280, 600)
        assertEquals(View.GONE, progress.visibility)
        assertTrue(description.height > (description.parent as View).height)
        details.panel.findViewWithTag<View>("game-details-settings").performClick()
        details.panel.findViewWithTag<View>("game-details-store").performClick()
        assertEquals("cloud-260", options?.titleId)
        assertEquals("TESTGAME0260", openedStore)
        assertEquals("Atalho na tela inicial", details.panel.findViewWithTag<View>("game-details-shortcut").contentDescription.toString())
        details.panel.findViewWithTag<View>("game-details-shortcut").performClick()
        assertEquals("cloud-260", shortcut?.titleId)
        assertTrue(details.isShowing)
        details.panel.findViewWithTag<View>("game-details-play").performClick()
        assertEquals("cloud-260", played?.titleId)
        view.close(); controller.pause().stop().destroy()
    }
    @Test fun greenHeaderHasReadableSubscriptionAndSettingsBesideItAndGamePassFailsClosed() {
        val controller=Robolectric.buildActivity(Activity::class.java).setup()
        val activity=controller.get()
        var opened=0
        val view=GameDashboardView(activity,GameLibrary(activity,"settings-header"),{}, {}, { opened++ }, {})
        activity.setContentView(view); view.setGames(games); view.selectTab(LibraryTab.ALL)
        fun descendants(node: View): List<View> = listOf(node) + if(node is ViewGroup)
            (0 until node.childCount).flatMap { descendants(node.getChildAt(it)) } else emptyList()
        for (tier in listOf(null,"ULTIMATE","FREE")) {
            view.setProfile(XboxProfile("Xbox User","XboxUser",subscription=tier))
            layout(view,1280,720)
            val labels=descendants(view).filterIsInstance<TextView>()
            assertFalse(labels.any { it.text.contains("pts") || it.text.contains("Rewards") })
            val expected=when(tier) { null -> "ASSINATURA NÃO INFORMADA"; "FREE" -> "FREE"; else -> "GAME PASS ULTIMATE" }
            val badge=labels.single { it.text.toString()==expected }
            assertNull(badge.layout.getEllipsisCount(0).takeIf { it>0 })
            val gear=labels.single { it.contentDescription=="Configurações" }
            assertTrue(gear.left>=badge.right)
            assertTrue(gear.right<=(gear.parent as View).width)
            gear.requestFocus(); key(view,KeyEvent.KEYCODE_BUTTON_A)
        }
        assertEquals(3,opened)
        view.setSearch("Forza Horizon 5"); layout(view,1280,720)
        val title=descendants(view).filterIsInstance<TextView>().single { it.text.toString().contains("FORZA\nHORIZON 5") }.text as Spanned
        assertEquals(DashboardTheme.accent,title.getSpans(0,title.length,ForegroundColorSpan::class.java).single().foregroundColor)
        view.setSearch("")
        view.selectTab(LibraryTab.GAME_PASS); layout(view,1280,720)
        val grid=descendants(view).filterIsInstance<RecyclerView>().single()
        assertEquals(0,grid.adapter!!.itemCount)
        view.setGamePassProducts(setOf("product-1","product-3")); layout(view,1280,720)
        assertEquals(2,grid.adapter!!.itemCount)
        assertEquals(games[1].titleId,view.selectedGame?.titleId)
        view.setGamePassProducts(null); layout(view,1280,720)
        assertEquals(0,grid.adapter!!.itemCount); assertNull(view.selectedGame)
        view.close(); controller.pause().stop().destroy()
    }
    @Test fun recentHistoryPersistsPerAccountAcrossCatalogRecreation() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        activity.getSharedPreferences("library_"+GameLibrary.accountKey("history"), 0).edit().clear().commit()
        val library = GameLibrary(activity, "history")
        library.recordPlayed("FORZA", 100)
        library.recordPlayed("FORTNITE", 200)
        library.recordPlayed("FORZA", 300)
        val restored = GameLibrary(activity, "history").records()
        assertEquals(2, restored["FORZA"]?.launches)
        assertEquals(300L, restored["FORZA"]?.lastPlayed)
        assertTrue(GameLibrary(activity, "another-history").records().isEmpty())
        controller.pause().stop().destroy()
    }
    @Test fun directionalFocusUpdatesTheHeaderAndAStartsTheFocusedGame() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        var played: GameTitle? = null
        val view = GameDashboardView(activity, GameLibrary(activity, "dpad"), { played = it }, {}, {}, {})
        activity.setContentView(view); view.setGames(games); view.selectTab(LibraryTab.ALL)
        view.restoreFocus() // Requested before RecyclerView has created any cover holders.
        layout(view, 1920, 1080)
        val grid = (0 until view.childCount).map(view::getChildAt).filterIsInstance<RecyclerView>().single()
        val first = grid.getChildAt(0)
        assertFalse("Custom card focus must not have Android's second focus rectangle", first.defaultFocusHighlightEnabled)
        assertTrue("Initial focus must reach the cover after layout", first.hasFocus())
        assertTrue(first.requestFocus())
        val right = first.focusSearch(View.FOCUS_RIGHT)
        assertNotNull(right)
        assertTrue(right.requestFocus())
        assertEquals(games[2].titleId, view.selectedGame?.titleId)
        val down = right.focusSearch(View.FOCUS_DOWN)
        assertNotNull(down)
        assertTrue(down.requestFocus())
        assertEquals(games[7].titleId, view.selectedGame?.titleId)
        key(view, KeyEvent.KEYCODE_BUTTON_A)
        confirmGameDetailsPlay()
        assertEquals(games[7].titleId, played?.titleId)
        view.close(); controller.pause().stop().destroy()
    }
    @Test fun authenticatedUserTierSurvivesProfileUpdatesButOnlyOfficialMembershipMarksTheGames() {
        val controller=Robolectric.buildActivity(Activity::class.java).setup()
        val activity=controller.get()
        val view=GameDashboardView(activity,GameLibrary(activity,"tier-claims"),{}, {}, {}, {})
        activity.setContentView(view)
        view.setGames(listOf(GameTitle("FH5","Forza Horizon 5","EDITION-5",programs=listOf("GPULTIMATE")),
            GameTitle("FH6","Forza Horizon 6","EDITION-6",userPrograms=listOf("GPULTIMATE"))))
        view.setProfile(XboxProfile("HAZERFROG","HAZERFROG"))
        view.setGamePassProducts(setOf("OTHER-EDITION-5"))
        view.selectTab(LibraryTab.GAME_PASS); layout(view,1280,720)
        fun descendants(node: View): List<View> = listOf(node) + if(node is ViewGroup)
            (0 until node.childCount).flatMap { descendants(node.getChildAt(it)) } else emptyList()
        assertTrue(descendants(view).filterIsInstance<TextView>().any { it.text.toString()=="GAME PASS ULTIMATE" })
        assertEquals(0,descendants(view).filterIsInstance<RecyclerView>().single().adapter!!.itemCount)
        view.setGamePassProducts(setOf("OTHER-EDITION-5"),setOf("FH5","FH6")); layout(view,1280,720)
        assertEquals(2,descendants(view).filterIsInstance<RecyclerView>().single().adapter!!.itemCount)
        view.close(); controller.pause().stop().destroy()
    }
    @Test fun nativeLayoutRendersAtReferenceAndTvSizesWithEqualPortraitCovers() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val view = GameDashboardView(activity, GameLibrary(activity, "visual-preview"), {}, {}, {}, {})
        activity.setContentView(view)
        view.setGames(games)
        view.selectTab(LibraryTab.ALL)
        view.setGamePassProducts(setOf("product-0", "product-1", "product-3"))
        view.setProfile(XboxProfile("LUCAS", "LUCAS XBOX", subscription = "ULTIMATE"))
        for ((w, h) in listOf(1280 to 948, 1920 to 1080, 3840 to 2160)) {
            layout(view, w, h)
            view.restoreFocus(); layout(view, w, h)
            val grid = (0 until view.childCount).map(view::getChildAt).filterIsInstance<RecyclerView>().single()
            assertTrue(grid.childCount >= 6)
            val row = (0 until 6).map(grid::getChildAt)
            assertTrue(row.all { !it.defaultFocusHighlightEnabled })
            assertTrue(row.all { it.width == row.first().width || kotlin.math.abs(it.width-row.first().width) <= 1 })
            assertTrue(row.all { it.height > it.width })
            assertTrue(grid.bottom < h)
            assertEquals(w, view.width)
            assertTrue(grid.right > w * .9f)
            assertTrue(grid.width > w * .8f)
            val icon = (0 until view.childCount).map(view::getChildAt).filterIsInstance<android.widget.ImageView>()
                .first { it.contentDescription?.startsWith("Ícone de") == true }
            assertTrue("Full game header must fit above the tabs and grid", icon.bottom < grid.top)
            assertTrue("Game icon must remain large on TV", icon.height >= w * .085f)
            assertTrue(grid.height > grid.getChildAt(0).height)
            grid.getChildAt(0).requestFocus()
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val file = File("build/reports/dashboard/catalog-${w}x$h.png").apply { parentFile?.mkdirs() }
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        view.close(); controller.pause().stop().destroy()
    }
}
