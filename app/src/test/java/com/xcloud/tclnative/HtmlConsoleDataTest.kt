package com.xcloud.tclnative

import com.xcloud.tclnative.catalog.*
import com.xcloud.tclnative.settings.PlaybackSettings
import com.xcloud.tclnative.signaling.GameTitle
import com.xcloud.tclnative.ui.dashboard.HtmlConsoleData
import com.xcloud.tclnative.ui.dashboard.HtmlConsoleDocument
import com.xcloud.tclnative.settings.UiTheme
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest

class HtmlConsoleDataTest {
    @Test fun rendererDocumentKeepsTheOriginalCssWithoutBootingEmbeddedDemoImages() {
        for(theme in listOf(UiTheme.PS4,UiTheme.PS5)) {
            val source=File("src/main/assets/${theme.htmlAsset}").readText()
            val adapter=File("src/main/assets/themes/native-adapter.js").readText()
            val document=HtmlConsoleDocument.prepare(source,adapter,theme)
            assertEquals(source.substringAfter("<style>").substringBefore("</style>"),document.substringAfter("<style>").substringBefore("</style>"))
            assertFalse(document.contains("data:image/"))
            assertTrue(document.contains("const games=[];"))
            assertTrue(document.length<source.length/2)
            assertTrue(document.contains(adapter))
            assertFalse(document.contains("render();scale();motion();"))
        }
    }
    @Test fun suppliedHtmlFilesRemainByteForByteIdentical() {
        val expected=mapOf("ps4" to "e24303ee03593f916ffe3cf8f18f6717dd0e7d94d82f00cd53f80708df19d649",
            "ps5" to "eb1ee6a2f5f32bdb35540f32656e4f7a9f8dffa0302227ecab9fa45edc656622")
        expected.forEach { (theme,hash) ->
            val actual=MessageDigest.getInstance("SHA-256").digest(File("src/main/assets/themes/simulacao-$theme.html").readBytes())
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            assertEquals(theme,hash,actual)
        }
    }
    @Test fun htmlGetsABoundedWindowActualQuotaAndNoAccountIdentifiers() {
        val cards=(0..1000).map { CatalogCard(game=GameTitle("$it","Game $it",tileUrl="https://store-images.s-microsoft.com/image")) }
        val profile=XboxProfile("<script>name</script>","Player",subscription="ULTIMATE",xuid="private-account-identifier")
        val value=HtmlConsoleData.snapshot(cards,cards[900].key,LibraryTab.ALL,profile,CloudPlaytime(),emptySet(),PlaybackSettings())
        val items=value.getJSONArray("games")
        assertEquals(5,items.length());assertEquals(cards[900].key,items.getJSONObject(value.getInt("selected")).getString("id"))
        assertTrue(value.getBoolean("hasNext"));assertTrue(value.getBoolean("hasPrevious"))
        assertFalse(value.getJSONObject("quota").getBoolean("unlimited"))
        assertEquals(profile.displayName,value.getJSONObject("profile").getString("name"))
        assertFalse(value.toString().contains("private-account-identifier"))
        fun window(index: Int)=HtmlConsoleData.snapshot(cards,cards[index].key,LibraryTab.ALL,profile,CloudPlaytime(),emptySet(),PlaybackSettings())
        assertEquals(window(2).getJSONArray("games").toString(),window(4).getJSONArray("games").toString())
        assertEquals(4,window(4).getInt("selected"));assertEquals(0,window(5).getInt("selected"))
    }
    @Test fun artworkRequiresHttpsAndLimitedTimeIsNotReplacedByUnlimited() {
        val game=GameTitle("game","Demo",tileUrl="http://example.test/tile.png",heroUrl="https://example.test/hero.png")
        val card=CatalogCard(game=game)
        val value=HtmlConsoleData.snapshot(listOf(card),card.key,LibraryTab.ALL,XboxProfile(),CloudPlaytime(CloudPlaytimeStatus.LIMITED,2520),emptySet(),PlaybackSettings())
        val item=value.getJSONArray("games").getJSONObject(0)
        assertEquals("",item.getString("image"));assertTrue(item.getString("hero").startsWith("https://"))
        assertFalse(value.getJSONObject("quota").getBoolean("unlimited"))
        assertEquals("42min",value.getJSONObject("quota").getString("value"))
    }
}
