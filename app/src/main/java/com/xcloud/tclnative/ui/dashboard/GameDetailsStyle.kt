package com.xcloud.tclnative.ui.dashboard

import android.graphics.Color
import androidx.core.graphics.ColorUtils
import com.xcloud.tclnative.settings.ConsoleScene
import com.xcloud.tclnative.settings.UiTheme

/** Keeps the same information layout while using the selected theme's contrast and accent. */
internal class GameDetailsStyle(palette: UiPalette) {
    private val original = palette.theme == UiTheme.GREEN
    private val top = palette[UiColor.PANEL_TOP]
    private val bottom = palette[UiColor.PANEL_BOTTOM]
    val accent = if (original) 0xff79dfb6.toInt() else palette[UiColor.ACCENT]
    val panel = if (original) 0xe21a2334.toInt() else ColorUtils.setAlphaComponent(top, 232)
    val bodyRow = if (original) 0xff182333.toInt() else ColorUtils.blendARGB(top, bottom, .55f)
    val selected = if (original) 0xff25384a.toInt() else ColorUtils.blendARGB(top, accent, .19f)
    val focused = if (original) 0xff28423f.toInt() else ColorUtils.blendARGB(top, accent, .25f)
    val rating = if (original) 0xff263443.toInt() else ColorUtils.blendARGB(top, accent, .13f)
    val border = if (original) 0xff64768c.toInt() else ColorUtils.blendARGB(top, accent, .39f)
    val muted = if (original) 0xffd8dfe6.toInt() else ColorUtils.blendARGB(Color.WHITE, accent, .23f)
    val shade = if (original) intArrayOf(0x86101723.toInt(), 0x94141a2a.toInt(), 0xe80e1520.toInt())
        else intArrayOf(ColorUtils.setAlphaComponent(bottom, 130), ColorUtils.setAlphaComponent(top, 159),
            ColorUtils.setAlphaComponent(bottom, 236))
    val backdrop = if (original) intArrayOf(palette[UiColor.BACKGROUND_END], 0xff111827.toInt(), 0xff141622.toInt())
        else intArrayOf(palette[UiColor.BACKGROUND_END], top, bottom)
    val radius = when (palette.theme.scene) {
        ConsoleScene.XBOX_ONE, ConsoleScene.XBOX_360_METRO -> 2
        ConsoleScene.PS4, ConsoleScene.XBOX_360_NXE -> 4
        ConsoleScene.PS5, ConsoleScene.XBOX_ORIGINAL, ConsoleScene.XBOX_360 -> 8
        else -> 5
    }

    fun action(primary: Boolean, focused: Boolean, selected: Boolean = false): Int {
        if (original) return when {
            focused -> if (primary) 0xff276552.toInt() else 0xff344460.toInt()
            selected -> 0xff284f4b.toInt()
            primary -> 0xff1c4b3e.toInt()
            else -> 0xff253047.toInt()
        }
        val intensity = when {
            primary && focused -> .35f
            primary -> .28f
            focused || selected -> .23f
            else -> .10f
        }
        val background = ColorUtils.blendARGB(top, accent, intensity)
        return if (ColorUtils.calculateContrast(Color.WHITE, background) >= 4.5) background
            else ColorUtils.blendARGB(background, Color.BLACK, .15f)
    }
}
