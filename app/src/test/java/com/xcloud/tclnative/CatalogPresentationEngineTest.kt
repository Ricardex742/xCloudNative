package com.xcloud.tclnative

import com.xcloud.tclnative.catalog.*
import com.xcloud.tclnative.signaling.GameTitle
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CatalogPresentationEngineTest {
    @Test fun alphabeticalIndexFollowsTheFilteredStoreGenresAndGroupsAccentedTitles() {
        val stored = StoreMetadata.galleryProduct(JSONObject(
            """{"ProductId":"AAAAAAAAAAAA","ProductTitle":"Águia","Properties":{"Category":"Role playing","Categories":["Shooter","Other"]}}"""
        ))!!
        val gallery = OfficialGallery(OfficialGalleryKind.PURCHASED, listOf(stored))
        val games = listOf(GameTitle("z", "Zebra"), GameTitle("8", "8-bit"),
            GameTitle("b", "Batalha", genres = setOf(CatalogGenre.SHOOTER)))
        val input = CatalogInput(games, mapOf(gallery.kind to gallery), emptyMap(), null, emptySet(),
            LibraryTab.ALL, emptySet(), null, null, "")
        val engine = CatalogPresentationEngine()
        val all = engine.build(input)
        assertEquals(listOf("8-bit", "Águia", "Batalha", "Zebra"), all.cards.map { it.name })
        assertEquals(mapOf('#' to 0, 'A' to 1, 'B' to 2, 'Z' to 3), all.alphabetPositions)
        assertEquals(setOf(CatalogGenre.SHOOTER, CatalogGenre.ROLE_PLAYING), all.genres)
        val shooters = engine.build(input.copy(genre = CatalogGenre.SHOOTER))
        assertEquals(listOf("Águia", "Batalha"), shooters.cards.map { it.name })
        assertEquals(mapOf('A' to 0, 'B' to 1), shooters.alphabetPositions)
        assertTrue(engine.build(input.copy(tab = LibraryTab.PURCHASED, genre = CatalogGenre.RACING)).cards.isEmpty())
        assertEquals(mapOf('A' to 0), engine.build(input.copy(tab = LibraryTab.PURCHASED,
            genre = CatalogGenre.SHOOTER)).alphabetPositions)
    }

    @Test fun purchaseGallerySortsEachLicenseGroupByEnglishTitleAndMarksOnlyPurchases() {
        val products = listOf(
            GalleryProduct("AAAAAAAAAAAA", "Abelha", englishName = "Zebra"),
            GalleryProduct("BBBBBBBBBBBB", "Zíper", englishName = "Apple"),
            GalleryProduct("CCCCCCCCCCCC", "Abacaxi", englishName = "Yellow", genres = setOf(CatalogGenre.ROLE_PLAYING)),
            GalleryProduct("DDDDDDDDDDDD", "Voo", englishName = "Blue"),
            GalleryProduct("EEEEEEEEEEEE", "A confirmar")
        )
        val gallery = OfficialGallery(OfficialGalleryKind.PURCHASED, products)
        val rights = mapOf("AAAAAAAAAAAA" to Ownership.OWNED, "BBBBBBBBBBBB" to Ownership.OWNED,
            "CCCCCCCCCCCC" to Ownership.NOT_OWNED, "DDDDDDDDDDDD" to Ownership.NOT_OWNED)
        val input = CatalogInput(emptyList(), mapOf(gallery.kind to gallery), emptyMap(), null, emptySet(),
            LibraryTab.PURCHASED, emptySet(), null, null, "", ownership = rights)
        val result = CatalogPresentation.build(input)
        assertEquals(listOf("Zíper", "Abelha", "Voo", "Abacaxi", "A confirmar"), result.cards.map { it.name })
        assertEquals(listOf(true, true, false, false, false), result.cards.map { it.purchased })
        assertEquals(listOf('A' to 0, 'B' to 2, 'Y' to 3, 'Z' to 1), result.alphabetPositions.toList())
        val notOwned = CatalogPresentation.build(input.copy(ownershipFilter = OwnershipFilter.NOT_OWNED))
        assertEquals(listOf("Voo", "Abacaxi"), notOwned.cards.map { it.name })
        assertEquals(mapOf('B' to 0, 'Y' to 1), notOwned.alphabetPositions)
        val rpg = CatalogPresentation.build(input.copy(genre = CatalogGenre.ROLE_PLAYING))
        assertEquals(listOf("Abacaxi"), rpg.cards.map { it.name })
        assertEquals(mapOf('Y' to 0), rpg.alphabetPositions)
    }

    @Test fun gamePassUsesOnlyItsOwnTitlesAndAccentedInitials() {
        val a = GameTitle("a", "Águia", "AAAAAAAAAAAA", genres = setOf(CatalogGenre.SHOOTER))
        val b = GameTitle("b", "Barco", "BBBBBBBBBBBB", genres = setOf(CatalogGenre.SIMULATION))
        val z = GameTitle("z", "Zelda", "ZZZZZZZZZZZZ", genres = setOf(CatalogGenre.ROLE_PLAYING))
        val input = CatalogInput(listOf(z, b, a), emptyMap(), emptyMap(), setOf(a.productId!!, b.productId!!),
            emptySet(), LibraryTab.GAME_PASS, emptySet(), null, null, "")
        val result = CatalogPresentation.build(input)
        assertEquals(listOf("Águia", "Barco"), result.cards.map { it.name })
        assertEquals(setOf(CatalogGenre.SHOOTER, CatalogGenre.SIMULATION), result.genres)
        assertEquals(mapOf('A' to 0, 'B' to 1), result.alphabetPositions)
        val gallery = OfficialGallery(OfficialGalleryKind.GAME_PASS,
            listOf(GalleryProduct(requireNotNull(a.productId)), GalleryProduct(requireNotNull(b.productId))))
        val filtered = CatalogPresentation.build(input.copy(galleries = mapOf(gallery.kind to gallery), genre = CatalogGenre.SIMULATION))
        assertEquals(listOf("Barco"), filtered.cards.map { it.name })
        assertEquals(mapOf('B' to 0), filtered.alphabetPositions)
    }

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
