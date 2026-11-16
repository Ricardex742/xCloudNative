package com.xcloud.tclnative

import com.xcloud.tclnative.catalog.*
import com.xcloud.tclnative.signaling.GameTitle
import org.junit.Assert.*
import org.junit.Test

class CatalogPresentationEngineTest {
    @Test fun immutableArtworkJoinsAreReusedWhileFiltersAndHistoryStayCurrent() {
        val games=(0 until 4000).map { GameTitle("G$it","Game %04d".format(it),"P"+it.toString().padStart(11,'0')) }
        val input=CatalogInput(games,emptyMap(),emptyMap(),null,emptySet(),LibraryTab.ALL,emptySet(),null,null,"")
        val engine=CatalogPresentationEngine();val first=engine.build(input)
        val favorite=engine.build(input.copy(tab=LibraryTab.FAVORITES,favorites=setOf("G12")))
        assertEquals(listOf("G12"),favorite.cards.map { it.game!!.titleId })
        assertSame(first.cards[12],favorite.cards.single())
        val search=engine.build(input.copy(query="Game 0012"))
        assertSame(favorite.cards.single(),search.cards.single())
        val owned=engine.build(input.copy(ownershipFilter=OwnershipFilter.OWNED,ownership=mapOf("P00000000012" to Ownership.OWNED)))
        assertSame(search.cards.single(),owned.cards.single())
        val updated=engine.build(input.copy(games=games.map { if(it.titleId=="G12") it.copy(name="New title") else it },query="New title"))
        assertEquals("New title",updated.cards.single().name);assertNotSame(search.cards.single(),updated.cards.single())
        val otherAccount=CatalogPresentationEngine().build(input.copy(games=listOf(GameTitle("PRIVATE","Other account"))))
        assertEquals(listOf("PRIVATE"),otherAccount.cards.map { it.game!!.titleId })
    }
}
