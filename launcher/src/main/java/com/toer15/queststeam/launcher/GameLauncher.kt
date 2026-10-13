package com.toer15.queststeam.launcher

import android.content.Context
import java.io.File

data class LaunchResult(val ok: Boolean, val message: String)

/**
 * Runs: rootfs/usr/local/bin/box64 rootfs/opt/wine/bin/wine <exe>
 * Box64 (arm64) translates x86_64 Wine; Wine runs the Windows .exe.
 *
 * Display: Wine needs an X11 server. Supported today:
 *  - Termux:X11 over TCP (DISPLAY=127.0.0.1:0). In Termux run: `termux-x11 :0 -listen tcp -ac`.
 *  - Headless (no DISPLAY) for console programs (e.g. `cmd /c ver`).
 * TODO: embed an in-app X server (port of Winlator's Java XServer, LGPL) to drop the Termux dependency.
 * TODO: OpenXR/VR via openvr-bridge.
 */
class GameLauncher(ctx: Context) {

    private val installer = RuntimeInstaller(ctx)
    val rootfs: File get() = installer.rootfs
    val isInstalled get() = installer.isInstalled
    val prefix get() = File(rootfs, "home/xuser/.wine")
    val gamesDir get() = File(prefix, "drive_c/games")
    @Volatile var current: Process? = null; private set

    fun environment(display: String?): Map<String, String> {
        val r = rootfs.path
        return buildMap {
            put("HOME", "$r/home/xuser")
            put("USER", "xuser")
            put("TMPDIR", "$r/tmp")
            put("WINEPREFIX", prefix.path)
            put("WINEDEBUG", "-all,err+all")
            put("PATH", "$r/usr/local/bin:$r/opt/wine/bin:$r/usr/bin:/system/bin")
            put("LD_LIBRARY_PATH", "$r/usr/lib:/system/lib64")
            put("BOX64_LD_LIBRARY_PATH", "$r/usr/lib/x86_64-linux-gnu:$r/opt/wine/lib/wine/x86_64-unix")
            put("BOX64_PATH", "$r/opt/wine/bin")
            put("BOX64_DYNAREC", "1")
            put("BOX64_LOG", "1")
            put("WINEDLLOVERRIDES", "openvr_api=n,b;winemenubuilder.exe=d")
            put("QSS_MODE", if (DeviceMode.isQuest) "quest-flat" else "phone-flat")
            if (!display.isNullOrBlank()) put("DISPLAY", display)
        }
    }

    /** Starts `box64 wine args...`; streams combined stdout/stderr to [onLine]. */
    fun runWine(args: List<String>, display: String?, workDir: File? = null, onLine: (String) -> Unit): LaunchResult {
        if (!installer.isInstalled) return LaunchResult(false, "Runtime not installed")
        val cmd = listOf(File(rootfs, "usr/local/bin/box64").path, File(rootfs, "opt/wine/bin/wine").path) + args
        onLine("$ " + cmd.joinToString(" "))
        return try {
            val pb = ProcessBuilder(cmd).directory(workDir ?: prefix).redirectErrorStream(true)
            pb.environment().putAll(environment(display))
            val p = pb.start(); current = p
            Thread {
                runCatching { p.inputStream.bufferedReader().forEachLine(onLine) }
                onLine("[process exited with code ${runCatching { p.waitFor() }.getOrDefault(-1)}]")
            }.start()
            LaunchResult(true, "Started")
        } catch (e: Exception) {
            LaunchResult(false, "Launch failed: ${e.message}")
        }
    }

    fun launchExe(exe: File, display: String?, onLine: (String) -> Unit) =
        runWine(listOf(exe.path), display, exe.parentFile, onLine)

    /** Sanity check that needs no display. */
    fun smokeTest(onLine: (String) -> Unit) = runWine(listOf("cmd", "/c", "ver"), null, null, onLine)

    fun stop() { current?.destroy(); current = null }

    fun findExes(dir: File): List<File> = dir.walkTopDown()
        .filter { it.isFile && it.extension.equals("exe", true) && !it.name.contains("unins", true) }
        .sortedByDescending { it.length() }.toList()
}
