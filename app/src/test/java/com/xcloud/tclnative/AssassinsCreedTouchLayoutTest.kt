package com.xcloud.tclnative

import com.xcloud.tclnative.signaling.GameTitle
import com.xcloud.tclnative.touch.AssassinsCreedTouchLayout
import com.xcloud.tclnative.touch.HudAction
import com.xcloud.tclnative.touch.HudBinding
import com.xcloud.tclnative.touch.HudElement
import com.xcloud.tclnative.touch.OfficialTouchAppearance
import com.xcloud.tclnative.touch.OfficialTouchStyle
import com.xcloud.tclnative.webrtc.TouchKind
import com.xcloud.tclnative.webrtc.VirtualGamepad
import org.junit.Assert.*
import org.junit.Test

class AssassinsCreedTouchLayoutTest {
    @Test fun onlySupportedTitlesUseThePlacement() {
        assertTrue(AssassinsCreedTouchLayout.appliesTo(GameTitle("origins", "Assassin’s Creed® Origins")))
        assertTrue(AssassinsCreedTouchLayout.appliesTo(GameTitle("odyssey", "Localized",
            englishName = "Assassin's Creed Odyssey - Gold Edition")))
        assertFalse(AssassinsCreedTouchLayout.appliesTo(GameTitle("valhalla", "Assassin's Creed Valhalla")))
    }

    @Test fun movedControlsStillSendThePackageActionsAndLeaveUnknownSlotsInPlace() {
        val style = OfficialTouchStyle()
        val appearance = OfficialTouchAppearance(style, style, enabled = true)
        val move = HudElement("left-inner-0", "L", .205f, .67f, .085f, TouchKind.LEFT_STICK,
            officialAppearance = appearance)
        val directional = HudElement("left-outer-2-0", "+", .31f, .75f, .05f, TouchKind.DPAD,
            officialAppearance = appearance)
        val combo = HudElement("right-outer-4-0", "RB + RT", .79f, .86f, .05f,
            action = HudAction(HudBinding.RB, HudBinding.RT), officialAppearance = appearance)
        val unknown = combo.copy(id = "right-outer-5-0", action = HudAction(HudBinding.GUIDE))
        val controls = AssassinsCreedTouchLayout.place(listOf(move, directional, combo, unknown), 1520, 720)
        assertTrue(controls[0].y < move.y)
        assertTrue(controls[0].radius > move.radius)
        assertTrue(controls[1].x > controls[0].x && controls[1].y > controls[0].y)
        assertTrue(controls[2].y < combo.y)
        assertEquals(combo.action, controls[2].action)
        assertSame(appearance, controls[2].officialAppearance)
        assertEquals(unknown, controls[3])

        val gamepad = VirtualGamepad(1520, 720, official = controls)
        val attack = gamepad.controls[2]
        assertEquals(attack, gamepad.down(1, attack.x, attack.y))
        assertEquals(HudBinding.RB.button, gamepad.state().buttons)
        assertEquals(1f, gamepad.state().rightTrigger, 0f)
    }
}
