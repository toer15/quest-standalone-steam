package com.toer15.queststeam.gamepad

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GamepadTest {
    @Test fun buttonsMapToXInput() {
        val p = PadState()
        assertTrue(p.setKey(KeyEvent.KEYCODE_BUTTON_A, true))
        assertTrue(p.setKey(KeyEvent.KEYCODE_BUTTON_R1, true))
        assertEquals(PadState.A or PadState.RB, p.buttons)
        assertFalse("same state again is not a change", p.setKey(KeyEvent.KEYCODE_BUTTON_A, true))
        p.setKey(KeyEvent.KEYCODE_BUTTON_A, false)
        assertEquals(PadState.RB, p.buttons)
        p.setKey(KeyEvent.KEYCODE_BUTTON_R2, true)
        assertEquals(255, p.rightTrigger)
    }

    @Test fun sticksTriggersAndHat() {
        val p = PadState()
        // Android: stick up = -1 on Y; XInput: up = +32767.
        p.setAxes(x = 1f, y = -1f, z = -1f, rz = 1f, lt = 0.5f, rt = 0f, hatX = 0f, hatY = -1f)
        assertEquals(32767, p.lx.toInt())
        assertEquals(32767, p.ly.toInt())
        assertEquals(-32768, p.rx.toInt())
        assertEquals(-32768, p.ry.toInt())
        assertEquals(128, p.leftTrigger)
        assertEquals(PadState.UP, p.buttons)
        p.setKey(KeyEvent.KEYCODE_BUTTON_B, true)
        assertEquals(PadState.UP or PadState.B, p.buttons)
    }

    @Test fun exactControllerModels() {
        assertEquals("DualSense Wireless Controller (PS5)", Controllers.modelName(0x054c, 0x0ce6, "Wireless Controller"))
        assertEquals("DualShock 4 (PS4)", Controllers.modelName(0x054c, 0x09cc, "Wireless Controller"))
        assertEquals("Xbox Wireless Controller (Series X|S)", Controllers.modelName(0x045e, 0x0b13, "Xbox Wireless Controller"))
        assertEquals("Nintendo Switch Pro Controller", Controllers.modelName(0x057e, 0x2009, "Pro Controller"))
        assertEquals("8BitDo Ultimate", Controllers.modelName(0x2dc8, 0x3106, "8BitDo Ultimate"))
        assertEquals(Controllers.TYPE_PLAYSTATION, Controllers.typeOf(0x054c, "Wireless Controller"))
        assertEquals(Controllers.TYPE_XBOX, Controllers.typeOf(0, "Xbox Wireless Controller"))
    }
}
