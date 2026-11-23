package com.xcloud.tclnative

import com.xcloud.tclnative.signaling.GameTitle
import com.xcloud.tclnative.touch.*
import com.xcloud.tclnative.webrtc.TouchKind
import com.xcloud.tclnative.webrtc.VirtualGamepad
import org.junit.Assert.*
import org.junit.Test

class MinecraftDungeonsTouchLayoutTest {
    @Test fun playableOverlayWithoutMovementGetsOneLeftStick() {
        assertTrue(MinecraftDungeonsTouchLayout.appliesTo(GameTitle("dungeons", "Minecraft Dungeons")))
        assertFalse(MinecraftDungeonsTouchLayout.appliesTo(GameTitle("legends", "Minecraft Legends")))
        assertFalse(MinecraftDungeonsTouchLayout.appliesTo(GameTitle("sequel", "Minecraft Dungeons II")))
        val style = OfficialTouchStyle()
        val appearance = OfficialTouchAppearance(style, style, enabled = true)
        val attack = HudElement("right-inner-0", "A", .8f, .64f, .07f,
            binding = HudBinding.A, officialAppearance = appearance)
        val artifact = attack.copy(id = "lower-rightCenter-0", x = .60f, y = .90f,
            binding = HudBinding.X, action = HudAction(HudBinding.X))
        val supplied = listOf(attack, artifact)
        val layout = MinecraftDungeonsTouchLayout
        val playable = layout.place(supplied, 1280, 606)
        assertEquals(supplied, playable.filterNot { it.kind == TouchKind.LEFT_STICK })
        assertEquals(playable, layout.place(playable, 1280, 606))
        assertEquals(listOf(attack), layout.place(listOf(attack), 1280, 606))
        assertTrue(layout.place(emptyList(), 1280, 606).isEmpty())
        val gamepad = VirtualGamepad(1280, 606, official = playable)
        val stick = gamepad.controls.single { it.kind == TouchKind.LEFT_STICK }
        assertEquals(.50f * 606, stick.y, .001f)
        assertNotNull(gamepad.down(1, stick.x, stick.y))
        gamepad.move(1, stick.x + stick.radius / 2, stick.y, now = 10)
        assertEquals(.5f, gamepad.state().leftX, .001f)
        assertEquals(0, gamepad.state().buttons)
        val sword = gamepad.controls.first()
        assertNotNull(gamepad.down(2, sword.x, sword.y))
        assertEquals(HudBinding.A.button, gamepad.state().buttons)
        gamepad.up(2)
        assertEquals(0, gamepad.state().buttons)
    }
}
