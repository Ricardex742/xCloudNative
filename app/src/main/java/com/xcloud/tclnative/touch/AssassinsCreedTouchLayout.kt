package com.xcloud.tclnative.touch

import com.xcloud.tclnative.signaling.GameTitle
import com.xcloud.tclnative.webrtc.TouchKind
import java.util.Locale

/** Placement of the controls supplied by the game in our native overlay. */
internal object AssassinsCreedTouchLayout {
    private data class Placement(val x: Float, val y: Float, val radius: Float)

    private val actions = mapOf(
        setOf(HudBinding.LB) to Placement(.12f, .31f, .067f),
        setOf(HudBinding.LT) to Placement(.68f, .84f, .058f),
        setOf(HudBinding.L3) to Placement(.67f, .22f, .039f),
        setOf(HudBinding.R3) to Placement(.72f, .40f, .039f),
        setOf(HudBinding.RB) to Placement(.81f, .51f, .064f),
        setOf(HudBinding.RT) to Placement(.77f, .77f, .065f),
        setOf(HudBinding.RB, HudBinding.RT) to Placement(.77f, .27f, .055f),
        setOf(HudBinding.Y) to Placement(.87f, .28f, .051f),
        setOf(HudBinding.B) to Placement(.94f, .43f, .046f),
        setOf(HudBinding.A) to Placement(.94f, .65f, .046f),
        setOf(HudBinding.X) to Placement(.87f, .80f, .046f),
        setOf(HudBinding.DOWN) to Placement(.96f, .85f, .040f)
    )
    private val movement = Placement(.23f, .52f, .147f)
    private val directionalPad = Placement(.34f, .77f, .084f)
    private val camera = Placement(.70f, .62f, .066f)

    fun appliesTo(game: GameTitle): Boolean =
        listOfNotNull(game.englishName, game.name, game.titleId).any { name ->
            val key = name.uppercase(Locale.ROOT).replace(Regex("[^A-Z0-9]"), "")
            key.startsWith("ASSASSINSCREEDORIGINS") || key.startsWith("ASSASSINSCREEDODYSSEY")
        }

    fun place(controls: List<HudElement>, width: Int, height: Int): List<HudElement> {
        if (width < height || height < 2) return controls
        val placements = controls.map { control ->
            if (control.officialAppearance == null || control.id.startsWith("upper-")) null
            else when (control.kind) {
                TouchKind.LEFT_STICK -> movement.takeIf { control.stickAxis == HudStickAxis.BOTH }
                TouchKind.RIGHT_STICK -> camera.takeIf { control.stickAxis == HudStickAxis.BOTH }
                TouchKind.DPAD -> directionalPad
                else -> actions[control.action?.keys]
            }
        }
        return controls.mapIndexed { index, control ->
            val placement = placements[index]
            // Multiple controls can share one action. Keep those in their package slots.
            if (placement == null || placements.count { it == placement } != 1) control
            else control.copy(x=placement.x, y=placement.y, radius=placement.radius)
        }
    }
}
