package com.xcloud.tclnative.touch

import com.xcloud.tclnative.signaling.GameTitle
import com.xcloud.tclnative.webrtc.TouchKind
import java.util.Locale

/** Movement fallback for the adventure overlay when the package has no left stick. */
internal object MinecraftDungeonsTouchLayout {
    private val movement = HudElement(
        "dungeons-movement", "L", .18f, .76f, .13f,
        TouchKind.LEFT_STICK, officialAppearance = OfficialTouchAppearance(
            OfficialTouchStyle(), OfficialTouchStyle(), enabled = true
        )
    )

    fun appliesTo(game: GameTitle): Boolean = listOfNotNull(game.englishName, game.name, game.titleId).any {
        it.uppercase(Locale.ROOT).replace(Regex("[^A-Z0-9]"), "").startsWith("MINECRAFTDUNGEONS")
    }

    fun place(controls: List<HudElement>, width: Int, height: Int): List<HudElement> {
        if (width < height || height < 2 || controls.any { it.kind == TouchKind.LEFT_STICK }) return controls
        val actions = controls.asSequence().filter { it.officialAppearance?.enabled == true }
            .flatMap { it.action?.keys.orEmpty().asSequence() }.toSet()
        if (HudBinding.A !in actions || actions.none { it == HudBinding.B || it == HudBinding.X || it == HudBinding.RT }) {
            return controls
        }
        return controls + movement
    }
}
