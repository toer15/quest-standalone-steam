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
 * VR: the game's openvr_api.dll is replaced by the bridge (see app VrBridge); QSS_VR_SHM tells it where to connect.
 */
class GameLauncher(private val ctx: Context) {

    private val installer = RuntimeInstaller(ctx)
    val rootfs: File get() = installer.rootfs
    val isInstalled get() = installer.isInstalled
    val needsUpdate get() = installer.needsUpdate
    val prefix get() = File(rootfs, "home/xuser/.wine")
    val gamesDir get() = File(prefix, "drive_c/games")
    @Volatile var current: Process? = null; private set

    fun environment(display: String?, extra: Map<String, String> = emptyMap()): Map<String, String> =
        RuntimeCore.wineEnvironment(rootfs.path, display, if (DeviceMode.isQuest) "quest" else "phone", extra)

    /** Starts `box64 wine args...`; streams combined stdout/stderr to [onLine]. */
    fun runWine(args: List<String>, display: String?, workDir: File? = null, extraEnv: Map<String, String> = emptyMap(), onExit: (Int) -> Unit = {}, onLine: (String) -> Unit): LaunchResult {
        if (!installer.isInstalled) return LaunchResult(false, "Runtime not downloaded yet: tap Download runtime.")
        if (RuntimeCore.repairPrefix(rootfs)) onLine("Repaired the Wine prefix (linked Wine's system DLLs, fixes exit code 53).")
        if (!RuntimeCore.prefixHealthy(rootfs)) return LaunchResult(false, "Wine prefix is broken: delete the runtime and download it again.")
        val cmd = listOf(File(rootfs, "usr/local/bin/box64").path, File(rootfs, "opt/wine/bin/wine").path) + args
        onLine("$ " + cmd.joinToString(" "))
        return try {
            File(rootfs, "tmp/mesa-cache").mkdirs()
            val pb = ProcessBuilder(cmd).directory(workDir ?: prefix).redirectErrorStream(true)
            pb.environment().putAll(environment(display, extraEnv))
            val p = pb.start(); current = p
            Thread {
                runCatching { p.inputStream.bufferedReader().forEachLine(onLine) }
                val code = runCatching { p.waitFor() }.getOrDefault(-1)
                onLine("[process exited with code $code]")
                runCatching { onExit(code) }
            }.start()
            LaunchResult(true, "Started")
        } catch (e: Exception) {
            LaunchResult(false, "Launch failed: ${e.message}")
        }
    }

    /**
     * Starts [exe]. With [desktop] ("1280x720") it runs inside Wine's virtual desktop, like Winlator:
     * one screen-sized window that games draw into, so the game fills the game display.
     */
    fun launchExe(
        exe: File, display: String?, extraEnv: Map<String, String> = emptyMap(), args: List<String> = emptyList(),
        desktop: String? = null, onExit: (Int) -> Unit = {}, onLine: (String) -> Unit,
    ): LaunchResult {
        // explorer starts the game with Windows' CreateProcess, which needs a Windows path (C:\games\…).
        val cmd = if (desktop != null) listOf("explorer", "/desktop=shell,$desktop", windowsPath(exe)) + args else listOf(exe.path) + args
        return runWine(cmd, display, exe.parentFile, extraEnv, onExit, onLine)
    }

    /** "…/.wine/drive_c/games/1/Game.exe" -> "C:\\games\\1\\Game.exe"; outside drive_c -> "Z:" + path in the runtime. */
    fun windowsPath(f: File): String {
        val driveC = File(prefix, "drive_c").absolutePath.trimEnd('/') + "/"
        val p = f.absolutePath
        return if (p.startsWith(driveC)) "C:\\" + p.removePrefix(driveC).replace('/', '\\')
        else "Z:" + p.removePrefix(rootfs.absolutePath).replace('/', '\\')
    }

    companion object {
        /** Splits a Steam launch-arguments string, keeping "quoted parts" together. */
        fun splitArgs(s: String): List<String> {
            val out = mutableListOf<String>()
            val cur = StringBuilder()
            var quoted = false
            var has = false
            for (c in s) {
                when {
                    c == '"' -> { quoted = !quoted; has = true }
                    c.isWhitespace() && !quoted -> { if (has) { out += cur.toString(); cur.clear(); has = false } }
                    else -> { cur.append(c); has = true }
                }
            }
            if (has) out += cur.toString()
            return out
        }
    }

    /** Sanity check that needs no display. */
    fun smokeTest(onLine: (String) -> Unit) = runWine(listOf("cmd", "/c", "ver"), null, null, onLine = onLine)

    fun stop() { current?.destroy(); current = null; killLeftovers() }

    /**
     * Kills every Wine/Box64 process this app started (wineserver, explorer, the game, wineboot…).
     * Stopping only the first process leaves the rest running, and the next game then talks to a
     * wineserver that remembers the old display's windows (X error BadWindow on X_CreateWindow).
     * Returns how many were killed. Keeps this app's own processes and the Termux:X11 server.
     */
    fun killLeftovers(): Int {
        val me = android.os.Process.myPid()
        val uid = android.os.Process.myUid()
        var n = 0
        File("/proc").listFiles()?.forEach { d ->
            val pid = d.name.toIntOrNull() ?: return@forEach
            if (pid == me) return@forEach
            val status = runCatching { File(d, "status").readText() }.getOrNull() ?: return@forEach
            val procUid = Regex("(?m)^Uid:\\s+(\\d+)").find(status)?.groupValues?.get(1)?.toIntOrNull()
            if (procUid != uid) return@forEach
            val cmd = runCatching { File(d, "cmdline").readText().replace('\u0000', ' ').trim() }.getOrDefault("")
            if (cmd.startsWith(ctx.packageName) || cmd.contains("app_process") || cmd.startsWith("logcat") || cmd.startsWith("sh ")) return@forEach
            android.os.Process.killProcess(pid)
            n++
        }
        return n
    }

    fun findExes(dir: File): List<File> = dir.walkTopDown()
        .filter { it.isFile && it.extension.equals("exe", true) && !it.name.contains("unins", true) }
        .sortedByDescending { it.length() }.toList()
}
