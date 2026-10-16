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

    /**
     * Starts `box64 wine args...`; streams combined stdout/stderr to [onLine]. With [track] the process
     * becomes [current] (the session's main process); programs started into a running session aren't.
     */
    fun runWine(
        args: List<String>, display: String?, workDir: File? = null, extraEnv: Map<String, String> = emptyMap(),
        onExit: (Int) -> Unit = {}, track: Boolean = true, onLine: (String) -> Unit,
    ): LaunchResult {
        if (!installer.isInstalled) return LaunchResult(false, "Runtime not downloaded yet: tap Download runtime.")
        if (RuntimeCore.repairPrefix(rootfs)) onLine("Repaired the Wine prefix (linked Wine's system DLLs, fixes exit code 53).")
        if (!RuntimeCore.prefixHealthy(rootfs)) return LaunchResult(false, "Wine prefix is broken: delete the runtime and download it again.")
        // Every Wine launch goes through here, so installs from older app versions get fixed too.
        runCatching { RuntimeCore.disablePreExec(rootfs) }.getOrNull()?.forEach {
            onLine("Runtime fix: $it (Winlator's pre-exec hook made Steam.exe wait forever).")
        }
        val cmd = listOf(File(rootfs, "usr/local/bin/box64").path, File(rootfs, "opt/wine/bin/wine").path) + args
        onLine("$ " + cmd.joinToString(" ") { if (' ' in it) "\"$it\"" else it })
        return try {
            File(rootfs, "tmp/mesa-cache").mkdirs()
            File(rootfs, "home/xuser/.cache/dxvk").mkdirs()
            val pb = ProcessBuilder(cmd).directory(workDir ?: prefix).redirectErrorStream(true)
            pb.environment().putAll(environment(display, extraEnv))
            val p = pb.start()
            if (track) current = p
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
        desktop: String? = null, onExit: (Int) -> Unit = {}, track: Boolean = true, desktopName: String = DESKTOP,
        onLine: (String) -> Unit,
    ): LaunchResult {
        // explorer starts the game with Windows' CreateProcess, which needs a Windows path (C:\games\…).
        // "nogui" is the bare game desktop of Winlator's Wine (what Winlator uses for games); "shell"
        // would add a taskbar and start menu. A program started while another one owns that desktop
        // gets its own [desktopName]: Wine can't hand it to a desktop another explorer owns (it ends
        // up with no window driver: "nodrv_CreateWindow … The explorer process failed to start").
        val cmd = if (desktop != null) listOf("explorer", "/desktop=$desktopName,$desktop", windowsPath(exe)) + args else listOf(exe.path) + args
        return runWine(cmd, display, exe.parentFile, extraEnv, onExit, track, onLine)
    }

    /** "…/.wine/drive_c/games/1/Game.exe" -> "C:\\games\\1\\Game.exe"; outside drive_c -> "Z:" + path in the runtime. */
    fun windowsPath(f: File): String {
        val driveC = File(prefix, "drive_c").absolutePath.trimEnd('/') + "/"
        val p = f.absolutePath
        return if (p.startsWith(driveC)) "C:\\" + p.removePrefix(driveC).replace('/', '\\')
        else "Z:" + p.removePrefix(rootfs.absolutePath).replace('/', '\\')
    }

    companion object {
        /** The game desktop of the session's main program (see [launchExe]). */
        const val DESKTOP = "nogui"

        private const val SIGCONT = 18
        private const val SIGSTOP = 19

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

        /** Steam for Windows' own programs (steam.exe, steamwebhelper.exe, steamservice.exe…). */
        fun isSteamProgram(name: String) = name.lowercase().let { it.startsWith("steam") || it.startsWith("gameoverlayui") }

        /** [name] (from [processName]) is the Windows program [exeName], or Wine's loader for it. */
        fun isProcessOf(name: String, exeName: String) =
            name.equals(exeName, ignoreCase = true) || name.startsWith("$exeName(", ignoreCase = true)

        /**
         * "C:\Program Files (x86)\Steam\steam.exe -silent …" -> "steam.exe"; unix programs -> their file name.
         * Helpers named after the program they start say so: "winpreexec(Steam.exe)" is Winlator's pre-exec
         * hook, "Steam.exe(loading)" is Wine's loader before the program itself runs.
         */
        fun processName(cmd: String): String {
            val argv0 = cmd.substringBefore(' ').substringAfterLast('/')
            // "box64 …/wine explorer /desktop=… C:\games\1\Game.exe" is Wine's launcher for the game,
            // not the game (before Wine renames it "C:\windows\system32\explorer.exe …").
            if (argv0 == "box64" || argv0 == "wine" || argv0 == "wine64") {
                val words = cmd.split(' ').filter { it.isNotEmpty() }
                val prog = words.getOrNull(if (argv0 == "box64") 2 else 1)?.substringAfterLast('/')?.substringAfterLast('\\')?.lowercase()
                if (prog == "explorer" || prog == "explorer.exe") return "explorer.exe"
                if (prog == "start" || prog == "start.exe") return "start.exe"
            }
            val exe = Regex("([^\\\\/]+\\.exe)\\b", RegexOption.IGNORE_CASE).find(cmd)?.value ?: return argv0
            return when (argv0) {
                "winpreexec", "winpreexec.disabled" -> "winpreexec($exe)"
                "wine", "wine64" -> "$exe(loading)"
                else -> exe
            }
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
    /**
     * The Windows programs running right now (Wine shows them by their Windows path) with the
     * processor time each has used, e.g. "steamwebhelper.exe 42s". For the log and loading screen.
     */
    fun processes(): List<String> =
        running().groupBy { it.name }.map { (name, list) ->
            val secs = list.sumOf { it.cpuSeconds }
            name + (if (list.size > 1) " ×${list.size}" else "") + " ${secs}s"
        }.sorted()

    data class Proc(val pid: Int, val name: String, val cpuSeconds: Long, val rssKb: Long = 0)

    /** This app's Wine/Box64 processes (not the app itself), named as in [processName]. */
    fun running(): List<Proc> {
        val me = android.os.Process.myPid()
        val uid = android.os.Process.myUid()
        val out = mutableListOf<Proc>()
        File("/proc").listFiles()?.forEach { d ->
            val pid = d.name.toIntOrNull() ?: return@forEach
            if (pid == me) return@forEach
            val status = runCatching { File(d, "status").readText() }.getOrNull() ?: return@forEach
            if (Regex("(?m)^Uid:\\s+(\\d+)").find(status)?.groupValues?.get(1)?.toIntOrNull() != uid) return@forEach
            val cmd = runCatching { File(d, "cmdline").readText().replace('\u0000', ' ').trim() }.getOrDefault("")
            if (cmd.isEmpty() || cmd.startsWith(ctx.packageName) || cmd.startsWith("logcat")) return@forEach
            val stat = runCatching { File(d, "stat").readText() }.getOrDefault("")
            val fields = stat.substringAfterLast(')').trim().split(' ')
            val ticks = (fields.getOrNull(11)?.toLongOrNull() ?: 0) + (fields.getOrNull(12)?.toLongOrNull() ?: 0)
            val rss = Regex("(?m)^VmRSS:\\s+(\\d+)").find(status)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            out += Proc(pid, processName(cmd), ticks / 100, rss)
        }
        return out
    }

    /** Processes of the Windows program [exeName] ("REPO.exe"), including Wine's loader for it. */
    fun processesOf(exeName: String): List<Proc> = running().filter { isProcessOf(it.name, exeName) }

    fun isRunning(exeName: String) = processesOf(exeName).isNotEmpty()

    /** Ends the Windows program [exeName] only (Steam and the rest of the session keep running). */
    fun kill(exeName: String): Int = processesOf(exeName).onEach { android.os.Process.killProcess(it.pid) }.size

    /**
     * Pauses ([pause]) or resumes the Windows program [exeName], all its processes. A paused program
     * keeps its memory but uses no CPU, and Android can compress that memory when it runs short.
     * Returns the number of processes.
     */
    fun pause(exeName: String, pause: Boolean): Int =
        processesOf(exeName).onEach { android.os.Process.sendSignal(it.pid, if (pause) SIGSTOP else SIGCONT) }.size

    /**
     * Pauses ([pause]) or resumes every Windows program and Wine's server: a game left for Quick Resume
     * that runs on its own. The server pauses last and resumes first. Returns the number of processes.
     */
    fun pauseAll(pause: Boolean): Int {
        val procs = running().filter { it.name.contains(".exe", ignoreCase = true) || it.name == "wineserver" }
            .sortedBy { (it.name == "wineserver") == pause }
        procs.forEach { android.os.Process.sendSignal(it.pid, if (pause) SIGSTOP else SIGCONT) }
        return procs.size
    }

    /**
     * CPU priority of every Wine/Box64 thread: the lowest for Steam's programs when [steamLow], and
     * for everything else when [othersLow]; normal otherwise. The lowest also puts threads on the
     * small CPU cores, so Steam idling next to a game (or behind the app) leaves the big ones free.
     * Returns the number of threads changed.
     */
    fun setPriorities(steamLow: Boolean, othersLow: Boolean): Int {
        var n = 0
        for (p in running()) {
            val low = if (isSteamProgram(p.name)) steamLow else othersLow
            val prio = if (low) android.os.Process.THREAD_PRIORITY_LOWEST else android.os.Process.THREAD_PRIORITY_DEFAULT
            File("/proc/${p.pid}/task").list()?.forEach { tid ->
                val t = tid.toIntOrNull() ?: return@forEach
                if (runCatching { android.os.Process.setThreadPriority(t, prio) }.isSuccess) n++
            }
        }
        return n
    }

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
