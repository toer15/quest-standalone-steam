package com.toer15.queststeam

import android.content.Context
import android.content.Intent
import com.toer15.queststeam.launcher.DeviceMode
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * SteamVR games on the Quest: swaps the game's openvr_api.dll for the bridge
 * (openvr-bridge/win, shipped in the Quest APK's assets), creates the shared memory both sides
 * map, and opens the immersive OpenXR view (native activity, app/src/quest/cpp).
 */
object VrBridge {
    private const val ASSET = "vrbridge/openvr_api.dll"
    private const val BACKUP_SUFFIX = ".qss-orig"

    // Must match openvr-bridge/include/qss_vr_shm.h.
    private const val MAGIC = 0x52565351
    private const val VERSION = 1
    const val SHM_SIZE = 67_112_960L
    const val REC_WIDTH = 1216
    const val REC_HEIGHT = 1280

    fun shmFile(ctx: Context) = File(ctx.filesDir, "vrbridge.shm")

    /** Quest build with the bridge DLL packaged. */
    fun isAvailable(ctx: Context): Boolean =
        BuildConfig.QUEST_BUILD && runCatching { ctx.assets.open(ASSET).close(); true }.getOrDefault(false)

    /** A SteamVR game ships openvr_api.dll (often in bin/win64). */
    fun isVrGame(gameDir: File): Boolean =
        gameDir.walkTopDown().any { it.isFile && it.name.equals("openvr_api.dll", true) }

    /** Replaces every 64-bit openvr_api.dll in the game with the bridge; originals are kept as *.qss-orig. */
    fun install(ctx: Context, gameDir: File, log: (String) -> Unit): Int {
        val bridge = ctx.assets.open(ASSET).use { it.readBytes() }
        var n = 0
        gameDir.walkTopDown().filter { it.isFile && it.name.equals("openvr_api.dll", true) }.forEach { dll ->
            val backup = File(dll.path + BACKUP_SUFFIX)
            val original = if (backup.exists()) backup else dll
            if (!isPe64(original)) { log("Skipping 32-bit ${dll.relativeTo(gameDir)} (64-bit games only for now)"); return@forEach }
            if (!backup.exists()) dll.copyTo(backup)
            dll.writeBytes(bridge)
            n++
            log("VR bridge installed: ${dll.relativeTo(gameDir)}")
        }
        return n
    }

    /** Puts the game's original openvr_api.dll files back. */
    fun uninstall(gameDir: File) {
        gameDir.walkTopDown().filter { it.isFile && it.name.endsWith(BACKUP_SUFFIX) }.forEach { backup ->
            backup.copyTo(File(backup.path.removeSuffix(BACKUP_SUFFIX)), overwrite = true)
            backup.delete()
        }
    }

    /** Creates the shared memory file with a valid header so the game can connect before the VR view starts. */
    fun prepareShm(ctx: Context): File {
        val f = shmFile(ctx)
        RandomAccessFile(f, "rw").use { raf ->
            raf.setLength(SHM_SIZE)
            val header = ByteBuffer.allocate(4096).order(ByteOrder.LITTLE_ENDIAN)
            header.putInt(0, MAGIC)
            header.putInt(4, VERSION)
            header.putInt(8, SHM_SIZE.toInt())
            header.putInt(12, REC_WIDTH)
            header.putInt(16, REC_HEIGHT)
            raf.seek(0)
            raf.write(header.array())
        }
        return f
    }

    /** Opens the immersive headset view (android.app.NativeActivity running libqssxr.so). */
    fun enterVr(ctx: Context) {
        val i = Intent().setClassName(ctx.packageName, "android.app.NativeActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
    }

    fun canPlayInVr(ctx: Context) = DeviceMode.isQuest && isAvailable(ctx)

    private fun isPe64(f: File): Boolean = runCatching {
        RandomAccessFile(f, "r").use { r ->
            r.seek(0x3c)
            val peOff = Integer.reverseBytes(r.readInt()).toLong()
            r.seek(peOff + 4)
            val machine = java.lang.Short.reverseBytes(r.readShort()).toInt() and 0xffff
            machine == 0x8664
        }
    }.getOrDefault(false)
}
