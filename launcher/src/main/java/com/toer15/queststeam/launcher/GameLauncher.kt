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
    val prefix get() = File(rootfs, "home/xuser/.wine")
    val gamesDir get() = File(prefix, "drive_c/games")
    @Volatile var current: Process? = null; private set

    fun environment(display: String?, extra: Map<String, String> = emptyMap()): Map<String, String> {
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
            // DXVK (installed into the prefix) for Direct3D 9/10/11, the game's own openvr_api.dll (our bridge).
            put("WINEDLLOVERRIDES", "d3d11,dxgi,d3d10core,d3d9=n,b;openvr_api=n,b;winemenubuilder.exe=d")
            // Turnip: Mesa's Vulkan driver for Adreno GPUs (Quest 3 = Adreno 740), from Winlator.
            put("VK_ICD_FILENAMES", "$r/usr/share/vulkan/icd.d/freedreno_icd.aarch64.json")
            put("TU_OVERRIDE_HEAP_SIZE", "4096")
            put("MESA_VK_WSI_PRESENT_MODE", "mailbox")
            put("MESA_SHADER_CACHE_DIR", "$r/tmp/mesa-cache")
            put("vblank_mode", "0")
            put("DXVK_LOG_LEVEL", "warn")
            put("DXVK_STATE_CACHE_PATH", "$r/tmp")
            put("QSS_MODE", if (DeviceMode.isQuest) "quest-flat" else "phone-flat")
            if (!display.isNullOrBlank()) put("DISPLAY", display)
            putAll(extra)
        }
    }

    /** Starts `box64 wine args...`; streams combined stdout/stderr to [onLine]. */
    fun runWine(args: List<String>, display: String?, workDir: File? = null, extraEnv: Map<String, String> = emptyMap(), onLine: (String) -> Unit): LaunchResult {
        if (!installer.isInstalled) return LaunchResult(false, "Runtime not installed")
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

    fun launchExe(exe: File, display: String?, extraEnv: Map<String, String> = emptyMap(), onLine: (String) -> Unit) =
        runWine(listOf(exe.path), display, exe.parentFile, extraEnv, onLine)

    /** Sanity check that needs no display. */
    fun smokeTest(onLine: (String) -> Unit) = runWine(listOf("cmd", "/c", "ver"), null, null, onLine = onLine)

    fun stop() { current?.destroy(); current = null }

    fun findExes(dir: File): List<File> = dir.walkTopDown()
        .filter { it.isFile && it.extension.equals("exe", true) && !it.name.contains("unins", true) }
        .sortedByDescending { it.length() }.toList()
}
