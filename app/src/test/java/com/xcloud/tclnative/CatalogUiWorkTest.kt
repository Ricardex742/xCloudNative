package com.xcloud.tclnative

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.xcloud.tclnative.catalog.CatalogGenre
import com.xcloud.tclnative.catalog.GalleryProduct
import com.xcloud.tclnative.catalog.GameLibrary
import com.xcloud.tclnative.catalog.LibraryTab
import com.xcloud.tclnative.catalog.OfficialGallery
import com.xcloud.tclnative.catalog.OfficialGalleryKind
import com.xcloud.tclnative.i18n.AppLanguage
import com.xcloud.tclnative.i18n.UiText
import com.xcloud.tclnative.settings.UiTheme
import com.xcloud.tclnative.signaling.GameTitle
import com.xcloud.tclnative.ui.dashboard.*
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],qualifiers="w1280dp-h720dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class CatalogUiWorkTest {
    @Test fun toolbarFiltersAndLetterPickerWorkIndependentlyOnEachBrowseTab() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        UiText.apply(activity, AppLanguage.PORTUGUESE)
        DashboardTheme.apply(activity, UiTheme.GREEN)
        val view = GameDashboardView(activity, GameLibrary(activity, "genre-catalog"), {}, {}, {}, {})
        activity.setContentView(view)
        val items = listOf(GameTitle("a", "Águia", "AAAAAAAAAAAA", genres = setOf(CatalogGenre.SHOOTER)),
            GameTitle("b", "Barco", "BBBBBBBBBBBB", genres = setOf(CatalogGenre.SIMULATION)),
            GameTitle("z", "Zebra", "ZZZZZZZZZZZZ", genres = setOf(CatalogGenre.SHOOTER)))
        fun views(root: View): Sequence<View> = sequence {
            yield(root)
            if (root is ViewGroup) for (index in 0 until root.childCount) yieldAll(views(root.getChildAt(index)))
        }
        fun layout() {
            view.measure(View.MeasureSpec.makeMeasureSpec(1280, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, 1280, 720)
        }
        fun selectTab(tab: LibraryTab) {
            view.selectTab(tab)
            view.awaitCatalog()
            layout()
        }
        fun chooseGenre(label: String) {
            val genre = views(view).filterIsInstance<TextView>().single { it.text.toString() == "Gêneros" && it.isShown }
            assertTrue(genre.performClick())
            val picker = ShadowDialog.getLatestDialog() as ConsoleDialog
            assertTrue(views(picker.panel).filterIsInstance<TextView>().single { it.text.toString() == label }.performClick())
            view.awaitCatalog()
            layout()
        }
        try {
            view.setGames(items)
            selectTab(LibraryTab.ALL)
            val alphabet = views(view).filterIsInstance<TextView>().single { it.text.toString() == "A–Z" }
            val grid = views(view).filterIsInstance<RecyclerView>().single()
            assertTrue(alphabet.isShown && (alphabet.parent as View).bottom <= grid.top)
            chooseGenre("Tiro")
            assertEquals("Águia", view.selectedGame?.name)
            assertTrue(alphabet.requestFocus())
            assertTrue(view.handleKey(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_A)))
            val picker = ShadowDialog.getLatestDialog() as ConsoleDialog
            val letters = views(picker.body).filterIsInstance<TextView>().associateBy { it.text.toString() }
            assertFalse(letters.getValue("B").isEnabled)
            assertTrue(letters.getValue("Z").performClick())
            shadowOf(Looper.getMainLooper()).idle()
            layout()
            assertEquals("Zebra", view.selectedGame?.name)
            selectTab(LibraryTab.FAVORITES)
            assertFalse(alphabet.isShown)
            view.setGamePassProducts(setOf("AAAAAAAAAAAA", "BBBBBBBBBBBB"))
            selectTab(LibraryTab.GAME_PASS)
            assertTrue(alphabet.isShown && alphabet.isEnabled)
            chooseGenre("Simulação")
            assertEquals("Barco", view.selectedGame?.name)
            view.setOfficialGallery(OfficialGalleryKind.PURCHASED,
                OfficialGallery(OfficialGalleryKind.PURCHASED, listOf(
                    GalleryProduct("AAAAAAAAAAAA", englishName = "Zebra"),
                    GalleryProduct("BBBBBBBBBBBB", englishName = "Boat"))))
            selectTab(LibraryTab.PURCHASED)
            assertTrue(alphabet.isShown && alphabet.isEnabled)
            chooseGenre("Tiro")
            assertEquals("Águia", view.selectedGame?.name)
            assertTrue(alphabet.performClick())
            val purchasedPicker = ShadowDialog.getLatestDialog() as ConsoleDialog
            assertTrue(views(purchasedPicker.body).filterIsInstance<TextView>().single { it.text.toString() == "Z" }.isEnabled)
            assertFalse(views(purchasedPicker.body).filterIsInstance<TextView>().single { it.text.toString() == "A" }.isEnabled)
            purchasedPicker.dismiss()
            selectTab(LibraryTab.GAME_PASS)
            assertEquals("Barco", view.selectedGame?.name)
        } finally {
            view.close()
            controller.pause().stop().destroy()
        }
    }

    @Test fun largeCatalogStaysReachableDuringTabSwitchesAndMetadataBursts() {
        val c=Robolectric.buildActivity(Activity::class.java).setup();val a=c.get()
        val view=GameDashboardView(a,com.xcloud.tclnative.catalog.GameLibrary(a,"large-catalog"),{},{},{},{})
        a.setContentView(view)
        val games=(0 until 4000).map { com.xcloud.tclnative.signaling.GameTitle("G$it","Game %04d".format(it)) }
        view.setGames(games)
        repeat(12) { i ->
            view.selectTab(if(i%2==0) com.xcloud.tclnative.catalog.LibraryTab.ALL else com.xcloud.tclnative.catalog.LibraryTab.FAVORITES)
            view.updateMetadata(listOf(games[i].copy(name="Updated %04d".format(i))))
            org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle()
        }
        view.selectTab(com.xcloud.tclnative.catalog.LibraryTab.ALL);view.awaitCatalog()
        assertNotNull(view.selectedGame)
        view.setSearch("Updated 0011");view.awaitCatalog()
        assertEquals("G11",view.selectedGame!!.titleId)
        view.close();c.pause().stop().destroy()
    }
    @Test fun fixedCoverChangesInvalidatePixelsWithoutRequestingAParentRemeasureAndCanStillResize() {
        val c=Robolectric.buildActivity(Activity::class.java).setup(); val a=c.get()
        val parent=object: FrameLayout(a) { var requests=0; override fun requestLayout() { requests++; super.requestLayout() } }
        val image=FixedArtworkView(a)
        parent.addView(image,FrameLayout.LayoutParams(192,320)); a.setContentView(parent)
        fun layout() {
            parent.measure(View.MeasureSpec.makeMeasureSpec(1280,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(720,View.MeasureSpec.EXACTLY))
            parent.layout(0,0,1280,720)
        }
        layout(); val before=parent.requests
        val bitmap=Bitmap.createBitmap(128,192,Bitmap.Config.RGB_565).apply { eraseColor(Color.BLUE) }
        image.setImageBitmap(bitmap); image.setImageDrawable(null); image.setImageBitmap(bitmap)
        assertEquals(before,parent.requests)
        assertEquals(192,image.width); assertEquals(320,image.height)
        val pixels=Bitmap.createBitmap(192,320,Bitmap.Config.ARGB_8888)
        image.draw(Canvas(pixels))
        assertEquals(Color.BLUE,pixels.getPixel(96,160))
        image.layoutParams=FrameLayout.LayoutParams(256,384); layout()
        assertEquals(256,image.width); assertEquals(384,image.height)
        c.pause().stop().destroy()
    }
    @Test fun cachedAndNewImagesUseTheSameFrameBudgetAndCancelledBindingsDoNotRun() {
        val frames=mutableListOf<()->Unit>(); val delivered=mutableListOf<Int>()
        val queue=ArtworkFrameQueue({frames.add(it)},2)
        repeat(9) { i -> queue.post(i) { delivered.add(i) } }
        queue.remove(0); queue.post(1) { delivered.add(100) }
        assertTrue(delivered.isEmpty()); assertEquals(1,frames.size)
        frames.removeAt(0).invoke()
        assertEquals(listOf(100,2),delivered)
        assertEquals(1,frames.size)
        queue.clear(); frames.removeAt(0).invoke()
        assertEquals(listOf(100,2),delivered)
    }
    @Test fun metadataBurstsAreCoalescedWithoutLosingLatestTitlesOrSelection() {
        val c=Robolectric.buildActivity(Activity::class.java).setup(); val a=c.get()
        val view=GameDashboardView(a,com.xcloud.tclnative.catalog.GameLibrary(a,"coalescing"),{},{},{},{})
        a.setContentView(view)
        val initial=listOf(com.xcloud.tclnative.signaling.GameTitle("one","One"))
        view.setGames(initial)
        view.selectTab(com.xcloud.tclnative.catalog.LibraryTab.ALL)
        view.awaitCatalog()
        repeat(20) { view.updateMetadata(listOf(initial[0].copy(name="Name $it"))) }
        assertEquals("One",view.selectedGame!!.name)
        view.awaitCatalog()
        assertEquals("Name 19",view.selectedGame!!.name)
        view.updateMetadata(listOf(initial[0].copy(name="Old"))); view.setGames(initial)
        view.awaitCatalog()
        assertEquals("One",view.selectedGame!!.name)
        view.close(); c.pause().stop().destroy()
    }
}
