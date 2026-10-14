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
class GameLauncher(ctx: Context) {

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
    fun runWine(args: List<String>, display: String?, workDir: File? = null, extraEnv: Map<String, String> = emptyMap(), onLine: (String) -> Unit): LaunchResult {
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
                onLine("[process exited with code ${runCatching { p.waitFor() }.getOrDefault(-1)}]")
            }.start()
            LaunchResult(true, "Started")
        } catch (e: Exception) {
            LaunchResult(false, "Launch failed: ${e.message}")
        }
    }

    fun launchExe(exe: File, display: String?, extraEnv: Map<String, String> = emptyMap(), args: List<String> = emptyList(), onLine: (String) -> Unit) =
        runWine(listOf(exe.path) + args, display, exe.parentFile, extraEnv, onLine)

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

    fun stop() { current?.destroy(); current = null }

    fun findExes(dir: File): List<File> = dir.walkTopDown()
        .filter { it.isFile && it.extension.equals("exe", true) && !it.name.contains("unins", true) }
        .sortedByDescending { it.length() }.toList()
}
