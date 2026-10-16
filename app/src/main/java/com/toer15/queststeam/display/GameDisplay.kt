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
    private const val XKB_ASSET = "display/xkb.zip"

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
        // Keyboard layouts: without them the X server quits at once ("$XKB_CONFIG_ROOT is not set").
        val xkb = runCatching { xkbRoot(ctx) }.getOrElse { log("Couldn't unpack the keyboard data: $it"); null }
            ?: return "The keyboard data for the game display is missing from this build."
        log("Starting the game display (Termux:X11 X server on :0)…")
        val pb = ProcessBuilder(
            "/system/bin/app_process", "-Xnoimage-dex2oat", "/", "com.termux.x11.CmdEntryPoint",
            ":0", "-ac", "-listen", "tcp",
        ).redirectErrorStream(true)
        pb.environment().apply {
            put("CLASSPATH", apk)
            put("TMPDIR", tmp.path)
            put("XKB_CONFIG_ROOT", xkb.path)
            // Copies the display server's own Android log into ours (connection problems show up there).
            put("TERMUX_X11_DEBUG", "1")
            remove("LD_PRELOAD")
            remove("LD_LIBRARY_PATH")
        }
        val p = try { pb.start() } catch (e: Exception) { return "Couldn't start the display server: ${e.message}" }
        server = p
        Thread {
            runCatching { p.inputStream.bufferedReader().forEachLine { if (worthLogging(it)) log("[display] $it") } }
            log("[display server exited with code ${runCatching { p.waitFor() }.getOrDefault(-1)}]")
        }.apply { isDaemon = true; start() }
        repeat(100) {
            if (isRunning()) { log("Game display ready."); return null }
            if (!p.isAlive) return "The display server stopped (code ${runCatching { p.exitValue() }.getOrDefault(-1)}); see the log above."
            Thread.sleep(150)
        }
        return "The display server didn't start within 15 seconds; see the log above."
    }

    /**
     * The X keyboard data (xkeyboard-config, from Ubuntu's xkb-data package; bundled as
     * assets/display/xkb.zip) unpacked to files/xkb. Re-unpacked when the bundled copy changes.
     */
    fun xkbRoot(ctx: Context): File? {
        val dir = File(ctx.filesDir, "xkb")
        val stamp = File(dir, ".qss-stamp")
        val size = runCatching { ctx.assets.openFd(XKB_ASSET).use { it.length } }.getOrElse {
            runCatching { ctx.assets.open(XKB_ASSET).use { s -> var n = 0L; val b = ByteArray(1 shl 16); while (true) { val k = s.read(b); if (k < 0) break; n += k }; n } }.getOrNull()
        } ?: return null
        if (stamp.isFile && stamp.readText() == size.toString() && File(dir, "rules").isDirectory) return dir
        dir.deleteRecursively()
        dir.mkdirs()
        val base = dir.parentFile!!.canonicalPath + File.separator
        java.util.zip.ZipInputStream(ctx.assets.open(XKB_ASSET).buffered()).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                // Entries are "xkb/…"; never write outside files/.
                val out = File(dir.parentFile, e.name)
                if (!out.canonicalPath.startsWith(base)) continue
                if (e.isDirectory) out.mkdirs() else { out.parentFile?.mkdirs(); out.outputStream().use { zip.copyTo(it) } }
            }
        }
        if (!File(dir, "rules").isDirectory) return null
        stamp.writeText(size.toString())
        return dir
    }

    /** Keeps warnings, errors and connection messages from the display's debug log; drops per-frame chatter. */
    private fun worthLogging(line: String): Boolean {
        val l = line.trim()
        if (l.isEmpty()) return false
        val level = Regex("""^(?:\S+\s+\S+\s+\d+\s+\d+\s+)?([VDIWEF])[ /]""").find(l)?.groupValues?.get(1)
        if (level == null) return true // plain server output
        if (level == "W" || level == "E" || level == "F") return true
        return Regex("(?i)broadcast|connect|binder|activity|CmdEntryPoint|error|fail").containsMatchIn(l)
    }

    /**
     * Settings for the game window, sent to Termux:X11 like its `termux-x11-preference` command:
     * landscape, a fixed 1280x720 screen stretched to the window, no extra-keys bar.
     */
    fun configureApp(ctx: Context) {
        val i = Intent("com.termux.x11.CHANGE_PREFERENCE").setPackage(PACKAGE)
            .putExtra("forceOrientation", "landscape")
            .putExtra("displayResolutionMode", "custom")
            .putExtra("displayResolutionCustom", "1280x720")
            .putExtra("displayStretch", "true")
            .putExtra("showAdditionalKbd", "false")
            .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
        runCatching { ctx.sendBroadcast(i) }
    }

    /** Brings the game window (Termux:X11) to the front. */
    fun show(ctx: Context) {
        val i = ctx.packageManager.getLaunchIntentForPackage(PACKAGE) ?: return
        runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    fun stop() { server?.destroy(); server = null }
}
