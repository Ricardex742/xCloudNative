package com.xcloud.tclnative

import com.xcloud.tclnative.ui.dashboard.CatalogDirection.*
import com.xcloud.tclnative.ui.dashboard.CatalogFocus
import com.xcloud.tclnative.ui.dashboard.CatalogFocusDestination.*
import com.xcloud.tclnative.ui.dashboard.CatalogFocusPolicy
import com.xcloud.tclnative.settings.ConsoleScene
import com.xcloud.tclnative.ui.dashboard.consoleReferenceScale
import org.junit.Assert.*
import org.junit.Test

class CatalogFocusTest {
    @Test fun focusFollowsTheGameKeyAcrossCatalogChangesAndCancelsPendingWorkOnExit() {
        val focus = CatalogFocus()
        focus.defer("game", 1)
        assertEquals(4, focus.consume(mapOf("game" to 4)))
        assertNull(focus.consume(emptyMap()))
        focus.defer("removed", 1)
        assertEquals(1, focus.consume(emptyMap()))

        val oldLayout = focus.beginLayout()
        focus.targetKey = "outgoing"
        focus.defer("outgoing", 2)
        focus.cancel()
        assertNull(focus.targetKey)
        assertNull(focus.consume(emptyMap()))
        assertFalse(focus.isCurrent(oldLayout))
        assertTrue(focus.isCurrent(focus.beginLayout()))
    }

    @Test fun carouselEdgesGoToTheRightConsoleControls() {
        assertEquals(Library, CatalogFocusPolicy.carousel(ConsoleScene.PS5, 0, -1, 3))
        assertEquals(AllGames, CatalogFocusPolicy.carousel(ConsoleScene.PS5, 2, 1, 3))
        assertEquals(Cover(1), CatalogFocusPolicy.carousel(ConsoleScene.PS5, 0, 1, 3))
        assertEquals(Category, CatalogFocusPolicy.carousel(ConsoleScene.XBOX_360_NXE, 0, -1, 3))
        assertEquals(Stay, CatalogFocusPolicy.carousel(ConsoleScene.PS4, 0, -1, 3))
        assertEquals(Stay, CatalogFocusPolicy.carousel(ConsoleScene.PS4, 2, 1, 3))
    }

    @Test fun seriesGridKeepsRowsIntactAndReturnsUpToCategories() {
        assertEquals(Category, CatalogFocusPolicy.grid(1, UP, 4, 10))
        assertEquals(Stay, CatalogFocusPolicy.grid(3, RIGHT, 4, 10))
        assertEquals(Stay, CatalogFocusPolicy.grid(4, LEFT, 4, 10))
        assertEquals(Cover(9), CatalogFocusPolicy.grid(5, DOWN, 4, 10))
        assertEquals(Cover(5), CatalogFocusPolicy.grid(9, UP, 4, 10))
        assertEquals(Stay, CatalogFocusPolicy.grid(6, DOWN, 4, 10))
    }

    @Test fun referenceCompositionFitsTheAvailableAreaOnTvAndWithPhoneInsets() {
        assertEquals(1.5f, consoleReferenceScale(1920, 1080), 0f)
        assertEquals(0.75f, consoleReferenceScale(1162, 540), 0f)
        assertEquals(0.5f, consoleReferenceScale(640, 410), 0f)
    }
}
