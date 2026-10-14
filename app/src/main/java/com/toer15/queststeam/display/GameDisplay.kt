package com.toer15.queststeam.display

import android.content.Context
import android.content.Intent
import com.toer15.queststeam.update.Updater
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

/**
 * The game window. Wine draws games through X11; Termux:X11 shows an X11 screen as an Android
 * window (a panel on the Quest). Without it Wine has no display and a game quits with
 * "nodrv_CreateWindow … The graphics driver is missing".
 *
 * The Termux app is not needed: Termux:X11's X server is started here, from this app, the same
 * way its own `termux-x11` command does (app_process + its APK on the CLASSPATH). The Termux:X11
 * APK is bundled in this app (assets/display/termux-x11.apk, from github.com/termux/termux-x11,
 * GPL-3.0) and installs with one tap.
 */
object GameDisplay {
    const val PACKAGE = "com.termux.x11"
    const val DISPLAY = "127.0.0.1:0"
    private const val ASSET = "display/termux-x11.apk"
    private const val PORT = 6000 // X display :0 over TCP

    @Volatile private var server: Process? = null

    fun isAppInstalled(ctx: Context): Boolean =
        runCatching { ctx.packageManager.getApplicationInfo(PACKAGE, 0); true }.getOrDefault(false)

    fun isBundled(ctx: Context) = runCatching { ctx.assets.open(ASSET).close(); true }.getOrDefault(false)

    /** One-tap install of the bundled Termux:X11 (Android asks to confirm). */
    fun installApp(ctx: Context): String? {
        if (!isBundled(ctx)) return "The display app isn't bundled in this build. Install Termux:X11 from github.com/termux/termux-x11/releases."
        if (!Updater.canInstall(ctx)) {
            Updater.openInstallPermissionSettings(ctx)
            return "Allow \"Install unknown apps\" for Quest Steam, then tap Install again."
        }
        val apk = File(ctx.cacheDir, "termux-x11.apk")
        ctx.assets.open(ASSET).use { i -> apk.outputStream().use { i.copyTo(it) } }
        return runCatching { Updater.installPackage(ctx, apk, selfUpdate = false); null }.getOrElse { "Couldn't start the installer: ${it.message}" }
    }

    /** True when an X server answers on 127.0.0.1:6000. */
    fun isRunning(): Boolean = runCatching {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", PORT), 300) }
        true
    }.getOrDefault(false)

    /**
     * Starts Termux:X11's X server (display :0, TCP) if it isn't running, then waits for it.
     * Returns null when ready, or a message saying what's missing.
     */
    fun ensureRunning(ctx: Context, rootfs: File, log: (String) -> Unit): String? {
        if (isRunning()) return null
        val apk = runCatching { ctx.packageManager.getApplicationInfo(PACKAGE, 0).sourceDir }.getOrNull()
            ?: return "The game display app (Termux:X11) isn't installed."
        val tmp = File(rootfs, "tmp").apply { mkdirs() }
        log("Starting the game display (Termux:X11 X server on :0)…")
        val pb = ProcessBuilder(
            "/system/bin/app_process", "-Xnoimage-dex2oat", "/", "com.termux.x11.CmdEntryPoint",
            ":0", "-ac", "-listen", "tcp",
        ).redirectErrorStream(true)
        pb.environment().apply {
            put("CLASSPATH", apk)
            put("TMPDIR", tmp.path)
            remove("LD_PRELOAD")
            remove("LD_LIBRARY_PATH")
        }
        val p = try { pb.start() } catch (e: Exception) { return "Couldn't start the display server: ${e.message}" }
        server = p
        Thread {
            runCatching { p.inputStream.bufferedReader().forEachLine { log("[display] $it") } }
            log("[display server exited with code ${runCatching { p.waitFor() }.getOrDefault(-1)}]")
        }.apply { isDaemon = true; start() }
        repeat(100) {
            if (isRunning()) { log("Game display ready."); return null }
            if (!p.isAlive) return "The display server stopped (code ${runCatching { p.exitValue() }.getOrDefault(-1)}); see the log above."
            Thread.sleep(150)
        }
        return "The display server didn't start within 15 seconds; see the log above."
    }

    /** Brings the game window (Termux:X11) to the front. */
    fun show(ctx: Context) {
        val i = ctx.packageManager.getLaunchIntentForPackage(PACKAGE) ?: return
        runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    fun stop() { server?.destroy(); server = null }
}
