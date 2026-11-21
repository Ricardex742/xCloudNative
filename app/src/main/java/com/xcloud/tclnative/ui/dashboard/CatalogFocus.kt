package com.xcloud.tclnative.ui.dashboard

import com.xcloud.tclnative.settings.ConsoleScene
/** Retains a target by game key while the catalog and RecyclerView finish updating. */
internal class CatalogFocus {
    private data class Pending(val key: String?, val fallbackPosition: Int)

    private var pending: Pending? = null
    private var layoutRequest = 0
    var targetKey: String? = null

    fun defer(key: String?, position: Int) {
        pending = Pending(key, position)
    }

    fun consume(positions: Map<String, Int>): Int? {
        val requested = pending ?: return null
        pending = null
        return positions[requested.key] ?: requested.fallbackPosition
    }

    fun beginLayout(): Int = ++layoutRequest

    fun isCurrent(request: Int): Boolean = request == layoutRequest

    fun cancelLayout() {
        ++layoutRequest
        targetKey = null
    }

    fun cancel() {
        cancelLayout()
        pending = null
    }
}

internal enum class CatalogDirection { LEFT, RIGHT, UP, DOWN }

internal sealed interface CatalogFocusDestination {
    data class Cover(val position: Int) : CatalogFocusDestination
    data object Category : CatalogFocusDestination
    data object Library : CatalogFocusDestination
    data object AllGames : CatalogFocusDestination
    data object Stay : CatalogFocusDestination
}

internal object CatalogFocusPolicy {
    fun carousel(scene: ConsoleScene, position: Int, step: Int, count: Int): CatalogFocusDestination {
        val next = position + step
        return when {
            next < 0 && scene == ConsoleScene.XBOX_360_NXE -> CatalogFocusDestination.Category
            next < 0 && scene == ConsoleScene.PS5 -> CatalogFocusDestination.Library
            next >= count && scene == ConsoleScene.PS5 -> CatalogFocusDestination.AllGames
            next in 0 until count -> CatalogFocusDestination.Cover(next)
            else -> CatalogFocusDestination.Stay
        }
    }

    fun grid(position: Int, direction: CatalogDirection, columns: Int, count: Int): CatalogFocusDestination {
        val next = when (direction) {
            CatalogDirection.LEFT -> if (position % columns == 0) return CatalogFocusDestination.Stay else position - 1
            CatalogDirection.RIGHT -> if (position % columns == columns - 1) return CatalogFocusDestination.Stay else position + 1
            CatalogDirection.UP -> if (position < columns) return CatalogFocusDestination.Category else position - columns
            CatalogDirection.DOWN -> if (position / columns < (count - 1) / columns)
                minOf(position + columns, count - 1) else return CatalogFocusDestination.Stay
        }
        return if (next in 0 until count) CatalogFocusDestination.Cover(next) else CatalogFocusDestination.Stay
    }
}
