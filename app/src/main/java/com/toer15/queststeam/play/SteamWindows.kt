package com.toer15.queststeam.play

import android.content.Context
import com.toer15.queststeam.launcher.GameLauncher
import com.toer15.queststeam.launcher.WineRegistry
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL

/**
 * Steam for Windows inside the runtime, for games that need the Steam client running (online games,
 * Steamworks: lobbies, friends, achievements). Installed from Valve's official installer, signed
 * into once by the player in the game display, then started in the background before such games.
 */
object SteamWindows {
    const val SETUP_URL = "https://cdn.cloudflare.steamstatic.com/client/installer/SteamSetup.exe"

    /** Steam's command line under Wine on a phone GPU (Winlator's settings): no GPU in its web views, no overlay. */
    val STEAM_ARGS = listOf(
        "-noshaders", "-nooverlay", "-nocrashmonitor", "-no-shared-textures",
        "-cef-disable-gpu", "-cef-disable-gpu-compositing", "-cef-disable-d3d11",
        "-cef-disable-sandbox", "-no-cef-sandbox", "-cef-disable-seccomp-sandbox",
        // No "not responding" restarts of Steam's browser when the Windows translation makes it answer late.
        "-cef-disable-hang-timeouts",
    )

    /**
     * Light Steam: Steam's built-in browser (its windows) in one process, with its GPU process inside,
     * instead of five or six processes. Under Box64 every process keeps its own translated copy of
     * the browser code, hundreds of MB each; with a game next to it the headset ran out of memory.
     */
    private val LIGHT_ARGS = listOf(
        "-cef-single-process", "-cef-in-process-gpu",
        // Steam's own friends and chat windows: the app's Friends tab shows friends and chat instead.
        "-nofriendsui", "-nochatui",
        // No crash-report process next to the browser, no video decoder.
        "-cef-disable-breakpad", "-disable-winh264",
    )

    private const val PREF_LIGHT = "steam_light"
    fun light(ctx: Context) = ctx.getSharedPreferences("runtime", 0).getBoolean(PREF_LIGHT, true)
    fun setLight(ctx: Context, on: Boolean) = ctx.getSharedPreferences("runtime", 0).edit().putBoolean(PREF_LIGHT, on).apply()

    /** Steam's command line options, with [light] mode if it's on. */
    fun args(ctx: Context): List<String> = STEAM_ARGS + if (light(ctx)) LIGHT_ARGS else emptyList()

    /**
     * Steam's core without its browser ("-no-browser": none of the steamwebhelper.exe processes). The
     * browser only draws Steam's windows, yet it is most of Steam's start time (about 60 of the 78 seconds
     * of processor time in a start) and memory (up to 3 GB); a game needs Steam's core (steam.exe:
     * sign-in, Steamworks). It's not known whether the current Steam signs in without the browser, so the
     * first start tests it and goes back to the browser if Steam doesn't sign in (see GameRunner).
     */
    private val CORE_ARGS = listOf("-no-browser", "-noshaders", "-nooverlay", "-nocrashmonitor", "-no-shared-textures", "-nofriendsui", "-nochatui")
    fun coreArgs(): List<String> = CORE_ARGS

    private const val PREF_CORE = "steam_core"
    private const val PREF_CORE_WORKS = "steam_core_works"

    /** "Steam without its browser" may be used (Runtime settings → Steam for Windows; on by default). */
    fun coreAllowed(ctx: Context) = ctx.getSharedPreferences("runtime", 0).getString(PREF_CORE, "auto") != "off"

    /** Turning it back on tests it again. */
    fun setCoreAllowed(ctx: Context, on: Boolean) =
        ctx.getSharedPreferences("runtime", 0).edit().putString(PREF_CORE, if (on) "auto" else "off").remove(PREF_CORE_WORKS).apply()

    /** null = not tested yet, true = Steam signed in without its browser on this headset, false = it didn't. */
    fun coreWorks(ctx: Context): Boolean? =
        ctx.getSharedPreferences("runtime", 0).let { if (it.contains(PREF_CORE_WORKS)) it.getBoolean(PREF_CORE_WORKS, false) else null }

    fun setCoreWorks(ctx: Context, works: Boolean) = ctx.getSharedPreferences("runtime", 0).edit().putBoolean(PREF_CORE_WORKS, works).apply()

    /** Untested again (the next start tests it). */
    fun forgetCore(ctx: Context) = ctx.getSharedPreferences("runtime", 0).edit().remove(PREF_CORE_WORKS).apply()

    /** How many seconds Steam took to sign in the last time, per kind of start (without / with its browser); null = never measured. */
    fun lastSignInS(ctx: Context, core: Boolean): Long? =
        ctx.getSharedPreferences("runtime", 0).getLong(if (core) "steam_sign_in_core_s" else "steam_sign_in_s", -1L).takeIf { it >= 0 }

    fun setLastSignInS(ctx: Context, core: Boolean, secs: Long) =
        ctx.getSharedPreferences("runtime", 0).edit().putLong(if (core) "steam_sign_in_core_s" else "steam_sign_in_s", secs).apply()

    /**
     * Steam's own settings that cost the most without a GPU (its windows are drawn by its built-in
     * browser, in software here): smooth scrolling, and GPU / video acceleration in its web views.
     * Written while Wine isn't running.
     */
    fun applyFastSettings(l: GameLauncher) {
        val reg = File(l.prefix, "user.reg")
        for (name in listOf("SmoothScrollWebViews", "GPUAccelWebViewsV3", "H264HWAccel")) {
            WineRegistry.setDword(reg, "Software\\Valve\\Steam", name, 0)
        }
    }

    /**
     * Steam's own programs keep Wine's built-in Direct3D (no DXVK, which games get through the global
     * DLL settings) and its GPU probes get no Vulkan: each Direct3D/Vulkan start goes through the GPU
     * driver bridge and costs Steam seconds (and a Steam process crashed in DXVK on the headset).
     */
    fun keepOffGpu(l: GameLauncher) {
        val reg = File(l.prefix, "user.reg")
        for (exe in OWN_PROGRAMS) for (dll in listOf("d3d9", "d3d10core", "d3d11", "dxgi")) {
            WineRegistry.setString(reg, "Software\\Wine\\AppDefaults\\$exe\\DllOverrides", dll, "builtin")
        }
        for (exe in GPU_PROBES) WineRegistry.setString(reg, "Software\\Wine\\AppDefaults\\$exe\\DllOverrides", "vulkan-1", "")
    }

    private val OWN_PROGRAMS = listOf(
        "steam.exe", "steamwebhelper.exe", "steamservice.exe", "steamsysinfo.exe",
        "steamerrorreporter.exe", "steamerrorreporter64.exe", "gameoverlayui.exe", "gameoverlayui64.exe",
    )
    private val GPU_PROBES = listOf("vulkandriverquery.exe", "vulkandriverquery64.exe", "gldriverquery.exe", "gldriverquery64.exe")

    /** Steam's built-in browser: it draws Steam's windows (sign-in, library, store). Games don't need it. */
    const val WEB_HELPER = "steamwebhelper.exe"

    /** Steam's windows (sign-in, library) are drawn by its built-in browser, steamwebhelper.exe. */
    const val UI_WINDOW_CLASS = WEB_HELPER

    /** Steam's in-game overlay and launchers stay off (too heavy under Box64). */
    const val DLL_OVERRIDES = "gameoverlayrenderer,gameoverlayrenderer64,gameoverlayui.exe,gameoverlayui64.exe,x86launcher.exe,x64launcher.exe=d"

    /**
     * [name] in [parent], ignoring case (Android's file system is case-sensitive, Windows isn't):
     * the exact spelling if it exists, otherwise the newest file spelled differently.
     */
    private fun child(parent: File, name: String): File {
        val exact = File(parent, name)
        if (exact.exists()) return exact
        return parent.listFiles()?.filter { it.name.equals(name, ignoreCase = true) }?.maxByOrNull { it.lastModified() } ?: exact
    }

    /**
     * Earlier versions could leave both "Steam.exe" (Valve's installer) and "steam.exe" (the client
     * package) in the Steam folder. Keeps the newest one so Wine doesn't start the stale copy.
     */
    fun dedupeExe(l: GameLauncher): String? {
        val copies = dir(l).listFiles()?.filter { it.isFile && it.name.equals("steam.exe", ignoreCase = true) }.orEmpty()
        if (copies.size < 2) return null
        val keep = copies.maxByOrNull { it.lastModified() } ?: return null
        val gone = copies.filter { it != keep && it.delete() }.map { it.name }
        return if (gone.isEmpty()) null else "kept ${keep.name}, removed ${gone.joinToString()}"
    }

    /**
     * Environment only Steam's own programs get (games started next to it don't): the translation
     * cache. (Light Steam used to show Steam two CPU cores; with its browser paused once it has
     * signed in, all cores make it ready sooner.)
     */
    @Suppress("UNUSED_PARAMETER")
    fun steamOnlyEnv(ctx: Context, l: GameLauncher): Map<String, String> = translationCacheEnv(l)

    /**
     * Box64 keeps the code it translated for Steam on storage (its "DynaCache"), so later Steam starts
     * skip most of the translation work. Only for Steam; games keep Winlator's setting (off).
     */
    fun translationCacheEnv(l: GameLauncher): Map<String, String> {
        val dir = File(l.rootfs, "home/xuser/.cache/box64").apply { mkdirs() }
        return mapOf("BOX64_DYNACACHE" to "1", "BOX64_DYNACACHE_FOLDER" to dir.path)
    }

    fun dir(l: GameLauncher) = child(child(File(l.prefix, "drive_c"), "Program Files (x86)"), "Steam")
    /** Valve's installer writes "Steam.exe". */
    fun exe(l: GameLauncher) = child(dir(l), "steam.exe")

    /** What's in the Steam folder, for the log when steam.exe can't be found. */
    fun describeDir(l: GameLauncher): String {
        val d = dir(l)
        return if (!d.isDirectory) "${d.path} doesn't exist" else d.path + ": " + (d.list()?.sorted()?.take(40)?.joinToString(", ") ?: "")
    }
    fun isInstalled(l: GameLauncher) = exe(l).isFile
    fun isSteam(exe: File) = exe.name.equals("steam.exe", ignoreCase = true)

    /** Someone has signed into this Steam (Steam remembers accounts in config/loginusers.vdf). */
    fun isSignedIn(l: GameLauncher) = runCatching {
        child(child(dir(l), "config"), "loginusers.vdf").takeIf { it.isFile }?.readText()?.contains("AccountName") == true
    }.getOrDefault(false)

    /**
     * "Get Steam ready when the app opens" (see GameRunner.warmUpSteam): on unless the player turns it off, so
     * the first Play is fast too. A new key: v0.18.1 to v0.19.0 kept it off.
     */
    fun warmUp(ctx: Context) = ctx.getSharedPreferences("runtime", 0).getBoolean("steam_warm_up_v3", true)
    fun setWarmUp(ctx: Context, on: Boolean) = ctx.getSharedPreferences("runtime", 0).edit().putBoolean("steam_warm_up_v3", on).apply()

    fun connectionLog(l: GameLauncher) = child(child(dir(l), "logs"), "connection_log.txt")

    private val loggedOn = Regex("logged ?on|logon.*\\bOK\\b|\\[U:1:\\d+\\].*connected", RegexOption.IGNORE_CASE)
    private val notLoggedOn = Regex("not logged ?on|logged ?off|logon fail", RegexOption.IGNORE_CASE)

    /**
     * The last lines of Steam's updater log (logs/bootstrap_log.txt) without timestamps: what Steam is
     * doing on its first start ("Downloading update (12,345 of 98,765 KB)…").
     */
    fun bootstrapTail(l: GameLauncher, n: Int = 3): List<String> = runCatching {
        val f = child(child(dir(l), "logs"), "bootstrap_log.txt")
        if (!f.isFile) return emptyList()
        RandomAccessFile(f, "r").use { raf ->
            val from = (raf.length() - 8_000).coerceAtLeast(0)
            raf.seek(from)
            val bytes = ByteArray((raf.length() - from).toInt())
            raf.readFully(bytes)
            String(bytes).lines().map { it.replace(Regex("^\\[[^\\]]*\\]\\s*"), "").trim() }.filter { it.isNotBlank() }.takeLast(n)
        }
    }.getOrDefault(emptyList())

    /** What Steam's first start is doing, from its updater log: a phase and, while downloading, a fraction. */
    data class BootStatus(val phase: String, val fraction: Float?)

    private val amount = Regex("([\\d,.]+)\\s*(?:of|/)\\s*([\\d,.]+)\\s*(KB|MB|GB|bytes)?", RegexOption.IGNORE_CASE)

    fun bootStatus(lines: List<String>): BootStatus {
        val last = lines.lastOrNull().orEmpty()
        val m = lines.asReversed().firstNotNullOfOrNull { l -> amount.find(l)?.takeIf { l.contains("download", true) } }
        val fraction = m?.let {
            val done = it.groupValues[1].replace(",", "").toDoubleOrNull()
            val total = it.groupValues[2].replace(",", "").toDoubleOrNull()
            if (done != null && total != null && total > 0) (done / total).toFloat().coerceIn(0f, 1f) else null
        }
        val phase = when {
            last.contains("download", true) -> "Downloading Steam"
            last.contains("extract", true) || last.contains("unpack", true) -> "Unpacking Steam"
            last.contains("install", true) || last.contains("apply", true) -> "Installing Steam"
            last.contains("verif", true) || last.contains("check", true) -> "Checking Steam's files"
            last.contains("launch", true) || last.contains("start", true) || last.contains("complete", true) -> "Steam is starting"
            lines.isEmpty() -> "Steam is starting up"
            else -> "Preparing Steam"
        }
        return BootStatus(phase, if (last.contains("download", true)) fraction else null)
    }

    private val connectionEvent = Regex("ConnectionCompleted|Disconnect|Timeout|timed out|fail|error|Logged ?On|LogOn|Logon|refused|reset|closed|Retry|Encrypt", RegexOption.IGNORE_CASE)

    /** The last lines of every Steam log written since [since] (Steam's own view of what went wrong). */
    fun logTails(l: GameLauncher, since: Long, lines: Int = 8): List<String> = runCatching {
        val logs = child(dir(l), "logs").listFiles().orEmpty().filter { it.isFile && it.lastModified() >= since - 5_000 }
        logs.sortedBy { it.name }.flatMap { f ->
            val text = RandomAccessFile(f, "r").use { raf ->
                val from = (raf.length() - (if (f.name.equals("connection_log.txt", true)) 60_000 else 6_000)).coerceAtLeast(0); raf.seek(from)
                ByteArray((raf.length() - from).toInt()).also { raf.readFully(it) }.let { String(it) }
            }
            val all = text.lines().filter { it.isNotBlank() }
            // The connection log is mostly server pings; its connects, drops and sign-in results matter.
            val picked = if (f.name.equals("connection_log.txt", true)) all.filter { connectionEvent.containsMatchIn(it) }.takeLast(20).ifEmpty { all.takeLast(lines) }
            else all.takeLast(lines)
            listOf("  ${f.name}:") + picked.map { "    " + it.take(250) }
        }
    }.getOrDefault(emptyList())

    /** True once Steam's connection log, past [offset], says it signed in. */
    fun loggedOnSince(l: GameLauncher, offset: Long): Boolean = runCatching {
        val f = connectionLog(l)
        if (!f.isFile || f.length() <= offset) return false
        RandomAccessFile(f, "r").use { raf ->
            raf.seek(offset)
            val bytes = ByteArray((raf.length() - offset).coerceAtMost(256_000).toInt())
            raf.readFully(bytes)
            String(bytes).lineSequence().any { loggedOn.containsMatchIn(it) && !notLoggedOn.containsMatchIn(it) }
        }
    }.getOrDefault(false)

    /** Tells Steamworks which game this is when it isn't started from Steam's own library. */
    fun writeAppId(exeDir: File, appId: Int) {
        if (appId <= 0) return
        runCatching { File(exeDir, "steam_appid.txt").writeText("$appId\n") }
    }

    /** Downloads Valve's SteamSetup.exe (about 2 MB). */
    fun downloadSetup(dest: File, progress: (Float) -> Unit) {
        dest.parentFile?.mkdirs()
        val part = File(dest.path + ".part")
        val conn = URL(SETUP_URL).openConnection() as HttpURLConnection
        conn.connectTimeout = 20_000
        conn.readTimeout = 60_000
        conn.instanceFollowRedirects = true
        try {
            if (conn.responseCode !in 200..299) throw java.io.IOException("HTTP ${conn.responseCode} from $SETUP_URL")
            val total = conn.contentLengthLong
            conn.inputStream.use { inp ->
                part.outputStream().use { out ->
                    val buf = ByteArray(1 shl 16)
                    var done = 0L
                    while (true) {
                        val n = inp.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) progress(done.toFloat() / total)
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        if (part.length() < 500_000) throw java.io.IOException("SteamSetup.exe download is too small (${part.length()} bytes)")
        dest.delete()
        if (!part.renameTo(dest)) throw java.io.IOException("Couldn't save SteamSetup.exe")
    }
}
