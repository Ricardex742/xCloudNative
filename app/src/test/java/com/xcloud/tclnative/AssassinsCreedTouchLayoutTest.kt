package com.xcloud.tclnative

import com.xcloud.tclnative.signaling.GameTitle
import com.xcloud.tclnative.touch.AssassinsCreedTouchLayout
import com.xcloud.tclnative.touch.HudAction
import com.xcloud.tclnative.touch.HudBinding
import com.xcloud.tclnative.touch.HudElement
import com.xcloud.tclnative.touch.OfficialTouchAppearance
import com.xcloud.tclnative.touch.OfficialTouchStyle
import com.xcloud.tclnative.webrtc.GamepadState
import com.xcloud.tclnative.webrtc.TouchKind
import com.xcloud.tclnative.webrtc.VirtualGamepad
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.hypot

class AssassinsCreedTouchLayoutTest {
    @Test fun onlySupportedTitlesUseThePlacement() {
        assertEquals(AssassinsCreedTouchLayout.ORIGINS,
            AssassinsCreedTouchLayout.forGame(GameTitle("origins", "Assassin’s Creed® Origins")))
        assertEquals(AssassinsCreedTouchLayout.ODYSSEY, AssassinsCreedTouchLayout.forGame(GameTitle("odyssey", "Localized",
            englishName = "Assassin's Creed Odyssey - Gold Edition")))
        assertNull(AssassinsCreedTouchLayout.forGame(GameTitle("valhalla", "Assassin's Creed Valhalla")))
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
        val controls = AssassinsCreedTouchLayout.ODYSSEY.place(listOf(move, directional, combo, unknown), 1520, 720)
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

    @Test fun originsEnlargesThePrimarySticksAndKeepsIndependentBlockAndHeavyAttack() {
        val style = OfficialTouchStyle()
        val appearance = OfficialTouchAppearance(style, style, enabled = true)
        val main = HudElement("left-inner-0", "L", .205f, .67f, .085f, TouchKind.LEFT_STICK,
            officialAppearance = appearance)
        val extra = main.copy(id = "left-outer-6-0", x = .10f, y = .76f, radius = .032f)
        val camera = main.copy(id = "right-inner-0", kind = TouchKind.RIGHT_STICK, x = .79f)
        val cameraExtra = camera.copy(id = "right-outer-5-0", y = .90f, radius = .032f)
        val combo = HudElement("right-outer-4-0", "RB + RT", .79f, .86f, .05f,
            action = HudAction(HudBinding.RB, HudBinding.RT), officialAppearance = appearance)
        val packageControls = listOf(extra, cameraExtra, main, camera, combo)
        val layout = AssassinsCreedTouchLayout.ORIGINS
        val placed = layout.place(packageControls, 1280, 606)
        assertEquals(extra, placed[0])
        assertEquals(cameraExtra.x, placed[1].x, 0f)
        assertEquals(cameraExtra.y, placed[1].y, 0f)
        assertTrue(placed[1].officialAppearance!!.faceScale > 1f)
        assertTrue(placed[2].radius > .15f && placed[2].y < main.y)
        assertTrue(placed[3].y < camera.y)

        val gamepad = VirtualGamepad(1280, 606, official = placed)
        fun press(binding: HudBinding): GamepadState {
            val control = gamepad.controls.single { it.action?.keys == setOf(binding) }
            assertEquals(control, gamepad.down(1, control.x, control.y))
            val pressed = gamepad.state()
            gamepad.up(1)
            assertEquals(GamepadState(), gamepad.state())
            return pressed
        }
        val block = press(HudBinding.LB)
        assertEquals(HudBinding.LB.button, block.buttons)
        assertEquals(0f, block.rightTrigger, 0f)
        val heavyAttack = press(HudBinding.RT)
        assertEquals(0, heavyAttack.buttons)
        assertEquals(1f, heavyAttack.rightTrigger, 0f)

        val packageLb = combo.copy(id = "package-lb", action = HudAction(HudBinding.LB))
        assertEquals("package-lb", layout.place(packageControls + packageLb, 1280, 606)
            .single { it.action?.keys == setOf(HudBinding.LB) }.id)
        assertTrue(layout.place(emptyList(), 1280, 606).isEmpty())

        val compact = VirtualGamepad(960, 720, official = layout.place(packageControls, 960, 720))
        val compactMove = compact.controls.single { it.id == main.id }
        val compactBlock = compact.controls.single { it.action?.keys == setOf(HudBinding.LB) }
        assertTrue(hypot(compactMove.x - compactBlock.x, compactMove.y - compactBlock.y) >
            compactMove.radius + compactBlock.radius)
    }
}
