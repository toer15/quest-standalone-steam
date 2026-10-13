package com.toer15.queststeam.launcher

import android.content.Context
import java.io.File

data class LaunchResult(val ok: Boolean, val message: String)

/**
 * Pipeline (Winlator-style):
 *   1. Rootfs: a glibc arm64 Linux userland extracted into app files dir (e.g. Winlator's imagefs).
 *   2. Box64 (x86_64 -> arm64) and Box86 (x86 -> arm32/via box64 WOW64) translate CPU instructions.
 *   3. Wine (x86_64 build) runs under Box64 and provides the Windows API.
 *   4. Graphics: DXVK (D3D11 -> Vulkan) on Turnip/Adreno 740 Vulkan driver.
 *   5. VR: openvr_api.dll in the prefix is replaced by our bridge which forwards to Quest OpenXR.
 */
class GameLauncher(private val ctx: Context) {

    val root = File(ctx.filesDir, "runtime")
    val rootfs = File(root, "rootfs")
    val box64 = File(rootfs, "usr/local/bin/box64")
    val wine = File(rootfs, "opt/wine/bin/wine64")
    val prefix = File(root, "wineprefix")
    fun gameDir(appId: Int) = File(root, "games/$appId")

    fun launch(appId: Int, name: String): LaunchResult {
        if (!box64.exists()) return LaunchResult(false, "Runtime not installed yet (see TODO: installRuntime)")
        val dir = gameDir(appId)
        if (!dir.exists()) return LaunchResult(false, "$name not downloaded. See docs/DEPOTS.md")
        val exe = findExe(dir) ?: return LaunchResult(false, "No .exe found in ${dir.path}")

        // TODO(prefix): create prefix on first run: wine64 wineboot -u; install DXVK dlls into system32.
        // TODO(bridge): copy libopenvr_bridge -> prefix/.../openvr_api.dll override (WINEDLLOVERRIDES).
        // TODO(display): Wine needs an X server; embed Winlator's XServer view or termux-x11.
        // TODO(vr): start OpenXR session in a native activity and hand swapchain to the bridge.
        val pb = ProcessBuilder(box64.path, wine.path, exe.path)
            .directory(exe.parentFile)
            .redirectErrorStream(true)
        pb.environment().apply {
            put("WINEPREFIX", prefix.path)
            put("BOX64_LOG", "1")
            put("BOX64_DYNAREC", "1")
            put("WINEDLLOVERRIDES", "openvr_api=n;d3d11,dxgi=n")
            put("DISPLAY", ":0")
            put("LD_LIBRARY_PATH", File(rootfs, "usr/lib").path)
            put("SteamAppId", appId.toString())
            // Flat mode everywhere until the OpenXR bridge works; on Quest this shows as a 2D panel.
            put("QSS_MODE", if (DeviceMode.isQuest) "quest-flat" else "phone-flat")
        }
        return try {
            val p = pb.start()
            Thread { p.inputStream.bufferedReader().useLines { l -> File(root, "logs/$appId.log").apply { parentFile?.mkdirs() }.printWriter().use { w -> l.forEach(w::println) } } }.start()
            LaunchResult(true, "Started $name — check runtime/logs/$appId.log")
        } catch (e: Exception) {
            LaunchResult(false, "Launch failed: ${e.message}")
        }
    }

    /** TODO(runtime): download + extract rootfs/box64/wine/dxvk tarballs (URLs configurable) into [root]. */
    fun installRuntime(): LaunchResult = LaunchResult(false, "Not implemented yet")

    private fun findExe(dir: File): File? = dir.walkTopDown()
        .filter { it.extension.equals("exe", true) && !it.name.contains("unins", true) && !it.name.contains("crash", true) }
        .maxByOrNull { it.length() }
}
