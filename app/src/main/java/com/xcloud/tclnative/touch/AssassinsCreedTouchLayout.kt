package com.xcloud.tclnative.touch

import com.xcloud.tclnative.signaling.GameTitle
import com.xcloud.tclnative.webrtc.TouchKind
import java.util.Locale

/** Placement of the controls supplied by the game in our native overlay. */
internal enum class AssassinsCreedTouchLayout {
    ORIGINS, ODYSSEY;

    private data class Placement(val x: Float, val y: Float, val radius: Float)

    fun place(controls: List<HudElement>, width: Int, height: Int): List<HudElement> {
        if (width < height || height < 2 || controls.isEmpty()) return controls

        val movement = primaryStick(controls, TouchKind.LEFT_STICK, "left")
        val camera = primaryStick(controls, TouchKind.RIGHT_STICK, "right")
        val actions = if (this == ORIGINS) originsActions else sharedActions
        val placements = controls.map { control ->
            if (control.officialAppearance == null) null
            else when {
                control.id.startsWith("upper-right-") && this == ORIGINS -> {
                    val slot = control.id.substringAfterLast('-').toIntOrNull() ?: 0
                    Placement(.95f - slot * .066f, .11f, .036f)
                }
                control.id.startsWith("upper-") -> null
                control === movement -> if (this == ORIGINS) Placement(.221f, .505f, .177f) else Placement(.23f, .52f, .147f)
                control === camera -> if (this == ORIGINS) Placement(.705f, .615f, .080f) else Placement(.70f, .62f, .066f)
                control.kind == TouchKind.DPAD -> if (this == ORIGINS) Placement(.335f, .755f, .108f) else Placement(.34f, .77f, .084f)
                else -> actions[control.action?.keys]
            }
        }
        val adjusted = controls.mapIndexed { index, control ->
            val placement = placements[index]
            // Multiple controls can share one action. Keep those in their package slots.
            if (placement == null || placements.count { it == placement } != 1) control
            else control.copy(x=placement.x, y=placement.y, radius=placement.radius)
        }
        val combat = movement != null && controls.any { HudBinding.RB in it.action?.keys.orEmpty() }
        if (this != ORIGINS || !combat) return adjusted
        // Keep individual block and heavy attack available even if the game supplies only a combo.
        return adjusted + listOfNotNull(
            missingButton(adjusted, HudBinding.LB),
            missingButton(adjusted, HudBinding.RT)
        )
    }

    private fun primaryStick(controls: List<HudElement>, kind: TouchKind, side: String): HudElement? =
        controls.filter {
            it.officialAppearance != null && it.kind == kind && it.stickAxis == HudStickAxis.BOTH &&
                it.action?.keys !in standaloneTriggers
        }.maxWithOrNull(compareBy({ it.id.startsWith("$side-inner-") }, { it.radius }))

    private fun missingButton(controls: List<HudElement>, binding: HudBinding): HudElement? {
        val keys = setOf(binding)
        if (controls.any { it.action?.keys == keys }) return null
        val placement = originsActions.getValue(keys)
        return HudElement("origins-fallback-${binding.name}", binding.label, placement.x, placement.y, placement.radius,
            binding = binding, officialAppearance = fallbackAppearance)
    }

    companion object {
        private val standaloneTriggers = setOf(setOf(HudBinding.LB), setOf(HudBinding.RT))
        private val fallbackAppearance = OfficialTouchAppearance(
            OfficialTouchStyle(background = OfficialTouchBackground(color = 0x88364248.toInt())),
            OfficialTouchStyle(background = OfficialTouchBackground(color = 0xcc364248.toInt())),
            enabled = true
        )
        private val sharedActions = mapOf(
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
        private val originsActions = sharedActions + mapOf(
            setOf(HudBinding.LB) to Placement(.124f, .306f, .070f),
            setOf(HudBinding.LT) to Placement(.671f, .832f, .062f),
            setOf(HudBinding.L3) to Placement(.669f, .203f, .041f),
            setOf(HudBinding.R3) to Placement(.704f, .402f, .041f),
            setOf(HudBinding.RB) to Placement(.822f, .501f, .070f),
            setOf(HudBinding.RT) to Placement(.775f, .758f, .071f),
            setOf(HudBinding.RB, HudBinding.RT) to Placement(.771f, .259f, .057f),
            setOf(HudBinding.B) to Placement(.942f, .412f, .046f),
            setOf(HudBinding.A) to Placement(.942f, .624f, .046f),
            setOf(HudBinding.X) to Placement(.869f, .772f, .046f)
        )

        fun forGame(game: GameTitle): AssassinsCreedTouchLayout? {
            for (name in listOfNotNull(game.englishName, game.name, game.titleId)) {
                val key = name.uppercase(Locale.ROOT).replace(Regex("[^A-Z0-9]"), "")
                if (key.startsWith("ASSASSINSCREEDORIGINS")) return ORIGINS
                if (key.startsWith("ASSASSINSCREEDODYSSEY")) return ODYSSEY
            }
            return null
        }
    }
}
