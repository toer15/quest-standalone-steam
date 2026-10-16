package com.toer15.queststeam.display

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class XCloseRequestTest {
    private fun sample() = XCloseRequest.message(sequence = 0x1234, windowId = 0x00800034, wmProtocols = 0x0150, wmDeleteWindow = 0x0151)

    @Test fun itIsAnXEventOf32Bytes() {
        assertEquals(32, sample().size)
    }

    @Test fun itIsAClientMessageSentByAnotherClient() {
        val b = sample()
        // ClientMessage is event 33; the high bit says it came from another client's SendEvent (only those make client messages).
        assertEquals(33, b[0].toInt() and 0x7f)
        assertTrue(b[0].toInt() and 0x80 != 0)
        assertEquals(32, b[1].toInt()) // 32-bit data
    }

    @Test fun theWordsSitWhereTheProtocolPutsThem() {
        val b = ByteBuffer.wrap(sample()).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0x1234, b.getShort(2).toInt())
        assertEquals(0x00800034, b.getInt(4)) // the window
        assertEquals(0x0150, b.getInt(8)) // the message type: WM_PROTOCOLS
        assertEquals(0x0151, b.getInt(12)) // data word 0: WM_DELETE_WINDOW
    }

    @Test fun theRestIsZero() {
        val b = sample()
        // Word 1 is the timestamp (0 = CurrentTime); words 2 to 4 are unused.
        for (i in 16 until 32) assertEquals("byte $i", 0, b[i].toInt())
    }
}
