package com.toer15.queststeam.display

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The X protocol message a window manager sends when you press a window's close button: a ClientMessage of type
 * WM_PROTOCOLS whose first data word is WM_DELETE_WINDOW. A Wine program turns it into the Windows close command
 * (WM_CLOSE), so the game quits the way it does on a PC. Pure, so the byte layout is tested.
 */
object XCloseRequest {
    /** The message's size on the wire. */
    const val SIZE = 32

    /** X's ClientMessage event; always sent with the "sent by another client" bit (0x80), because only SendEvent makes one. */
    private const val CLIENT_MESSAGE = 33 or 0x80

    /**
     * [sequence]: the receiving client's last request number, [windowId]: the window to close, [wmProtocols] and
     * [wmDeleteWindow]: the atoms of "WM_PROTOCOLS" and "WM_DELETE_WINDOW". Little endian, like the Wine programs on the headset.
     */
    fun message(sequence: Short, windowId: Int, wmProtocols: Int, wmDeleteWindow: Int): ByteArray {
        val e = ByteBuffer.allocate(SIZE).order(ByteOrder.LITTLE_ENDIAN)
        e.put(0, CLIENT_MESSAGE.toByte())
        e.put(1, 32.toByte()) // the data is five 32-bit words
        e.putShort(2, sequence)
        e.putInt(4, windowId)
        e.putInt(8, wmProtocols) // the message type
        e.putInt(12, wmDeleteWindow) // data word 0: which protocol message this is; word 1 (the time) stays 0 = CurrentTime
        return e.array()
    }
}
