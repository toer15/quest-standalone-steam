package com.toer15.queststeam.gamepad

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Gives Windows games the player's Bluetooth controller. Android input (KeyEvent/MotionEvent)
 * is turned into XInput state in <rootfs>/tmp/gamepad.shm, which our xinput1_x.dll
 * (openvr-bridge/win/xinput.cpp) reads inside Wine. Rumble goes the other way.
 * Layout: openvr-bridge/include/qss_pad_shm.h.
 */
object GamepadBridge {
    private const val MAGIC = 0x50565351
    private const val VERSION = 1
    const val MAX = 4
    const val SIZE = 4096L
    private const val HEADER = 16
    private const val PAD = 32

    /** XInput names games load; the same DLL serves all of them. */
    val DLL_NAMES = listOf("xinput1_4", "xinput1_3", "xinput1_2", "xinput1_1", "xinput9_1_0")

    fun shmFile(ctx: Context) = File(ctx.filesDir, "rootfs/tmp/gamepad.shm")

    private var buf: MappedByteBuffer? = null
    private val slots = IntArray(MAX) { -1 }   // Android device id per XInput slot
    private val pads = Array(MAX) { PadState() }
    private val lastRumbleSeq = IntArray(MAX)
    private val rumbleOn = BooleanArray(MAX)
    @Volatile private var rumbleThread: Thread? = null

    /** True while a game can receive the controller (shared memory is open). */
    val isOpen get() = buf != null

    /**
     * Creates/maps the shared memory before a game starts and copies our XInput DLLs into the
     * Wine prefix. Returns the file to pass as QSS_PAD_SHM.
     */
    @Synchronized
    fun prepare(ctx: Context, rootfs: File, log: (String) -> Unit = {}): File {
        installDlls(ctx, rootfs, log)
        val f = shmFile(ctx).apply { parentFile?.mkdirs() }
        if (buf == null) {
            RandomAccessFile(f, "rw").use { raf ->
                raf.setLength(SIZE)
                buf = raf.channel.map(FileChannel.MapMode.READ_WRITE, 0, SIZE).apply { order(ByteOrder.LITTLE_ENDIAN) }
            }
            val b = buf!!
            for (i in 0 until SIZE.toInt()) b.put(i, 0)
            b.putInt(4, VERSION); b.putInt(8, MAX)
            b.putInt(0, MAGIC)
            for (i in 0 until MAX) { slots[i] = -1; pads[i].reset() }
        }
        onControllersChanged(Controllers.connected.value)
        startRumble()
        return f
    }

    /** Copies the 64-bit DLL into system32 and the 32-bit one into syswow64 under every XInput name. */
    fun installDlls(ctx: Context, rootfs: File, log: (String) -> Unit) {
        val windows = File(rootfs, "home/xuser/.wine/drive_c/windows")
        for ((asset, dir) in listOf("gamepad/xinput64.dll" to "system32", "gamepad/xinput32.dll" to "syswow64")) {
            val bytes = runCatching { ctx.assets.open(asset).use { it.readBytes() } }.getOrNull()
            if (bytes == null) { log("Controller bridge DLL missing from this build ($asset)."); continue }
            for (name in DLL_NAMES) {
                val out = File(windows, "$dir/$name.dll")
                val path = out.toPath()
                // Wine's builtins are symlinks into Wine itself: remove the link, never write through it.
                if (Files.isSymbolicLink(path)) Files.delete(path)
                else if (out.isFile && out.length() == bytes.size.toLong() && out.readBytes().contentEquals(bytes)) continue
                out.parentFile?.mkdirs()
                out.writeBytes(bytes)
            }
        }
    }

    /** Assigns XInput slots to connected controllers (in connection order) and frees removed ones. */
    @Synchronized
    fun onControllersChanged(list: List<Controller>) {
        val ids = list.map { it.deviceId }.toSet()
        for (i in 0 until MAX) if (slots[i] != -1 && slots[i] !in ids) { slots[i] = -1; pads[i].reset(); write(i) }
        for (c in list) slotFor(c.deviceId, c.type)
    }

    private fun slotFor(deviceId: Int, type: Int = Controllers.byDevice(deviceId)?.type ?: Controllers.TYPE_UNKNOWN): Int {
        slots.indexOf(deviceId).takeIf { it >= 0 }?.let { return it }
        val free = slots.indexOf(-1).takeIf { it >= 0 } ?: return -1
        slots[free] = deviceId
        pads[free].reset(); pads[free].type = type
        write(free)
        return free
    }

    /** Feeds a key event; true if it was a controller button (consumed for the game). */
    @Synchronized
    fun onKey(e: KeyEvent): Boolean {
        if (!isGamepadEvent(e.source) || buf == null) return false
        if (PadState.buttonBit(e.keyCode) == 0 && !PadState.isTriggerKey(e.keyCode)) return false
        if (e.action != KeyEvent.ACTION_DOWN && e.action != KeyEvent.ACTION_UP) return true
        val i = slotFor(e.deviceId).takeIf { it >= 0 } ?: return false
        if (pads[i].setKey(e.keyCode, e.action == KeyEvent.ACTION_DOWN)) write(i)
        return true
    }

    /** Feeds stick/trigger/hat motion; true if it came from a controller. */
    @Synchronized
    fun onMotion(e: MotionEvent): Boolean {
        if ((e.source and InputDevice.SOURCE_JOYSTICK) != InputDevice.SOURCE_JOYSTICK || buf == null) return false
        if (e.action != MotionEvent.ACTION_MOVE) return true
        val i = slotFor(e.deviceId).takeIf { it >= 0 } ?: return false
        val changed = pads[i].setAxes(
            e.getAxisValue(MotionEvent.AXIS_X), e.getAxisValue(MotionEvent.AXIS_Y),
            e.getAxisValue(MotionEvent.AXIS_Z), e.getAxisValue(MotionEvent.AXIS_RZ),
            maxOf(e.getAxisValue(MotionEvent.AXIS_LTRIGGER), e.getAxisValue(MotionEvent.AXIS_BRAKE)),
            maxOf(e.getAxisValue(MotionEvent.AXIS_RTRIGGER), e.getAxisValue(MotionEvent.AXIS_GAS)),
            e.getAxisValue(MotionEvent.AXIS_HAT_X), e.getAxisValue(MotionEvent.AXIS_HAT_Y),
        )
        if (changed) write(i)
        return true
    }

    private fun isGamepadEvent(source: Int) =
        (source and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
            (source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK ||
            (source and InputDevice.SOURCE_DPAD) == InputDevice.SOURCE_DPAD

    private fun write(i: Int) {
        val b = buf ?: return
        val p = pads[i]
        val o = HEADER + i * PAD
        p.packet++
        b.putShort(o + 8, p.buttons.toShort())
        b.put(o + 10, p.leftTrigger.toByte()); b.put(o + 11, p.rightTrigger.toByte())
        b.putShort(o + 12, p.lx); b.putShort(o + 14, p.ly); b.putShort(o + 16, p.rx); b.putShort(o + 18, p.ry)
        b.putInt(o + 28, p.type)
        b.putInt(o + 4, p.packet)
        b.putInt(o, if (slots[i] != -1) 1 else 0)
    }

    /** Polls rumble requests from the game and vibrates the matching controller. */
    private fun startRumble() {
        if (rumbleThread != null) return
        rumbleThread = Thread({
            var tick = 0
            while (true) {
                runCatching { pollRumble(tick++) }
                try { Thread.sleep(40) } catch (_: InterruptedException) { break }
            }
        }, "qss-rumble").apply { isDaemon = true; start() }
    }

    private fun pollRumble(tick: Int) {
        val b = buf ?: return
        for (i in 0 until MAX) {
            val id = synchronized(this) { slots[i] }
            if (id == -1) continue
            val o = HEADER + i * PAD
            val seq = b.getInt(o + 24)
            val low = b.getShort(o + 20).toInt() and 0xffff
            val high = b.getShort(o + 22).toInt() and 0xffff
            val strength = maxOf(low, high)
            // Re-issue a long pulse every ~0.8 s while the game keeps rumbling; stop when it sets 0.
            if (seq == lastRumbleSeq[i] && !(rumbleOn[i] && tick % 20 == 0)) continue
            lastRumbleSeq[i] = seq
            val vib = InputDevice.getDevice(id)?.vibrator ?: continue
            if (!vib.hasVibrator()) continue
            if (strength == 0) { if (rumbleOn[i]) vib.cancel(); rumbleOn[i] = false; continue }
            rumbleOn[i] = true
            val amp = (strength / 257).coerceIn(1, 255)
            if (Build.VERSION.SDK_INT >= 26 && vib.hasAmplitudeControl()) vib.vibrate(VibrationEffect.createOneShot(1000, amp))
            else @Suppress("DEPRECATION") vib.vibrate(1000)
        }
    }
}

/** One controller's XInput state, from Android key codes and axes. */
class PadState {
    var buttons = 0
    var leftTrigger = 0
    var rightTrigger = 0
    var lx: Short = 0; var ly: Short = 0; var rx: Short = 0; var ry: Short = 0
    var packet = 0
    var type = 0
    private var hatBits = 0
    private var keyBits = 0
    private var digitalL2 = false
    private var digitalR2 = false
    private var axisLT = 0
    private var axisRT = 0

    fun reset() { buttons = 0; leftTrigger = 0; rightTrigger = 0; lx = 0; ly = 0; rx = 0; ry = 0; hatBits = 0; keyBits = 0; digitalL2 = false; digitalR2 = false; axisLT = 0; axisRT = 0 }

    /** Returns true if the state changed. */
    fun setKey(keyCode: Int, down: Boolean): Boolean {
        val before = snapshot()
        when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_L2 -> digitalL2 = down
            KeyEvent.KEYCODE_BUTTON_R2 -> digitalR2 = down
            else -> {
                val bit = buttonBit(keyCode)
                keyBits = if (down) keyBits or bit else keyBits and bit.inv()
            }
        }
        update()
        return snapshot() != before
    }

    fun setAxes(x: Float, y: Float, z: Float, rz: Float, lt: Float, rt: Float, hatX: Float, hatY: Float): Boolean {
        val before = snapshot()
        lx = stick(x); ly = stick(-y); rx = stick(z); ry = stick(-rz)
        axisLT = trigger(lt); axisRT = trigger(rt)
        hatBits = (if (hatY < -0.5f) UP else 0) or (if (hatY > 0.5f) DOWN else 0) or
            (if (hatX < -0.5f) LEFT else 0) or (if (hatX > 0.5f) RIGHT else 0)
        update()
        return snapshot() != before
    }

    private fun update() {
        buttons = keyBits or hatBits
        leftTrigger = if (digitalL2) 255 else axisLT
        rightTrigger = if (digitalR2) 255 else axisRT
    }

    private fun snapshot() = listOf(buttons, leftTrigger, rightTrigger, lx, ly, rx, ry)

    companion object {
        const val UP = 0x0001; const val DOWN = 0x0002; const val LEFT = 0x0004; const val RIGHT = 0x0008
        const val START = 0x0010; const val BACK = 0x0020; const val LTHUMB = 0x0040; const val RTHUMB = 0x0080
        const val LB = 0x0100; const val RB = 0x0200; const val GUIDE = 0x0400
        const val A = 0x1000; const val B = 0x2000; const val X = 0x4000; const val Y = 0x8000

        fun buttonBit(keyCode: Int): Int = when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_A -> A
            KeyEvent.KEYCODE_BUTTON_B -> B
            KeyEvent.KEYCODE_BUTTON_X -> X
            KeyEvent.KEYCODE_BUTTON_Y -> Y
            KeyEvent.KEYCODE_BUTTON_L1 -> LB
            KeyEvent.KEYCODE_BUTTON_R1 -> RB
            KeyEvent.KEYCODE_BUTTON_THUMBL -> LTHUMB
            KeyEvent.KEYCODE_BUTTON_THUMBR -> RTHUMB
            KeyEvent.KEYCODE_BUTTON_START -> START
            KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_BACK -> BACK
            KeyEvent.KEYCODE_BUTTON_MODE -> GUIDE
            KeyEvent.KEYCODE_DPAD_UP -> UP
            KeyEvent.KEYCODE_DPAD_DOWN -> DOWN
            KeyEvent.KEYCODE_DPAD_LEFT -> LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> RIGHT
            else -> 0
        }

        fun isTriggerKey(keyCode: Int) = keyCode == KeyEvent.KEYCODE_BUTTON_L2 || keyCode == KeyEvent.KEYCODE_BUTTON_R2

        fun stick(v: Float): Short = (v.coerceIn(-1f, 1f) * if (v < 0) 32768f else 32767f).roundToInt().coerceIn(-32768, 32767).toShort()

        fun trigger(v: Float): Int = if (abs(v) < 0.02f) 0 else (v.coerceIn(0f, 1f) * 255f).roundToInt()
    }
}
