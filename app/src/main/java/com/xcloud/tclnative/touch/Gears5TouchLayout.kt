package com.xcloud.tclnative.touch

import com.xcloud.tclnative.signaling.GameTitle
import com.xcloud.tclnative.webrtc.TouchKind

/** Place recognizable Gears 5 controls without changing the actions in the Xbox package. */
internal object Gears5TouchLayout {
    private val title = Regex("""^Gears\s*(?:of\s*War\s*)?5(?:\b|$)""", RegexOption.IGNORE_CASE)

    fun appliesTo(game: GameTitle) = listOfNotNull(game.englishName, game.name).any { title.containsMatchIn(it) }

    fun place(controls: List<HudElement>, width: Int, height: Int): List<HudElement> {
        if (width < height || height < 2 || controls.none { it.kind == TouchKind.LEFT_STICK } ||
            controls.none { it.officialAppearance != null && (it.binding == HudBinding.RT || it.binding == HudBinding.LT) }) {
            return controls
        }
        val counts = controls.groupingBy { it.binding }.eachCount()
        val movement = controls.firstOrNull { it.id.startsWith("left-inner") && it.kind == TouchKind.LEFT_STICK }
            ?: controls.first { it.kind == TouchKind.LEFT_STICK }
        val look = controls.firstOrNull { it.id.startsWith("right-inner") && it.dragLook && it.action == null }
        return controls.map { control ->
            when {
                control === movement -> control.copy(x = .215f, y = .50f)
                control === look -> control.copy(x = .82f, y = .50f, radius = maxOf(control.radius, .18f))
                control.binding == null || counts[control.binding] != 1 -> control
                else -> when (control.binding) {
                    HudBinding.LT -> control.copy(x = .91f, y = .30f)
                    HudBinding.L3 -> control.copy(x = .265f, y = .24f)
                    HudBinding.R3 -> control.copy(x = .77f, y = .24f)
                    HudBinding.Y -> control.copy(x = .70f, y = .40f)
                    HudBinding.B -> control.copy(x = .70f, y = .61f)
                    HudBinding.X -> control.copy(x = .95f, y = .61f)
                    HudBinding.A -> control.copy(x = .875f, y = .77f)
                    HudBinding.VIEW -> control.copy(x = .89f, y = .08f)
                    HudBinding.MENU -> control.copy(x = .955f, y = .08f)
                    else -> control
                }
            }
        }
    }
}
