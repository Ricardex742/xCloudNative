package com.xcloud.tclnative

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import com.xcloud.tclnative.catalog.*
import com.xcloud.tclnative.i18n.AppLanguage
import com.xcloud.tclnative.i18n.UiText
import com.xcloud.tclnative.signaling.GameTitle
import com.xcloud.tclnative.ui.dashboard.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],qualifiers="w1280dp-h720dp-land-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GameOwnershipUiTest {
    private fun all(v: View): List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap { all(v.getChildAt(it)) } else emptyList()
    @Test fun ownershipLookupIsLazyAccountScopedAndMissingRightsRemainSeparate() {
        val controller=Robolectric.buildActivity(Activity::class.java).setup();val a=controller.get()
        UiText.apply(a,AppLanguage.PORTUGUESE)
        var requests=0
        val view=GameDashboardView(a,GameLibrary(a,"ownership-one"),{},{},{},{},onOwnershipNeeded={ requests++ })
        try {
            a.setContentView(view)
            view.setGames(listOf(GameTitle("A","Alpha","AAA000000001"),GameTitle("B","Beta","BBB000000002")))
            view.selectTab(LibraryTab.ALL);view.awaitCatalog();assertEquals(0,requests)
            view.setOwnershipFilter(OwnershipFilter.OWNED);assertEquals(1,requests)
            assertEquals(setOf("AAA000000001","BBB000000002"),view.beginOwnershipRequest())
            view.updateOwnership(mapOf("AAA000000001" to Ownership.OWNED));view.finishOwnershipRequest();view.awaitCatalog()
            assertEquals("Alpha",view.selectedGame?.name)
            view.setOwnershipFilter(OwnershipFilter.NOT_OWNED);view.awaitCatalog();assertNull(view.selectedGame)
            view.setOwnershipFilter(OwnershipFilter.UNKNOWN);view.awaitCatalog();assertEquals("Beta",view.selectedGame?.name)
            view.setSearch("Alpha");view.awaitCatalog();assertNull(view.selectedGame)
            view.setSearch("");view.setOwnershipFilter(OwnershipFilter.ALL);view.awaitCatalog();assertNotNull(view.selectedGame)
            assertEquals(1,requests)
            val other=GameDashboardView(a,GameLibrary(a,"ownership-two"),{},{},{},{})
            try {
                other.setGames(listOf(GameTitle("A","Alpha","AAA000000001")));other.selectTab(LibraryTab.ALL)
                other.setOwnershipFilter(OwnershipFilter.OWNED);other.awaitCatalog();assertNull(other.selectedGame)
            } finally { other.close() }
        } finally { view.close();controller.pause().stop().destroy() }
    }
    @Test fun openingFilterRetainsTypedSearchAndClearSearchRemainsClickable() {
        val controller=Robolectric.buildActivity(Activity::class.java).setup();val a=controller.get();UiText.apply(a,AppLanguage.PORTUGUESE)
        try {
            var query="";var filters=0
            val search=ConsoleDialogs.search(a,"",ConsoleAction("Games") { filters++ }) { query=it }
            all(search.body).filterIsInstance<EditText>().single().setText("Forza")
            all(search.body).filterIsInstance<TextView>().single {it.isClickable&&it.text.toString()=="Games"}.performClick()
            assertEquals("Forza",query);assertEquals(1,filters)
            val next=ConsoleDialogs.search(a,query) { query=it }
            all(next.window!!.decorView).filterIsInstance<TextView>().single {it.isClickable&&it.text.toString().equals("Limpar busca",true)}.performClick()
            assertEquals("",query)
        } finally { controller.pause().stop().destroy() }
    }
    @Test fun allFilterChoicesUseVerticalFocusableRowsAndPreserveTheSelection() {
        val controller=Robolectric.buildActivity(Activity::class.java).setup();val a=controller.get();UiText.apply(a,AppLanguage.PORTUGUESE)
        try {
            var selected: OwnershipFilter?=null
            val dialog=LibraryFilterPanel.show(a,OwnershipFilter.NOT_OWNED,"Adquiridos: 1",{selected=it},{},{})
            capturePanel(dialog,"library-ownership-filters")
            val rows=all(dialog.body).filterIsInstance<TextView>().filter {it.isClickable}
            assertEquals(5,rows.size);assertTrue(rows.all {it.isFocusable})
            assertTrue(rows.single {it.text.contains("Não adquiridos")}.text.startsWith("✓"))
            assertTrue(rows.single {it.text.contains("Não adquiridos")}.hasFocus())
            rows.single {it.text.contains("Propriedade não confirmada")}.performClick()
            assertEquals(OwnershipFilter.UNKNOWN,selected)
        } finally { controller.pause().stop().destroy() }
    }
}
