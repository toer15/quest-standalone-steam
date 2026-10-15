package com.toer15.queststeam.play

import android.content.Context
import com.toer15.queststeam.launcher.GameLauncher
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
    private const val PREF = "steam_with_games"

    /** Steam's command line under Wine on a phone GPU (Winlator's settings): no GPU in its web views, no overlay. */
    val STEAM_ARGS = listOf(
        "-noshaders", "-nooverlay", "-nocrashmonitor", "-no-shared-textures",
        "-cef-disable-gpu", "-cef-disable-gpu-compositing", "-cef-disable-d3d11",
        "-cef-disable-sandbox", "-no-cef-sandbox", "-cef-disable-seccomp-sandbox",
    )

    /** Steam's in-game overlay and launchers stay off (too heavy under Box64). */
    const val DLL_OVERRIDES = "gameoverlayrenderer,gameoverlayrenderer64,gameoverlayui.exe,gameoverlayui64.exe,x86launcher.exe,x64launcher.exe=d"

    fun dir(l: GameLauncher) = File(l.prefix, "drive_c/Program Files (x86)/Steam")
    fun exe(l: GameLauncher) = File(dir(l), "steam.exe")
    fun isInstalled(l: GameLauncher) = exe(l).isFile
    fun isSteam(exe: File) = exe.name.equals("steam.exe", ignoreCase = true)

    /** Someone has signed into this Steam (Steam remembers accounts in config/loginusers.vdf). */
    fun isSignedIn(l: GameLauncher) = runCatching {
        File(dir(l), "config/loginusers.vdf").takeIf { it.isFile }?.readText()?.contains("AccountName") == true
    }.getOrDefault(false)

    fun startWithGames(ctx: Context) = ctx.getSharedPreferences("runtime", 0).getBoolean(PREF, true)
    fun setStartWithGames(ctx: Context, on: Boolean) = ctx.getSharedPreferences("runtime", 0).edit().putBoolean(PREF, on).apply()

    fun connectionLog(l: GameLauncher) = File(dir(l), "logs/connection_log.txt")

    private val loggedOn = Regex("logged ?on|logon.*\\bOK\\b|\\[U:1:\\d+\\].*connected", RegexOption.IGNORE_CASE)
    private val notLoggedOn = Regex("not logged ?on|logged ?off|logon fail", RegexOption.IGNORE_CASE)

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
        runCatching { File(exeDir, "steam_appid.txt").takeIf { !it.exists() }?.writeText("$appId\n") }
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
