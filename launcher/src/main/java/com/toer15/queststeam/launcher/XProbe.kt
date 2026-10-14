package com.toer15.queststeam.launcher

import java.io.DataInputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Tiny X11 client (just enough of the protocol) used to see when a started game has opened its
 * window: counts the top-level windows on the display that are mapped (visible) and at least
 * [minSize] pixels on each side. The loading screen switches to the game when that number goes up.
 * The display server runs with -ac (no access control), so no authentication is sent.
 */
object XProbe {
    data class Window(val id: Int, val width: Int, val height: Int)

    /** Visible top-level windows on 127.0.0.1:6000+[display]; null if the X server can't be reached. */
    fun visibleWindows(display: Int = 0, minSize: Int = 64, host: String = "127.0.0.1"): List<Window>? = runCatching {
        Socket().use { s ->
            s.connect(InetSocketAddress(host, 6000 + display), 1000)
            s.soTimeout = 2000
            val out = s.getOutputStream()
            val inp = DataInputStream(s.getInputStream())
            val root = handshake(out, inp)
            var seq = 0
            fun request(opcode: Int, window: Int): ByteArray {
                val b = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
                b.put(opcode.toByte()); b.put(0); b.putShort(2); b.putInt(window)
                out.write(b.array()); out.flush()
                seq++
                return reply(inp)
            }
            val tree = request(15, root) // QueryTree
            if (tree[0].toInt() != 1) return@use emptyList<Window>()
            val tb = ByteBuffer.wrap(tree).order(ByteOrder.LITTLE_ENDIAN)
            val n = tb.getShort(16).toInt() and 0xffff
            val children = (0 until n).map { tb.getInt(32 + 4 * it) }
            children.mapNotNull { w ->
                val attrs = request(3, w) // GetWindowAttributes
                if (attrs[0].toInt() != 1) return@mapNotNull null
                val mapState = attrs[26].toInt()
                val overrideRedirect = attrs[27].toInt() != 0
                if (mapState != 2 || overrideRedirect) return@mapNotNull null // 2 = Viewable
                val geo = request(14, w) // GetGeometry
                if (geo[0].toInt() != 1) return@mapNotNull null
                val gb = ByteBuffer.wrap(geo).order(ByteOrder.LITTLE_ENDIAN)
                val width = gb.getShort(16).toInt() and 0xffff
                val height = gb.getShort(18).toInt() and 0xffff
                if (width < minSize || height < minSize) null else Window(w, width, height)
            }
        }
    }.getOrNull()

    /** Connection setup; returns the first screen's root window. */
    private fun handshake(out: OutputStream, inp: DataInputStream): Int {
        val req = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
        req.put('l'.code.toByte()); req.put(0); req.putShort(11); req.putShort(0); req.putShort(0); req.putShort(0); req.putShort(0)
        out.write(req.array()); out.flush()
        val head = ByteArray(8).also { inp.readFully(it) }
        val hb = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN)
        val extra = (hb.getShort(6).toInt() and 0xffff) * 4
        val body = ByteArray(extra).also { inp.readFully(it) }
        require(head[0].toInt() == 1) { "X server refused the connection" }
        val b = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN)
        val vendorLen = b.getShort(16).toInt() and 0xffff
        val formats = body[21].toInt() and 0xff
        val screenOffset = 32 + pad4(vendorLen) + 8 * formats
        return b.getInt(screenOffset)
    }

    /** Reads one reply (32 bytes + extra length), skipping events. */
    private fun reply(inp: DataInputStream): ByteArray {
        while (true) {
            val head = ByteArray(32).also { inp.readFully(it) }
            when (head[0].toInt()) {
                1 -> {
                    val extra = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN).getInt(4) * 4
                    if (extra == 0) return head
                    return head + ByteArray(extra).also { inp.readFully(it) }
                }
                0 -> return head // error (e.g. the window just went away)
                else -> continue // event
            }
        }
    }

    private fun pad4(n: Int) = (n + 3) and 3.inv()
}
