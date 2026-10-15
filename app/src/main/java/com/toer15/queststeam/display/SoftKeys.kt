package com.toer15.queststeam.display

import android.view.KeyCharacterMap
import com.winlator.xserver.XKeycode

/**
 * Text from the Quest's system keyboard, typed into the game display as key presses (for Steam's
 * sign-in, chat boxes, names in games…).
 */
object SoftKeys {
    private val keys by lazy { KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD) }

    fun type(text: String) = text.forEach(::typeChar)

    fun tap(key: XKeycode) {
        val server = BuiltInDisplay.server ?: return
        runCatching { server.injectKeyPress(key); server.injectKeyRelease(key) }
    }

    /** Removes [count] characters before the cursor. */
    fun backspace(count: Int) = repeat(count) { tap(XKeycode.KEY_BKSP) }

    private fun typeChar(c: Char) {
        val server = BuiltInDisplay.server ?: return
        when (c) {
            '\n' -> return tap(XKeycode.KEY_ENTER)
            '\t' -> return tap(XKeycode.KEY_TAB)
        }
        val events = runCatching { keys.getEvents(charArrayOf(c)) }.getOrNull()
        if (!events.isNullOrEmpty()) {
            events.forEach { e -> runCatching { server.keyboard.onKeyEvent(e) } }
        } else runCatching {
            // Not on a US keyboard (ø, é, …): the X server maps a spare key to the character (X keysym).
            val keysym = if (c.code in 0x20..0xFF) c.code else 0x01000000 or c.code
            server.injectKeyPress(XKeycode.KEY_PRTSCN, keysym)
            server.injectKeyRelease(XKeycode.KEY_PRTSCN)
        }
    }

    /**
     * What to send when a text field changes from [old] to [new]: how many characters to delete
     * (backspaces) and what to type, keeping the common start.
     */
    fun diff(old: String, new: String): Pair<Int, String> {
        var same = 0
        while (same < old.length && same < new.length && old[same] == new[same]) same++
        return (old.length - same) to new.substring(same)
    }
}
