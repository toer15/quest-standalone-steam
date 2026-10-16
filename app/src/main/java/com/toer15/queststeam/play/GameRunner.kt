package com.toer15.queststeam.play

import android.content.Context
import com.toer15.queststeam.RuntimeActivity
import com.toer15.queststeam.VrBridge
import com.toer15.queststeam.display.BuiltInDisplay
import com.toer15.queststeam.display.Vortek
import com.toer15.queststeam.display.GameDisplay
import com.toer15.queststeam.display.NetworkInfo
import com.toer15.queststeam.display.PlayingService
import com.toer15.queststeam.gamepad.ControllerRelay
import com.toer15.queststeam.download.DownloadManager
import com.toer15.queststeam.gamepad.Controllers
import com.toer15.queststeam.gamepad.GamepadBridge
import com.toer15.queststeam.launcher.GameLauncher
import com.toer15.queststeam.launcher.LaunchResult
import com.toer15.queststeam.launcher.WineRegistry
import com.toer15.queststeam.launcher.RuntimeCore
import com.toer15.queststeam.launcher.RuntimeInstaller
import com.toer15.queststeam.launcher.XProbe
import com.winlator.alsaserver.ALSAClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** What the loading screen shows while a game starts. */
sealed interface LaunchStage {
    data object Idle : LaunchStage
    /** Loading: [step] says what's happening; [progress] 0..1 when known. */
    data class Working(
        val title: String, val step: String, val progress: Float? = null,
        /** The game's latest output lines, so you can see it's working (and what went wrong). */
        val detail: String? = null,
        /** After a while the game's display can be opened by hand while it loads. */
        val canShowWindow: Boolean = false,
    ) : LaunchStage
    /** The game's window is up: "Opening game now". */
    data class Opening(val title: String) : LaunchStage
    data class Running(val title: String, val vr: Boolean) : LaunchStage
    data class Failed(val title: String, val message: String) : LaunchStage
}

/**
 * Starts games, from the Runtime screen and from the VR panel alike: runtime download (first
 * time), controller bridge, VR bridge or flat mode, the game display, then the game's own .exe.
 * Play does not wait on Steam's window (see [DirectLaunch]): Steam stays silent in the background
 * when a game needs it, and the display switches as soon as the game has drawn.
 */
object GameRunner {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private var launcher: GameLauncher? = null

    private val _log = MutableStateFlow("")
    val log: StateFlow<String> = _log
    private val _stage = MutableStateFlow<LaunchStage>(LaunchStage.Idle)
    val stage: StateFlow<LaunchStage> = _stage
    /** A game can't start because the game display app isn't installed (shows the install popup). */
    private val _needsDisplay = MutableStateFlow(false)
    val needsDisplay: StateFlow<Boolean> = _needsDisplay
    fun dismissDisplayPrompt() { _needsDisplay.value = false }

    fun clearLog() { _log.value = ""; LogFile.clear() }

    @Volatile private var appCtx: Context? = null

    /**
     * Starts the log file for this run (see [LogFile]). If Android closed the app unexpectedly last
     * time (e.g. out of memory while a game loaded), the log shows that run's end and the reason.
     */
    fun initLog(ctx: Context) {
        appCtx = ctx.applicationContext
        LogFile.start(ctx)?.let { previous ->
            _log.value = previous
            LogFile.append(previous.trimEnd())
        }
    }

    /**
     * The log for pasting into a message. Messaging apps cut long texts, so the crash details come
     * first ("== Why it stopped =="), then the log without DXVK's long info lists.
     */
    fun logForSharing(): String {
        val head = "Quest Steam ${com.toer15.queststeam.BuildConfig.VERSION_NAME} log\n"
        val all = _log.value.trimEnd().lines()
        val crash = crashSummary(all)
        val compact = all.filter { keepInSharedLog(it) }
        val body = compact.joinToString("\n").takeLast(40_000)
        return head + (if (crash.isNotEmpty()) "== Why it stopped ==\n" + crash.joinToString("\n") + "\n\n== Log ==\n" else "") + body
    }

    /**
     * The shareable log cut into pieces that fit in one message each (split at line ends), each
     * starting with "(part i/n)".
     */
    fun logParts(maxChars: Int = 6_000): List<String> {
        val parts = mutableListOf(StringBuilder())
        for (line in logForSharing().lines()) {
            val piece = line.take(maxChars - 20)
            if (parts.last().length + piece.length + 1 > maxChars - 20) parts += StringBuilder()
            parts.last().append(piece).append('\n')
        }
        val n = parts.size
        return parts.mapIndexed { i, sb -> "(part ${i + 1}/$n)\n" + sb.toString().trimEnd() }
    }

    /** For the shared .txt file: the crash summary first, then the complete log. */
    fun fullLogForFile(): String {
        val all = _log.value.trimEnd().lines()
        val crash = crashSummary(all)
        return "Quest Steam ${com.toer15.queststeam.BuildConfig.VERSION_NAME} log\n" +
            (if (crash.isNotEmpty()) "== Why it stopped ==\n" + crash.joinToString("\n") + "\n\n" else "") +
            "== Complete log ==\n" + _log.value
    }

    /** The app's crash sections ([crash], [game log], [DXVK log]) plus Wine/DXVK errors, newest last. */
    fun crashSummary(all: List<String>): List<String> {
        // Only the last game start: the log can hold several runs.
        val runStart = all.indexOfLast { it.startsWith("$ ") }.coerceAtLeast(0)
        val run = all.subList(runStart, all.size)
        val section = Regex("^\\[(crash|game log|DXVK log|windows|processes|steam logs)")
        val firstSection = run.indexOfFirst { section.containsMatchIn(it) }.let { if (it < 0) run.size else it }
        val key = Regex("Unhandled|page fault|^err:|^\\w{4}:err:(?!winediag:getaddrinfo|wineboot|nsi)|exited with code|Couldn't|Failed to|^Vortek:|^Sound:|^GPU:|^Headset Vulkan|game is drawing|Game window is open|\\[waiting|^  +0x|^  running:|^\\[steam")
        val out = run.subList(0, firstSection).filter { key.containsMatchIn(it) }.takeLast(30).toMutableList()
        if (firstSection < run.size) out += run.subList(firstSection, run.size).filter { !it.startsWith("[android log]") && !it.startsWith("  D/vulkan") }.take(110)
        return out
    }

    /** Drops DXVK's long info lists (memory types, extensions, features) and Vulkan loader chatter. */
    private fun keepInSharedLog(line: String): Boolean {
        if (line.startsWith("  D/vulkan")) return false
        if (!line.startsWith("info:")) return true
        return Regex("Game:|DXVK: v|config|Device :|Driver :|feature level|Feature level|Vulkan: Found|pipeline|Creating|Swap|Present", RegexOption.IGNORE_CASE).containsMatchIn(line)
    }

    private val important = Regex("err:|warn:|Found device|Driver:|error|fail|DXVK|Vortek|Vulkan|exited with code|D3D|wine:|X Error|\\[android log\\]|\\b[EF]/[A-Za-z]", RegexOption.IGNORE_CASE)

    /** The log lines that explain a failure (Wine/DXVK errors, GPU driver, exit code), newest last. */
    fun importantLines(log: String, max: Int = 18): List<String> {
        val all = log.trimEnd().lines()
        // Sections the app adds after a game ends are already filtered: keep them whole.
        val section = Regex("^\\[(crash|game log|DXVK log)")
        val start = all.indexOfFirst { section.containsMatchIn(it) }
        if (start >= 0 && max > 18) {
            val before = all.subList(0, start).filter { important.containsMatchIn(it) && !it.contains("I/Adreno") && !it.startsWith("  D/vulkan") }
            val after = all.subList(start, all.size).filter { !it.startsWith("[android log]") && !it.startsWith("  D/vulkan") }
            return before.takeLast(maxOf(10, max - after.size)) + after
        }
        val picked = all.filter { important.containsMatchIn(it) && !it.contains("I/Adreno") }
        return (if (picked.size >= 3) picked else all).takeLast(max)
    }

    fun appendLog(line: String) {
        val s = _log.value + line + "\n"
        _log.value = if (s.length > 120_000) s.takeLast(100_000) else s
        LogFile.append(line)
    }

    /** Before a game starts: the app gives back memory it can (its cache of game pictures). */
    private fun trimAppMemory(ctx: Context, collect: Boolean = true) {
        runCatching { coil.Coil.imageLoader(ctx).memoryCache?.clear() }
        // A full collection stalls the start. Games skip it; the display is already up.
        if (collect) System.gc()
    }

    /** Memory the system could give out right now, in MB. */
    private fun freeMemoryMb(): Long {
        val am = appCtx?.getSystemService(android.app.ActivityManager::class.java) ?: return Long.MAX_VALUE
        return android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.availMem / 1_048_576
    }

    /** Free memory, and what the Windows programs use (largest first), for the log. */
    private fun memoryNote(): String {
        val ctx = appCtx ?: return ""
        val am = ctx.getSystemService(android.app.ActivityManager::class.java) ?: return ""
        val mi = android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val procs = launcher?.running().orEmpty()
        val top = procs.groupBy { it.name }.map { (n, l) -> Triple(n, l.size, l.sumOf { it.rssKb } / 1024) }
            .sortedByDescending { it.third }.take(4)
        return "memory: ${mi.availMem / 1_048_576} MB free of ${mi.totalMem / 1_048_576} MB" + (if (mi.lowMemory) " (LOW)" else "") +
            "; Windows programs use ${procs.sumOf { it.rssKb } / 1024} MB (" +
            top.joinToString { (n, c, mb) -> "$n${if (c > 1) " ×$c" else ""} $mb MB" } + ")"
    }

    /** Background-thread errors the app caught since the game started (e.g. in the game display's renderer). */
    @Volatile private var errorsMark = 0L

    private fun markBackgroundErrors() {
        errorsMark = appCtx?.let { File(it.filesDir, "errors.log").length() } ?: 0L
    }

    private fun appendBackgroundErrors() {
        val f = appCtx?.let { File(it.filesDir, "errors.log") } ?: return
        if (!f.isFile || f.length() <= errorsMark) return
        val text = runCatching {
            java.io.RandomAccessFile(f, "r").use { raf ->
                raf.seek(errorsMark.coerceAtMost(raf.length()))
                ByteArray((raf.length() - raf.filePointer).coerceAtMost(20_000).toInt()).also { raf.readFully(it) }.let { String(it) }
            }
        }.getOrNull() ?: return
        errorsMark = f.length()
        appendLog("[app errors]")
        text.lines().filter { it.isNotBlank() }.take(40).forEach { appendLog("  " + it.take(240)) }
    }

    val isRunning get() = launcher?.current?.isAlive == true

    /** A game or Steam is on screen or starting (keys and the controller go to it). */
    val isPlaying get() = isRunning && _stage.value.let { it is LaunchStage.Running || it is LaunchStage.Working || it is LaunchStage.Opening }

    /**
     * Steam keeps running between games, like on a PC. Once Steam runs on the built-in display, its
     * Wine session (display, wineserver, Steam) stays up when you leave Steam's screen or a game ends,
     * and the next game starts inside it: no Steam restart, no sign-in wait. This is the session's
     * environment (what games started in it get); null when Steam isn't running.
     */
    @Volatile private var steamEnv: Map<String, String>? = null

    /** The game started inside Steam's session ("REPO.exe"), while it runs. */
    @Volatile private var sessionGame: String? = null

    /**
     * Counts session starts and stops. A session's exit callback only acts while its own start is the
     * latest: a Steam replaced by a new start (or stopped) ends later, and mustn't take the new one down.
     */
    @Volatile private var launchGen = 0

    /** Names the extra Wine desktops of programs started into a running session ("game1", "steam2"…). */
    private var desktops = 0
    private fun nextDesktop(prefix: String) = prefix + (++desktops)

    /**
     * CPU priorities while Steam's session runs: with a game starting or on screen, Steam's programs
     * get the lowest priority (and the small CPU cores) so the game gets the rest; with you in the
     * app, everything Wine runs does, so the app stays smooth; with Steam's own window on screen,
     * all normal. Re-applied every few seconds: Steam starts new helper processes now and then.
     */
    private var priorityJob: Job? = null
    @Volatile private var lowPriority = false

    private enum class Priority { Normal, SteamLow, AllLow }

    private fun priorityNow(): Priority = when (_stage.value) {
        // A game's screen is up before the game runs (see DirectLaunch): until it does (Steam still signing in, or
        // starting the game itself), Steam gets full speed, as the game waits for it.
        is LaunchStage.Running, is LaunchStage.Working, is LaunchStage.Opening -> if (sessionGame != null) Priority.SteamLow else Priority.Normal
        // In the app: a game getting ready in the background (see getLastGameReady) or waiting paused
        // keeps its priority, Steam's the lowest; a Steam that's still signing in gets full speed (it's
        // ready sooner; its browser waits paused afterwards); otherwise everything the lowest priority.
        else -> if (sessionGame != null) Priority.SteamLow else if (!steamSignedIn) Priority.Normal else Priority.AllLow
    }

    private fun watchPriority() {
        if (priorityJob?.isActive == true) return
        priorityJob = scope.launch {
            var last = Priority.Normal
            while (steamRunning()) {
                val now = priorityNow()
                if (now != Priority.Normal || last != Priority.Normal) {
                    withContext(Dispatchers.IO) { launcher?.setPriorities(steamLow = now != Priority.Normal, othersLow = now == Priority.AllLow) }
                }
                last = now
                lowPriority = now != Priority.Normal
                withContext(Dispatchers.IO) { applySteamUiPolicy() }
                delay(4000)
            }
            lowPriority = false
        }
    }

    /** Before a game or Steam's window comes on screen: their priorities now, not in a few seconds. */
    private suspend fun foreground(steamLow: Boolean) {
        if (!lowPriority && !steamLow) return
        withContext(Dispatchers.IO) { launcher?.setPriorities(steamLow = steamLow, othersLow = false) }
    }

    /** Steam's session is up: Steam's desktop process (the session's main process) is alive. */
    fun steamRunning(): Boolean = steamEnv != null && BuiltInDisplay.isRunning && isRunning

    /**
     * A game needs Steam itself (steam.exe), not Steam's built-in browser (steamwebhelper.exe: its
     * windows, store and friends), which is most of Steam's memory and CPU. With Light Steam the browser
     * runs only while Steam's window is on screen (and until Steam has signed in); otherwise it waits
     * paused: no CPU, and Android can compress its memory for the game. Games keep working with it
     * paused (R.E.P.O. signed in to Steamworks that way). (Box64's "on-demand Steam UI" closes it the
     * same way for Linux Steam.)
     */
    @Volatile private var steamUiPaused = false
    private var memoryJob: Job? = null

    /** Steam's own window (sign-in, library) is what's on screen, or loading to be. */
    private fun steamOnScreen(): Boolean = _stage.value.let {
        (it is LaunchStage.Running && it.title.startsWith("Steam")) ||
            (it is LaunchStage.Working && it.title.startsWith("Steam")) || (it is LaunchStage.Opening && it.title.startsWith("Steam"))
    }

    /**
     * While a game is the thing on screen, Steam's browser (the process that draws Steam's windows)
     * is not drawn and doesn't run. Steam itself stays up for the game. Show Steam turns this off.
     */
    private fun gameFillsDisplay(game: Boolean) {
        val renderer = BuiltInDisplay.view?.renderer ?: return
        if (game) renderer.setUnviewableWMClasses("explorer.exe", SteamWindows.WEB_HELPER)
        else renderer.setUnviewableWMClasses("explorer.exe")
    }

    /** Pauses or resumes Steam's browser as described at [steamUiPaused]. Re-applied every few seconds (Steam starts browser helpers now and then). */
    private fun applySteamUiPolicy() {
        val ctx = appCtx ?: return
        val l = launcher ?: return
        if (!steamRunning()) return
        if (!steamSignedIn) checkSteamSignIn(l)
        // Steam is starting a game itself (see watchApplaunch): its windows stay off the game screen, and its
        // browser keeps running until the game is up (Steam may need it to start the game).
        if (applaunchGame != null && sessionGame == null) { gameFillsDisplay(true); return }
        // A game is up: its window fills the display, even if Steam hasn't finished signing in.
        if (sessionGame != null) {
            gameFillsDisplay(true)
            val n = l.pause(SteamWindows.WEB_HELPER, true)
            if (n > 0 && !steamUiPaused) {
                steamUiPaused = true
                appendLog("Steam's windows stay off this screen. Only the game is shown.")
            }
            return
        }
        if (!SteamWindows.light(ctx) || !steamSignedIn || steamOnScreen()) { resumeSteamUi(); return }
        val n = l.pause(SteamWindows.WEB_HELPER, true)
        if (n > 0 && !steamUiPaused) {
            steamUiPaused = true
            appendLog("Steam's windows wait paused while they're not on screen (Steam stays signed in and runs games; Show Steam brings them back).")
        }
    }

    /** While the game [title] runs in Steam's session: a note in the log each minute (free memory, what Steam and the game use). */
    private fun watchMemory(title: String) {
        if (memoryJob?.isActive == true) return
        memoryJob = scope.launch {
            var lastNote = System.currentTimeMillis()
            while (sessionGame != null && steamRunning()) {
                if (System.currentTimeMillis() - lastNote >= 60_000 && _stage.value is LaunchStage.Running) {
                    lastNote = System.currentTimeMillis()
                    appendLog("[$title] " + withContext(Dispatchers.IO) { memoryNote() })
                }
                delay(1000)
            }
        }
    }

    /** Steam's browser runs again (see [steamUiPaused]). */
    private fun resumeSteamUi() {
        if (!steamUiPaused) return
        steamUiPaused = false
        if ((launcher?.pause(SteamWindows.WEB_HELPER, false) ?: 0) > 0) appendLog("Steam's windows are running again.")
    }

    /** Steam's session: where its connection log ended when it started, when it started, and whether it has signed in since. */
    @Volatile private var steamLogOffset = 0L
    @Volatile private var steamStartedAt = 0L
    @Volatile private var steamSignedIn = false

    /**
     * The running Steam session has no browser (see [SteamWindows.coreArgs]): faster to sign in and light on
     * memory, but no Steam window. [coreTrial]: this start is the one-time test of whether Steam signs in
     * that way on this headset.
     */
    @Volatile private var steamCoreOnly = false
    @Volatile private var coreTrial = false

    /** Steam is being restarted with its browser (after the test failed): a Play waiting for Steam keeps waiting through the restart. */
    @Volatile private var steamRestarting = false

    /** True once Steam's session has signed in (it says so in its connection log); logs it the first time. */
    private fun checkSteamSignIn(l: GameLauncher): Boolean {
        if (steamSignedIn) return true
        if (!SteamWindows.loggedOnSince(l, steamLogOffset)) return false
        synchronized(this) {
            if (!steamSignedIn) {
                steamSignedIn = true
                val used = runCatching { l.processes().filter { it.startsWith("steam") }.joinToString(", ") }.getOrDefault("")
                val took = (System.currentTimeMillis() - steamStartedAt) / 1000
                appCtx?.let { SteamWindows.setLastSignInS(it, steamCoreOnly, took) }
                appendLog(
                    "Steam is signed in (${took}s after it started${if (steamCoreOnly) ", without its browser" else ""})." +
                        if (used.isNotEmpty()) " Processor time so far: $used." else ""
                )
            }
        }
        return true
    }

    /** When Play was pressed, for "on screen N s after Play" (the start-time goal); 0 once reported. */
    @Volatile private var playPressedAt = 0L
    /** When the game's own process was started (for "before the game started" / "the game itself" in the start-time line). */
    @Volatile private var gameLaunchedAt = 0L
    /**
     * When the game came on screen (its first frame, or Play on a game that was waiting): how long it was played, for
     * [readyAgain]; 0 while it hasn't drawn yet (a game that closes then didn't start).
     */
    @Volatile private var gameShownAt = 0L
    /** Counts Plays (and leaving): the game screen's watcher of an earlier Play stops ([watchOnScreen]). */
    @Volatile private var playGen = 0
    /** Counts the session's game starts: the exit watcher of an earlier start of the same game stops ([watchGameExit]). */
    @Volatile private var gameGen = 0
    /** The game Steam was asked to start itself (-applaunch) that hasn't shown up yet (see [watchApplaunch]). */
    @Volatile private var applaunchGame: String? = null
    private var warmJob: Job? = null
    /** Prepares the built-in display only. Never starts steam.exe. */
    private var displayJob: Job? = null

    /**
     * Starts Steam in the background on the built-in display (-silent: no window), as the session's
     * main program, so it stays for the next games. Games started in it get [env] (see [steamEnv]).
     * With [core], Steam runs without its browser (see [SteamWindows.coreArgs]). Returns false if Steam
     * didn't start.
     */
    private suspend fun launchSteamSession(ctx: Context, l: GameLauncher, env: Map<String, String>, gen: Int, core: Boolean = false): Boolean {
        steamLogOffset = withContext(Dispatchers.IO) { SteamWindows.connectionLog(l).let { if (it.isFile) it.length() else 0L } }
        steamSignedIn = false
        var steamLines = 0
        val onSteamLine: (String) -> Unit = { line ->
            val keep = (line.contains("err:") || line.startsWith("wine:") || line.contains("Box64", ignoreCase = true)) && !line.contains("fixme")
            if (steamLines < 60 && keep) { steamLines++; appendLog("[steam] $line") }
        }
        val steam = withContext(Dispatchers.IO) {
            l.launchExe(
                SteamWindows.exe(l), BuiltInDisplay.DISPLAY, env + SteamWindows.steamOnlyEnv(ctx, l),
                listOf("-silent") + (if (core) SteamWindows.coreArgs() else SteamWindows.args(ctx)),
                desktop = BuiltInDisplay.SCREEN, onExit = { code -> if (gen == launchGen) onGameExit(ctx, "Steam", code) }, onLine = onSteamLine,
            )
        }
        if (!steam.ok) { appendLog("Steam didn't start: ${steam.message}"); return false }
        steamStartedAt = System.currentTimeMillis()
        steamCoreOnly = core
        steamEnv = env.toMap()
        watchPriority()
        appendLog("Steam for Windows is starting in the background${if (core) " without its browser" else ""}…")
        return true
    }

    /**
     * Waits until Steam's session has signed in (at most 4 minutes): while Steam gets ready in the background,
     * and for Play on a game while Steam is still signing in (the game's screen is up meanwhile, see [startInSteam]).
     * Returns false if Steam closed or didn't sign in in time.
     */
    private suspend fun awaitSteamSignIn(l: GameLauncher): Boolean {
        val t0 = System.currentTimeMillis()
        while (!steamSignedIn) {
            if (withContext(Dispatchers.IO) { checkSteamSignIn(l) }) break
            // (A restart of Steam with its browser, after the test failed, is not Steam closing.)
            if (!steamRunning() && !steamRestarting) { appendLog("Steam closed while starting."); return false }
            if (System.currentTimeMillis() - t0 >= STEAM_SIGN_IN_LIMIT_S * 1000L) { appendLog("Steam didn't sign in within ${STEAM_SIGN_IN_LIMIT_S / 60} minutes."); return false }
            delay(1000)
        }
        withContext(Dispatchers.IO) { applySteamUiPolicy() }
        return true
    }

    /**
     * Waits for Steam, started without its browser, to sign in: true once it has. False when Steam hasn't even
     * tried to connect after [silentS] seconds (its connection log stays silent: nothing tells it to sign in
     * without the browser), or hasn't signed in after [limitS] seconds, or closes.
     */
    private suspend fun awaitCoreSignIn(l: GameLauncher, gen: Int, silentS: Int, limitS: Int): Boolean {
        val t0 = System.currentTimeMillis()
        while (gen == launchGen) {
            if (withContext(Dispatchers.IO) { checkSteamSignIn(l) }) return true
            if (!steamRunning()) { appendLog("Steam closed while starting without its browser."); return false }
            val secs = (System.currentTimeMillis() - t0) / 1000
            val tried = withContext(Dispatchers.IO) { SteamWindows.connectionLog(l).let { it.isFile && it.length() > steamLogOffset } }
            if (secs >= silentS && !tried) { appendLog("Steam hasn't tried to connect after ${secs}s without its browser."); return false }
            if (secs >= limitS) { appendLog("Steam hasn't signed in after ${secs}s without its browser."); return false }
            delay(1000)
        }
        return false
    }

    /**
     * Gets Steam ready in the background when the app opens (Runtime settings → Steam for Windows →
     * "Get Steam ready when the app opens"), so it's signed in by the time you press Play and the game
     * starts at once, instead of after a minute or two of Steam starting first. Steam's windows then
     * wait paused (see [steamUiPaused]) and everything Wine runs has the lowest priority while you're
     * in the app.
     */
    fun warmUpSteam(ctx: Context) {
        val app = ctx.applicationContext
        if (!SteamWindows.warmUp(app) || !SteamWindows.startWithGames(app)) return
        if (isRunning || warmJob?.isActive == true || job?.isActive == true || _stage.value !is LaunchStage.Idle) return
        if (!BuiltInDisplay.isBuiltIn(display(app)) || !RuntimeInstaller(app).isInstalled) return
        val l = launcher ?: GameLauncher(app).also { launcher = it }
        if (!SteamWindows.isInstalled(l) || !SteamWindows.isSignedIn(l)) return
        warmJob = scope.launch {
            try {
                startSteamInBackground(app, l)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                appendLog("Couldn't get Steam ready: $e")
            }
        }
    }

    /**
     * Prepares the runtime when the app opens so Play only has to start the chosen game.
     * Does not start the game display here: that view has to be created while the game screen
     * is open, or the game has nothing to draw into. Does not start Wine or steam.exe.
     */
    fun warmDisplay(ctx: Context) {
        val app = ctx.applicationContext
        if (displayJob?.isActive == true) return
        if (job?.isActive == true || _stage.value !is LaunchStage.Idle) return
        if (!BuiltInDisplay.isBuiltIn(display(app)) || !RuntimeInstaller(app).isInstalled) return
        val l = launcher ?: GameLauncher(app).also { launcher = it }
        displayJob = scope.launch {
            try {
                prepareRuntime(app, l, gamesOnly = true)
                if (job?.isActive == true || _stage.value !is LaunchStage.Idle) return@launch
                if (Vortek.selected(app) == Vortek.VORTEK) {
                    withContext(Dispatchers.IO) {
                        runCatching { Vortek.prepare(app, l.rootfs, ::appendLog) }
                            .onFailure { appendLog("Vortek GPU driver setup failed: ${it.message}") }
                    }
                }
                appendLog("Ready to open a game. Play starts only the game you choose, on its own display.")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                appendLog("Couldn't get ready to open a game: $e")
            }
        }
    }

    /**
     * [core]: without Steam's browser (the default once that has worked, or while it's untested; never after
     * it didn't sign in). The first time is a test: if Steam doesn't sign in that way, it starts again with
     * its browser, as it always did, and the result is remembered.
     */
    private suspend fun startSteamInBackground(ctx: Context, l: GameLauncher, core: Boolean = SteamWindows.coreAllowed(ctx) && SteamWindows.coreWorks(ctx) != false) {
        val gen = ++launchGen
        steamEnv = null
        sessionGame = null
        gameStartedAt = System.currentTimeMillis()
        markBackgroundErrors()
        appendLog("Getting Steam ready in the background, so games start right away (Runtime settings → Steam for Windows).")
        prepareRuntime(ctx, l)
        if (gen != launchGen) return // a game was started meanwhile
        val env = mutableMapOf<String, String>()
        env.putAll(GameSpeed.env(ctx, l))
        withContext(Dispatchers.IO) {
            runCatching { env["QSS_PAD_SHM"] = GamepadBridge.prepare(ctx, l.rootfs, ::appendLog).path }
                .onFailure { appendLog("Controller bridge unavailable: $it") }
        }
        env["WINEDLLOVERRIDES"] = (l.environment(null)["WINEDLLOVERRIDES"] ?: "") + ";" + SteamWindows.DLL_OVERRIDES
        val useVortek = Vortek.selected(ctx) == Vortek.VORTEK && withContext(Dispatchers.IO) {
            runCatching { Vortek.prepare(ctx, l.rootfs, ::appendLog) }
                .onFailure { appendLog("Vortek GPU driver setup failed: ${it.message}") }.getOrDefault(false)
        }
        if (gen != launchGen) return
        BuiltInDisplay.expectWindow(null, SteamWindows.UI_WINDOW_CLASS)
        env.putAll(BuiltInDisplay.start(ctx, l.rootfs, useVortek, ::appendLog))
        setWineAudio(l, env.containsKey("ANDROID_ALSA_SERVER"))
        appendLog("Game speed: ${GameSpeed.describe(ctx)}.")
        val launched = launchSteamSession(ctx, l, env, gen, core)
        steamRestarting = false // the restart (if this is one) has started, or failed to
        if (!launched) { BuiltInDisplay.stop(); return }
        PlayingService.start(ctx, "Steam")
        if (core) {
            // Untested: the one-time test. Tested before: the same wait, with a way back if it doesn't sign in this time.
            val tested = SteamWindows.coreWorks(ctx) == true
            if (!tested) appendLog("Testing Steam without its browser (most of Steam's start time and memory): if it doesn't sign in, Steam starts with its browser as before.")
            coreTrial = !tested
            val ok = try {
                if (tested) awaitCoreSignIn(l, gen, CORE_KNOWN_SILENT_S, CORE_KNOWN_S) else awaitCoreSignIn(l, gen, CORE_SILENT_S, CORE_TRIAL_S)
            } finally { coreTrial = false }
            if (gen != launchGen) return // Play or Stop took over meanwhile
            if (ok) {
                if (!tested) {
                    SteamWindows.setCoreWorks(ctx, true)
                    appendLog("✓ Steam signs in without its browser: it starts that way from now on.")
                }
                getLastGameReady(ctx, l)
                return
            }
            if (tested) {
                SteamWindows.forgetCore(ctx)
                appendLog("Steam didn't sign in without its browser this time: starting it with its browser (the next start tests again).")
            } else {
                SteamWindows.setCoreWorks(ctx, false)
                appendLog("Steam doesn't sign in without its browser on this headset: starting it with its browser as before (remembered; Runtime settings → Steam for Windows tests again).")
            }
            steamRestarting = true // a Play waiting for Steam keeps waiting through the restart
            startSteamInBackground(ctx, l, core = false)
            return
        }
        if (awaitSteamSignIn(l)) getLastGameReady(ctx, l)
    }

    /**
     * Play pressed while Steam is being tested without its browser and hasn't even tried to connect: not worth
     * waiting for. The test is dropped (it stays untested for the next start) and Play starts Steam as it always did.
     */
    private suspend fun abandonCoreTrial(l: GameLauncher) {
        coreTrial = false
        steamRestarting = false
        warmJob?.cancelAndJoin()
        ++launchGen
        steamEnv = null
        sessionGame = null
        steamSignedIn = false
        steamCoreOnly = false
        withContext(Dispatchers.IO) { l.stop() }
        BuiltInDisplay.stop()
        appendLog("Play was pressed during the test of Steam without its browser: Steam starts as usual now, and the test runs again next time.")
    }

    /** How big the caches that make later starts faster are, for the log (they should grow, then stay). */
    private fun cacheNote(ctx: Context, l: GameLauncher): String {
        fun size(dir: File) = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        val box = size(File(l.rootfs, "home/xuser/.cache/box64")) / 1_048_576
        val vortek = size(File(ctx.cacheDir, "vortek")) / 1_048_576
        val dxvk = size(File(l.rootfs, "home/xuser/.cache/dxvk")) / 1024
        return "Caches for faster starts: Box64 translations $box MB, Vortek textures $vortek MB, DXVK pipelines $dxvk KB."
    }

    /** The start-time goal: logs (and shows on the game screen) how long Play took to put [title] on screen. */
    private fun noteOnScreen(title: String) {
        val pressed = playPressedAt
        if (pressed == 0L) return
        playPressedAt = 0L
        val now = System.currentTimeMillis()
        val secs = (now - pressed) / 1000
        // How much of it was waiting before the game even started (Steam's sign-in), and how much the game itself took.
        val launched = gameLaunchedAt
        val split = if (launched in pressed..now) "${(launched - pressed) / 1000}s before the game started, the game itself ${(now - launched) / 1000}s; " else ""
        appendLog("⏱ $title is on screen ${secs}s after Play (${split}goal: a few seconds).")
        BuiltInDisplay.showStartTime("on screen ${secs}s after Play")
    }

    /** The display games use (Runtime screen setting; default = the built-in game display). */
    fun display(ctx: Context): String? =
        (ctx.getSharedPreferences("runtime", 0).getString("display", BuiltInDisplay.DISPLAY) ?: BuiltInDisplay.DISPLAY).trim().ifBlank { null }

    @Volatile private var showNow = false

    /** "Show game window" on the loading screen: switch to the game right away. */
    fun showGameNow(ctx: Context) {
        if (BuiltInDisplay.isRunning) showNow = true else GameDisplay.show(ctx)
    }

    fun dismissFailure() { if (_stage.value is LaunchStage.Failed) _stage.value = LaunchStage.Idle }

    /** Installs Steam for Windows into the runtime from Valve's installer (silent install). */
    fun installSteam(ctx: Context) {
        if (job?.isActive == true) return
        val app = ctx.applicationContext
        val title = "Steam for Windows"
        job = scope.launch {
            try {
                val installer = RuntimeInstaller(app)
                if (!installer.isInstalled) {
                    _stage.value = LaunchStage.Failed(title, "Download the Windows runtime first (Runtime settings → Download runtime).")
                    return@launch
                }
                val l = launcher ?: GameLauncher(app).also { launcher = it }
                withContext(Dispatchers.IO) { l.killLeftovers(); NetworkInfo.write(app, l.rootfs) }
                val setup = File(l.rootfs, "tmp/SteamSetup.exe")
                _stage.value = LaunchStage.Working(title, "Downloading Steam's installer…", 0f)
                withContext(Dispatchers.IO) {
                    SteamWindows.downloadSetup(setup) { p -> _stage.value = LaunchStage.Working(title, "Downloading Steam's installer…", p) }
                }
                appendLog("Downloaded SteamSetup.exe (${setup.length() / 1024} KB).")
                _stage.value = LaunchStage.Working(title, "Installing Steam…", detail = "Valve's installer runs silently inside the Windows runtime.")
                val env = BuiltInDisplay.start(app, l.rootfs, false, ::appendLog).toMutableMap()
                val done = kotlinx.coroutines.CompletableDeferred<Int>()
                val r = withContext(Dispatchers.IO) {
                    l.runWine(listOf(setup.path, "/S"), BuiltInDisplay.DISPLAY, setup.parentFile, env, onExit = { done.complete(it) }, onLine = ::appendLog)
                }
                if (!r.ok) throw IllegalStateException(r.message)
                val code = kotlinx.coroutines.withTimeoutOrNull(15 * 60_000L) { done.await() }
                withContext(Dispatchers.IO) { l.killLeftovers() }
                BuiltInDisplay.stop()
                setup.delete()
                if (SteamWindows.isInstalled(l)) {
                    appendLog("Steam for Windows is installed.")
                    if (ensureSteamClient(l, title)) appendLog("Steam is ready. Next: Open Steam and sign in once.")
                    _stage.value = LaunchStage.Idle
                } else {
                    val why = if (code == null) "the installer didn't finish in 15 minutes" else "the installer ended (code $code) without steam.exe"
                    appendLog("Steam install failed: $why.")
                    appendLog("Steam folder: ${SteamWindows.describeDir(l)}")
                    _stage.value = LaunchStage.Failed(title, "Steam couldn't be installed: $why.")
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                appendLog("Steam install failed: $e")
                BuiltInDisplay.stop()
                _stage.value = LaunchStage.Failed(title, e.message ?: e.toString())
            }
        }
    }

    /** While Steam runs in the game display, tells when the player has signed in. */
    private fun watchSteamSignIn(l: GameLauncher) {
        if (SteamWindows.isSignedIn(l)) return
        val offset = SteamWindows.connectionLog(l).let { if (it.isFile) it.length() else 0L }
        scope.launch {
            while (isRunning) {
                if (withContext(Dispatchers.IO) { SteamWindows.loggedOnSince(l, offset) || SteamWindows.isSignedIn(l) }) {
                    appendLog("✓ Signed in to Steam. Leave Steam (✕ Leave game) and press Play on your game.")
                    return@launch
                }
                delay(3000)
            }
        }
    }

    /**
     * Downloads and unpacks the Steam client through the app (fast) when Steam's folder only has the
     * small starter. Returns false (and logs why) if that fails; Steam then updates itself as before.
     */
    private suspend fun ensureSteamClient(l: GameLauncher, title: String): Boolean {
        val dir = SteamWindows.dir(l)
        if (SteamClientDownload.isBootstrapped(dir)) return true
        _stage.value = LaunchStage.Working(title, "Downloading Steam…", 0f, detail = "The app downloads Steam's files itself (much faster than Steam's own updater).")
        return try {
            var last = 0L
            withContext(Dispatchers.IO) {
                SteamClientDownload.install(dir, ::appendLog) { done, total, what ->
                    val now = System.currentTimeMillis()
                    if (now - last < 250 && done < total) return@install
                    last = now
                    val mb = "${done / 1_000_000} of ${total / 1_000_000} MB"
                    _stage.value = LaunchStage.Working(
                        title, if (what.startsWith("Downloading")) "$what… $mb" else "$what…",
                        progress = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else null,
                        detail = "The app downloads Steam's files itself (much faster than Steam's own updater).",
                    )
                }
            }
            true
        } catch (e: Exception) {
            appendLog("Couldn't download Steam through the app (${e.message}); Steam will update itself instead (slower).")
            false
        }
    }

    /** Opens Steam for Windows in the game display (first start updates it; then the player signs in). */
    fun openSteam(ctx: Context) {
        val l = launcher ?: GameLauncher(ctx.applicationContext).also { launcher = it }
        if (!SteamWindows.isInstalled(l)) { installSteam(ctx); return }
        if (steamRunning() && sessionGame == null && !steamCoreOnly) { showSteam(ctx); return }
        if (job?.isActive == true) return
        val app = ctx.applicationContext
        if (SteamClientDownload.isBootstrapped(SteamWindows.dir(l))) {
            play(ctx, SteamWindows.exe(l), SteamWindows.args(ctx).joinToString(" "), RuntimeActivity.MODE_FLAT, "Steam")
            return
        }
        job = scope.launch {
            ensureSteamClient(l, "Steam")
            _stage.value = LaunchStage.Idle
            play(app, SteamWindows.exe(l), SteamWindows.args(app).joinToString(" "), RuntimeActivity.MODE_FLAT, "Steam")
        }
    }

    /** No .exe on the headset: say so, instead of opening Steam or an empty runtime screen. */
    fun missingGame(title: String, exe: File? = null) {
        val where = exe?.path?.let { " ($it)" }.orEmpty()
        appendLog("No game executable for $title$where.")
        _stage.value = LaunchStage.Failed(
            title,
            "Can't find this game's .exe on the headset. Download or import it, then press Play again.",
        )
    }

    fun play(ctx: Context, exe: File, args: String, mode: String, title: String, appId: Int = 0) {
        val busy = _stage.value is LaunchStage.Working || _stage.value is LaunchStage.Opening
        if (busy && job?.isActive == true) return
        val openingSteam = SteamWindows.isSteam(exe)
        if (!openingSteam && !exe.isFile) {
            missingGame(title, exe)
            return
        }
        playPressedAt = System.currentTimeMillis()
        playGen++
        // A Steam that's running (or still signing in) stays, and the game starts in it: its background start keeps
        // going (it may still be testing Steam without its browser). Only one that hasn't started Steam yet stops.
        if (!steamRunning()) warmJob?.cancel()
        val app = ctx.applicationContext
        _stage.value = LaunchStage.Working(
            title,
            if (openingSteam) "Starting Steam…" else DirectLaunch.launching(title),
        )
        job = scope.launch {
            try {
                val installer = RuntimeInstaller(app)
                if (!installer.isInstalled) {
                    _stage.value = LaunchStage.Working(title, DirectLaunch.PREPARING)
                    appendLog("First launch: downloading the Windows runtime (Box64 + Wine, ~157 MB)…")
                    withContext(Dispatchers.IO) {
                        installer.install(::appendLog) { p -> _stage.value = LaunchStage.Working(title, "Preparing runtime… ${p.stage}", p.fraction) }
                    }
                    appendLog("Runtime downloaded and installed.")
                    if (!openingSteam) _stage.value = LaunchStage.Working(title, DirectLaunch.launching(title))
                }
                val l = launcher ?: GameLauncher(app).also { launcher = it }
                if (coreTrial) {
                    // Play during the test. A Steam that is already connecting without its browser is on its way: it is kept (its
                    // head start counts) and waited for like any Steam. One that hasn't tried to connect isn't: the test is dropped.
                    val connecting = withContext(Dispatchers.IO) { SteamWindows.connectionLog(l).let { it.isFile && it.length() > steamLogOffset } }
                    if (connecting) appendLog("Play was pressed while Steam is starting without its browser; it is connecting, so it's kept for the game.")
                    else abandonCoreTrial(l)
                }
                // A game getting ready in the background stops waiting: it's shown if it's this one, else closed.
                readyJob?.cancelAndJoin()
                val vrGame = mode == RuntimeActivity.MODE_VR && VrBridge.isVrGame(gameRoot(l, exe))
                paused?.let { p ->
                    val alive = withContext(Dispatchers.IO) { l.isRunning(p.exe) } && (if (p.inSteam) steamRunning() else isRunning)
                    if (alive && !vrGame && p.exe.equals(exe.name, ignoreCase = true)) { resumeGame(app, p, l); return@launch }
                    closePaused(app)
                }
                readying?.let { r ->
                    val alive = steamRunning() && withContext(Dispatchers.IO) { l.isRunning(r.exe) }
                    if (alive && !vrGame && r.exe.equals(exe.name, ignoreCase = true)) { showReadying(app, l, r); return@launch }
                    closeReadying()
                }
                if (!openingSteam) {
                    displayJob?.let { if (it.isActive) it.join() }
                    val plan = DirectLaunch.plan(
                        title, openingSteam = false, runtimeInstalled = true, gameFileExists = true,
                        appId = appId, steamInstalled = SteamWindows.isInstalled(l), steamRunning = !vrGame && steamRunning(),
                    )
                    appendLog(
                        when (plan.steam) {
                            DirectLaunch.SteamRole.BESIDE_STEAM -> "Play: $title starts next to the Steam that's already running (Steam doesn't start again)."
                            DirectLaunch.SteamRole.APPLAUNCH -> "Play: Steam applaunch $appId ($title). Steam starts this game and stays for the next one. Steam's window stays off."
                            else -> "Play: only $title${if (appId > 0) " (app $appId)" else ""}. ${exe.name} starts."
                        },
                    )
                    if (plan.steam == DirectLaunch.SteamRole.BESIDE_STEAM) {
                        if (applaunchGame.equals(exe.name, ignoreCase = true) && sessionGame == null) {
                            // Steam is still starting it from the last Play (you left its screen meanwhile): back to it, no second start.
                            appendLog("Steam is already starting $title: back to its screen.")
                            BuiltInDisplay.expectWindow(exe.name.lowercase(), null)
                            gameFillsDisplay(true)
                            PlayingService.start(app, title)
                            _stage.value = LaunchStage.Running(title, vr = false)
                            watchOnScreen(app, l, title, exe.name)
                        } else startInSteam(app, exe, args, title, appId)
                        return@launch
                    }
                    if (plan.steam == DirectLaunch.SteamRole.APPLAUNCH && !SteamWindows.isSignedIn(l)) {
                        // Nobody has signed in to this Steam yet: Steam can't start the game before that, and its sign-in needs its window.
                        appendLog("Sign in to Steam first: opening Steam. When you're signed in, leave Steam and press Play on $title again.")
                        ensureSteamClient(l, "Steam")
                        start(ctx, SteamWindows.exe(l), SteamWindows.args(app).joinToString(" "), RuntimeActivity.MODE_FLAT, "Steam: sign in, then play $title")
                        watchSteamSignIn(l)
                        return@launch
                    }
                    _stage.value = LaunchStage.Working(title, plan.step)
                    start(ctx, exe, args, mode, title, appId)
                    return@launch
                }
                if (openingSteam && steamRunning()) {
                    if (!steamCoreOnly) { showSteam(app); return@launch }
                    // Steam's window is drawn by its browser, which this Steam doesn't run: Steam restarts with it.
                    if (sessionGame != null) {
                        appendLog("Steam's window needs Steam's browser: close the game first, then open Steam.")
                        _stage.value = LaunchStage.Idle
                        return@launch
                    }
                    appendLog("Steam's window needs Steam's browser: restarting Steam with it (a game waiting in the background closes).")
                }
                start(ctx, exe, args, mode, title)
                if (openingSteam) watchSteamSignIn(l)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                appendLog("Couldn't start: $e")
                _stage.value = LaunchStage.Failed(title, e.message ?: e.toString())
            }
        }
    }

    private suspend fun start(ctx: Context, requested: File, args: String, mode: String, title: String, appId: Int = 0) {
        val l = launcher ?: GameLauncher(ctx.applicationContext).also { launcher = it }
        val gen = ++launchGen
        steamEnv = null
        sessionGame = null
        steamSignedIn = false
        steamCoreOnly = false
        steamRestarting = false
        applaunchGame = null
        gameExe = null
        gameTitle = null
        gameStartedAt = System.currentTimeMillis()
        gameShownAt = 0L
        markBackgroundErrors()
        val steamExe = SteamWindows.isSteam(requested)
        trimAppMemory(ctx, collect = steamExe)
        LogFile.setRunning(true)
        prepareRuntime(ctx, l, gamesOnly = !steamExe)
        // The copy of steam.exe that was asked for may have been the stale one removed above.
        val exe = if (SteamWindows.isSteam(requested) && !requested.isFile) SteamWindows.exe(l) else requested
        val disp = display(ctx)
        val gameDir = gameRoot(l, exe)
        lastGameDir = exe.parentFile
        val env = mutableMapOf<String, String>()
        env.putAll(GameSpeed.env(ctx, l))
        appendLog("Game speed: ${GameSpeed.describe(ctx)}.")
        val wantVr = mode == RuntimeActivity.MODE_VR
        if (!SteamWindows.isSteam(exe)) keepShownGame(title, DirectLaunch.launching(title))
        else _stage.value = LaunchStage.Working(title, "Preparing ${if (wantVr) "VR" else "flat screen"} mode…")
        withContext(Dispatchers.IO) {
            runCatching { env["QSS_PAD_SHM"] = GamepadBridge.prepare(ctx, l.rootfs, ::appendLog).path }
                .onFailure { appendLog("Controller bridge unavailable: $it") }
            val vrGame = VrBridge.isVrGame(gameDir)
            if (wantVr && vrGame && VrBridge.canPlayInVr(ctx)) {
                val n = VrBridge.install(ctx, gameDir, ::appendLog)
                if (n > 0) {
                    env["QSS_VR_SHM"] = VrBridge.prepareShm(ctx).path
                    appendLog("SteamVR game: starting in VR (bridge in $n place(s)).")
                } else {
                    appendLog("This game's openvr_api.dll is 32-bit; VR needs a 64-bit game for now. Starting flat.")
                }
            } else if (wantVr && vrGame && !VrBridge.isAvailable(ctx)) {
                appendLog("VR bridge DLL is missing from this build; starting flat.")
            } else if (wantVr && !vrGame) {
                appendLog("This game doesn't use SteamVR (OpenVR), so it starts flat.")
            } else {
                if (vrGame) VrBridge.uninstall(gameDir)
                appendLog("Flat mode: the game runs on a screen.")
            }
        }
        val pads = Controllers.connected.value
        if (pads.isEmpty()) appendLog("No controller connected (pair one in Quest Settings → Devices → Bluetooth).")
        else pads.take(GamepadBridge.MAX).forEachIndexed { i, c -> appendLog("Controller ${i + 1}: ${c.model} → game") }
        val inVr = env.containsKey("QSS_VR_SHM")

        // The game's window. Default: the display built into this app (the game shows in this window).
        // A Termux:X11 address (127.0.0.1:0) in the Runtime settings still uses that app instead.
        val builtIn = BuiltInDisplay.isBuiltIn(disp)
        val termux = !builtIn && disp != null && (disp.startsWith("127.0.0.1") || disp.startsWith("localhost"))
        var baseline = 0
        if (builtIn) {
            if (!SteamWindows.isSteam(exe)) keepShownGame(title, DirectLaunch.launching(title))
            else _stage.value = LaunchStage.Working(title, "Starting the game display…")
            // The game's own windows have its exe name as their class; Steam's UI is drawn by other processes.
            BuiltInDisplay.expectedWindowClass = if (SteamWindows.isSteam(exe)) null else exe.name.lowercase()
            BuiltInDisplay.expectedUiWindowClass = if (SteamWindows.isSteam(exe)) SteamWindows.UI_WINDOW_CLASS else null
            if (SteamWindows.isSteam(exe)) env.putAll(SteamWindows.steamOnlyEnv(ctx, l))
            // No Steam overlay in games (too heavy under Box64).
            env["WINEDLLOVERRIDES"] = (l.environment(null)["WINEDLLOVERRIDES"] ?: "") + ";" + SteamWindows.DLL_OVERRIDES
            val useVortek = Vortek.selected(ctx) == Vortek.VORTEK && withContext(Dispatchers.IO) {
                runCatching { Vortek.prepare(ctx, l.rootfs, ::appendLog) }
                    .onFailure { appendLog("Vortek GPU driver setup failed: ${it.message}") }.getOrDefault(false)
            }
            env.putAll(BuiltInDisplay.start(ctx, l.rootfs, useVortek, ::appendLog))
            if (!SteamWindows.isSteam(exe)) gameFillsDisplay(true)
            setWineAudio(l, env.containsKey("ANDROID_ALSA_SERVER"))
            // The view exists now, on this screen. Show it before the game draws so this game's
            // picture has a surface. A display created earlier, off this screen, was replaced above.
            if (!SteamWindows.isSteam(exe) && !inVr && BuiltInDisplay.view != null && DirectLaunch.showImmediately(false)) {
                _stage.value = LaunchStage.Opening(title)
            }
        } else if (termux) {
            if (!GameDisplay.isAppInstalled(ctx)) {
                appendLog("The game display app (Termux:X11) isn't installed. Install it, or clear the display setting to use the built-in display.")
                _needsDisplay.value = true
                _stage.value = LaunchStage.Idle
                return
            }
            _stage.value = LaunchStage.Working(title, if (SteamWindows.isSteam(exe)) "Starting the game display…" else DirectLaunch.launching(title))
            val problem = withContext(Dispatchers.IO) { GameDisplay.ensureRunning(ctx, l.rootfs, ::appendLog) }
            if (problem != null) { appendLog(problem); _stage.value = LaunchStage.Failed(title, problem); return }
            setWineAudio(l, false) // no sound server with Termux:X11: no audio device instead of a broken one
            GameDisplay.configureApp(ctx)
            if (!inVr) {
                GameDisplay.show(ctx)
                delay(300)
                bringBack(ctx)
            }
            baseline = withContext(Dispatchers.IO) { XProbe.visibleWindows()?.size ?: 0 }
        }

        val id = if (!SteamWindows.isSteam(exe)) (if (appId > 0) appId else gameDir.name.toIntOrNull() ?: 0) else 0
        val applaunch = DirectLaunch.usesApplaunch(SteamWindows.isSteam(exe), id, SteamWindows.isInstalled(l))
        // That command starts Steam too: Steam stays for the next games, and the game it starts is watched (see watchApplaunch).
        val adopt = applaunch && builtIn && !inVr
        if (!SteamWindows.isSteam(exe)) {
            if (id > 0) {
                withContext(Dispatchers.IO) {
                    SteamWindows.writeAppId(exe.parentFile ?: gameDir, id)
                    if (applaunch) {
                        runCatching {
                            SteamWindows.keepOffGpu(l)
                            SteamWindows.applyFastSettings(l)
                            val added = SteamLibrary.register(SteamWindows.dir(l), l.gamesDir) { gameId ->
                                if (gameId == id) title else null
                            }
                            if (added.contains(id)) appendLog("Steam's library: $title (app $id) is installed, so Play can start it.")
                        }.onFailure { appendLog("Couldn't add $title to Steam's library: ${it.message}") }
                    }
                }
                // Steam gives the game it starts its app id itself. steam.exe doesn't get one: it isn't that game.
                if (!applaunch) {
                    env["SteamAppId"] = id.toString()
                    env["SteamGameId"] = id.toString()
                }
            }
            appendLog(
                if (applaunch) "Steam applaunch: $title (app $id). The command is steam.exe -silent -applaunch $id."
                else "Direct launch: $title${if (id > 0) " (app $id)" else ""}. Only ${exe.name} starts.",
            )
            keepShownGame(title, DirectLaunch.launching(title))
        } else {
            _stage.value = LaunchStage.Working(title, "Starting Steam…")
        }
        val gameArgv = GameLauncher.splitArgs(args)
        if (gameArgv.isNotEmpty() && !SteamWindows.isSteam(exe)) appendLog("Launch options from Steam: $args")
        // Steam's desktop is the whole screen; a game's is the chosen game resolution (direct view scales it up).
        val desktop = if (SteamWindows.isSteam(exe)) BuiltInDisplay.SCREEN else BuiltInDisplay.gameDesktop(ctx)
        val sizeArgs = if (builtIn && !SteamWindows.isSteam(exe)) unitySizeArgs(exe, gameArgv, desktop) else emptyList()
        if (builtIn && !SteamWindows.isSteam(exe)) {
            appendLog(pictureNote(ctx, desktop, sizeArgs))
            appendLog(withContext(Dispatchers.IO) { runCatching { cacheNote(ctx, l) }.getOrDefault("") })
        }
        if (builtIn && (SteamWindows.isSteam(exe) || adopt)) {
            steamLogOffset = withContext(Dispatchers.IO) { SteamWindows.connectionLog(l).let { if (it.isFile) it.length() else 0L } }
            steamSignedIn = false
        }
        val besideClient = steamEnv != null && !SteamWindows.isSteam(exe)
        val launchFile = if (applaunch) SteamWindows.exe(l) else exe
        val launchArgv = if (applaunch) {
            DirectLaunch.applaunchArgs(id, gameArgv + sizeArgs, SteamWindows.args(ctx))
        } else gameArgv + sizeArgs
        if (applaunch) appendLog("Steam's Play command: " + launchArgv.joinToString(" "))
        val result = withContext(Dispatchers.IO) {
            l.launchExe(
                launchFile, if (builtIn) BuiltInDisplay.DISPLAY else disp, env, launchArgv,
                desktop = if (builtIn) desktop else null,
                desktopName = if (besideClient) nextDesktop("game") else GameLauncher.DESKTOP,
                onExit = exit@{ code ->
                    if (gen != launchGen) return@exit
                    if (!applaunch) {
                        onGameExit(ctx, title, code)
                    } else scope.launch {
                        val gameUp = withContext(Dispatchers.IO) { l.isRunning(exe.name) }
                        if (gameUp) appendLog("Steam's launch process ended ($code). $title is still running.")
                        else onGameExit(ctx, if (adopt) "Steam" else title, code)
                    }
                }, onLine = ::appendLog,
            )
        }
        appendLog(result.message)
        if (!result.ok) { _stage.value = LaunchStage.Failed(title, result.message); return }
        // Steam itself: its session stays up for games (see steamEnv); games don't get Steam's translation cache.
        if (builtIn && SteamWindows.isSteam(exe)) {
            steamEnv = env - SteamWindows.steamOnlyEnv(ctx, l).keys + GameSpeed.env(ctx, l)
            steamStartedAt = System.currentTimeMillis()
            watchPriority()
        } else if (adopt) {
            // Steam's own Play command started Steam: it's this session's Steam from now on (see steamEnv), so the next
            // game starts in it without a Steam start. The game is watched once Steam has started it (watchApplaunch).
            applaunchGame = exe.name
            steamEnv = env.toMap()
            steamStartedAt = System.currentTimeMillis()
            steamCoreOnly = false
            watchPriority()
        } else if (builtIn && !inVr) {
            gameExe = exe.name
            gameTitle = title
            if (besideClient) {
                sessionGame = exe.name
                foreground(steamLow = true)
            }
        }
        PlayingService.start(ctx, title)

        if (inVr) {
            _stage.value = LaunchStage.Working(title, "Opening the VR view…")
            delay(400)
            VrBridge.enterVr(ctx)
            _stage.value = LaunchStage.Running(title, vr = true)
            return
        }
        if (builtIn && !SteamWindows.isSteam(exe) && DirectLaunch.showImmediately(false)) {
            _stage.value = LaunchStage.Running(title, vr = false)
            appendLog("The game display is up. $title is starting in it.")
            watchOnScreen(ctx, l, title, exe.name)
            if (adopt) watchApplaunch(ctx, l, exe, args, title, id, gen)
            return
        }
        if (builtIn || termux) {
            // Loading screen until the game really draws its window (Unity/Unreal games take a while under Box64).
            val started = System.currentTimeMillis()
            var lastNote = 0L
            showNow = false
            val steamItself = SteamWindows.isSteam(exe)
            var lastBootstrap = ""
            var lastGameLog = ""
            var steamProcs = emptyList<String>()
            while (true) {
                if (!isRunning) return // onGameExit reports it
                if (showNow) break
                val secs = (System.currentTimeMillis() - started) / 1000
                if (builtIn) {
                    if (BuiltInDisplay.gameWindow.value) break
                    if (secs - lastNote >= 15) {
                        lastNote = secs
                        val n = BuiltInDisplay.server?.windowManager?.rootWindow?.children?.size ?: 0
                        appendLog("[waiting ${secs}s] the game hasn't drawn its window yet ($n windows, ${BuiltInDisplay.clientCount()} X clients on the display)")
                        withContext(Dispatchers.IO) { l.processes() }.takeIf { it.isNotEmpty() }?.let { appendLog("  running: " + it.joinToString(", ")) }
                        appendLog("  " + withContext(Dispatchers.IO) { memoryNote() })
                        appendBackgroundErrors()
                        if (secs in 45..59) BuiltInDisplay.describeWindows().takeIf { it.isNotEmpty() }?.let { w ->
                            appendLog("[waiting] windows on the game screen:"); w.forEach(::appendLog)
                        }
                        // What the game itself says while it loads (Unity's Player.log), so a hang shows where.
                        withContext(Dispatchers.IO) { gameLogTail(4) }.takeIf { it.isNotEmpty() && it.joinToString() != lastGameLog }?.let { t ->
                            lastGameLog = t.joinToString()
                            appendLog("  game says:"); t.forEach { appendLog("    $it") }
                        }
                        withContext(Dispatchers.IO) { appendNativeLog() }
                    }
                } else {
                    val windows = withContext(Dispatchers.IO) { XProbe.visibleWindows() }
                    if ((windows?.size ?: 0) > baseline) {
                        appendLog("Game window is open (${windows!!.joinToString { "${it.width}x${it.height}" }}).")
                        break
                    }
                    if (secs - lastNote >= 15) {
                        lastNote = secs
                        appendLog(
                            if (windows == null) "[waiting ${secs}s] can't ask the game display for windows: ${XProbe.lastError}"
                            else "[waiting ${secs}s] the game hasn't shown its window yet (${windows.size} visible, ${XProbe.lastTotal} windows in total on the display)"
                        )
                    }
                }
                if (steamItself) {
                    BuiltInDisplay.checkUiWindow(::appendLog)
                    if (BuiltInDisplay.gameWindow.value) break
                    // Steam's first start downloads the real Steam (a few hundred MB) before any window.
                    val boot = withContext(Dispatchers.IO) { SteamWindows.bootstrapTail(l) }
                    val last = boot.lastOrNull().orEmpty()
                    if (last.isNotEmpty() && last != lastBootstrap) { lastBootstrap = last; appendLog("[steam] $last") }
                    if (secs >= STEAM_TIMEOUT_S) {
                        appendLog("Steam hasn't shown a window after ${secs / 60} minutes; showing the game display anyway.")
                        break
                    }
                    val status = SteamWindows.bootStatus(boot)
                    if (secs % 5 == 0L) steamProcs = withContext(Dispatchers.IO) { l.processes() }
                    val pct = status.fraction?.let { " ${(it * 100).toInt()}%" } ?: ""
                    _stage.value = LaunchStage.Working(
                        title, "${status.phase}…$pct  (${secs / 60}:${"%02d".format(secs % 60)})",
                        progress = status.fraction,
                        detail = (listOf(
                            "Steam's first start updates Steam itself (a few hundred MB), then opens its sign-in window here by itself: " +
                                "a few minutes on the headset, nothing to press.",
                            "Running: " + steamProcs.joinToString(", ").ifEmpty { "…" },
                            "Steam says:",
                        ) + boot.ifEmpty { listOf("(nothing yet)") }).joinToString("\n"),
                        canShowWindow = false,
                    )
                    delay(1000)
                    continue
                }
                if (secs >= (if (builtIn) BUILT_IN_TIMEOUT_S else WINDOW_TIMEOUT_S)) {
                    appendLog("No game window after ${secs}s; showing the game display anyway.")
                    break
                }
                _stage.value = LaunchStage.Working(
                    title,
                    if (steamItself) "Starting Steam… ${secs}s" else DirectLaunch.launching(title, secs),
                    detail = logTail(5),
                    canShowWindow = secs >= 20,
                )
                delay(500)
            }
            if (builtIn && !steamItself && BuiltInDisplay.gameWindow.value) noteOnScreen(title)
            if (termux) GameDisplay.show(ctx)
        }
        gameShownAt = System.currentTimeMillis()
        _stage.value = LaunchStage.Running(title, vr = false)
        if (termux && pads.isNotEmpty()) {
            // The Termux:X11 window gets focus; the relay badge keeps the controller going to the game.
            if (ControllerRelay.canOverlay(ctx)) { delay(800); ControllerRelay.start(ctx) }
            else appendLog("To use the controller while the game window is in front, allow \"Display over other apps\" for Quest Steam.")
        }
    }

    /**
     * Starts a game inside Steam's running session (same display, wineserver and environment): no
     * Steam restart. The game screen comes up at once (see [DirectLaunch]); a Steam that's still signing
     * in is waited for there (games need Steam signed in, like on a PC). The game's own Windows process
     * is watched (the launcher process only hands it to the session's desktop); when it ends you're back
     * in the app and Steam keeps running.
     */
    private suspend fun startInSteam(ctx: Context, exe: File, args: String, title: String, appId: Int = 0) {
        val l = launcher ?: return
        if (!steamRunning()) {
            _stage.value = LaunchStage.Failed(title, "Steam closed before $title could start. The log below shows why.")
            return
        }
        BuiltInDisplay.expectWindow(exe.name.lowercase(), null)
        gameFillsDisplay(true)
        _stage.value = LaunchStage.Running(title, vr = false)
        watchOnScreen(ctx, l, title, exe.name)
        if (!steamSignedIn) {
            appendLog("Steam is still signing in in the background: $title starts as soon as it has (games need Steam signed in, like on a PC). Steam's window stays off.")
            if (!awaitSteamSignIn(l)) {
                if (!steamRunning()) { _stage.value = LaunchStage.Failed(title, "Steam closed before $title could start. The log below shows why."); return }
                appendLog("Starting $title anyway.")
            }
        } else appendLog("Starting $title next to the running Steam.")
        // (Steam may have restarted while it signed in: its environment now.)
        val base = steamEnv ?: return
        withContext(Dispatchers.IO) { l.pause(SteamWindows.WEB_HELPER, true) }
        trimAppMemory(ctx, collect = false)
        val result = launchInSession(ctx, l, base, exe, args, title, appId)
        if (!result.ok) { _stage.value = LaunchStage.Failed(title, result.message); return }
        LastGame.set(ctx, exe, args, title, appId.takeIf { it > 0 } ?: gameRoot(l, exe).name.toIntOrNull() ?: 0)
        PlayingService.start(ctx, title)
        appendLog("The game display is up. $title is starting in it.")
        watchGameExit(ctx, l, exe.name, title)
    }

    /**
     * Starts [exe] inside Steam's running session ([base] is its environment), on its own game desktop
     * on the same display (Steam's desktop belongs to Steam's explorer). On success it's the session's
     * game ([sessionGame]). [appId]: the game's Steam app id (0: its folder's name, if that's one).
     */
    private suspend fun launchInSession(ctx: Context, l: GameLauncher, base: Map<String, String>, exe: File, args: String, title: String, appId: Int = 0): LaunchResult {
        gameStartedAt = System.currentTimeMillis()
        gameShownAt = 0L
        lastGameDir = exe.parentFile
        sessionGame?.let { old -> withContext(Dispatchers.IO) { l.kill(old) } }
        val gameDir = gameRoot(l, exe)
        val env = base.toMutableMap()
        val id = appId.takeIf { it > 0 } ?: gameDir.name.toIntOrNull() ?: 0
        if (id > 0) {
            withContext(Dispatchers.IO) { SteamWindows.writeAppId(exe.parentFile ?: gameDir, id) }
            env["SteamAppId"] = id.toString()
            env["SteamGameId"] = id.toString()
        }
        appendLog("Direct launch: $title${if (id > 0) " (app $id)" else ""} via Box64/Wine. Steam's window stays off this screen.")
        markBackgroundErrors()
        LogFile.setRunning(true)
        appendLog(withContext(Dispatchers.IO) { memoryNote() })
        foreground(steamLow = true)
        BuiltInDisplay.expectWindow(exe.name.lowercase(), null)
        withContext(Dispatchers.IO) { applySteamUiPolicy() }
        val argv = GameLauncher.splitArgs(args)
        if (argv.isNotEmpty()) appendLog("Launch options from Steam: $args")
        val desktop = BuiltInDisplay.gameDesktop(ctx)
        val sizeArgs = unitySizeArgs(exe, argv, desktop)
        appendLog(pictureNote(ctx, desktop, sizeArgs))
        appendLog(withContext(Dispatchers.IO) { runCatching { cacheNote(ctx, l) }.getOrDefault("") })
        val result = withContext(Dispatchers.IO) {
            l.launchExe(exe, BuiltInDisplay.DISPLAY, env, argv + sizeArgs, desktop = desktop, track = false, desktopName = nextDesktop("game"), onLine = ::appendLog)
        }
        if (!result.ok) { appendLog(result.message); LogFile.setRunning(false); return result }
        gameLaunchedAt = System.currentTimeMillis()
        gameGen++
        sessionGame = exe.name
        gameExe = exe.name
        gameTitle = title
        watchMemory(title)
        return result
    }

    /**
     * Watches the session's game [name] (this start of it): when it closes you're back in the app, and Steam keeps
     * running. One that closes while its screen is up but before it drew anything didn't start: that's said, with
     * the log of why.
     */
    private fun watchGameExit(ctx: Context, l: GameLauncher, name: String, title: String) {
        val mine = gameGen
        scope.launch {
            val t0 = System.currentTimeMillis()
            var seen = false
            var misses = 0
            while (sessionGame == name && gameGen == mine && steamRunning()) {
                delay(2000)
                if (withContext(Dispatchers.IO) { l.isRunning(name) }) { seen = true; misses = 0; continue }
                // Wine starts it through its explorer: it can take a while to show up under its own name.
                if (!seen && System.currentTimeMillis() - t0 < 60_000) continue
                if (++misses >= 3) break
            }
            if (sessionGame != name || gameGen != mine) return@launch // left with "Leave game", or another game started
            sessionGame = null
            gameExe = null
            gameTitle = null
            // A paused game can end too (Android may close it when memory runs short).
            if (paused?.exe == name) { paused = null; ALSAClient.setMuted(false) }
            LogFile.setRunning(false)
            if (!steamRunning()) return@launch
            BuiltInDisplay.expectWindow(null, SteamWindows.UI_WINDOW_CLASS)
            PlayingService.start(ctx, "Steam")
            val onScreen = _stage.value.let { it is LaunchStage.Running && it.title == title }
            if (onScreen && gameShownAt == 0L) {
                LastGame.clear(ctx)
                appendLog("$title closed while starting${if (seen) "" else " (it never appeared)"}. Steam keeps running in the background.")
                appendLog(withContext(Dispatchers.IO) { memoryNote() })
                withContext(Dispatchers.IO) { appendNativeLog(); if (gameLogFile() != null) appendUnityLog() else appendDxvkLogs() }
                appendBackgroundErrors()
                _stage.value = LaunchStage.Failed(title, "The game closed while starting. The log below shows why.")
                return@launch
            }
            appendLog("$title closed. Steam keeps running in the background, so the next game starts faster.")
            withContext(Dispatchers.IO) { appendUnityLog() }
            if (onScreen) _stage.value = LaunchStage.Idle
            readyAgain(ctx, l, name, title, if (gameShownAt > 0) System.currentTimeMillis() - gameShownAt else 0L)
        }
    }

    /**
     * A game that closed by itself (quit from its own menu) after being played for a while gets ready again in the
     * background, so the next Play on it is instant too, not only the first one after the app opens. A game that
     * closes soon after it started is crashing, not played, and Close game / Leave end a game on purpose: neither
     * comes back by itself. See [getLastGameReady], which also checks that nothing else is going on.
     */
    private fun readyAgain(ctx: Context, l: GameLauncher, name: String, title: String, ranMs: Long) {
        if (ranMs < READY_AGAIN_MIN_RUN_MS || !LastGame.enabled(ctx)) return
        if (LastGame.get(ctx)?.exe?.name?.equals(name, ignoreCase = true) != true) return
        scope.launch {
            delay(8000) // the system settles, and a Play on another game right away wins
            if (steamRunning() && steamSignedIn) {
                appendLog("$title is played for now: getting it ready again in the background, so the next Play on it is instant.")
                getLastGameReady(ctx, l)
            }
        }
    }

    /**
     * Shows the running Steam: Steam started in the background (-silent) has no window yet, so it's
     * asked to open its main window (a second steam.exe hands the request to the running one).
     */
    private fun showSteam(ctx: Context) {
        gameFillsDisplay(false)
        val l = launcher ?: return
        val env = steamEnv ?: return
        BuiltInDisplay.expectWindow(null, SteamWindows.UI_WINDOW_CLASS)
        scope.launch {
            closeReadying()
            closePaused(ctx)
            foreground(steamLow = false)
            withContext(Dispatchers.IO) {
                resumeSteamUi()
                l.launchExe(SteamWindows.exe(l), BuiltInDisplay.DISPLAY, env, listOf("steam://open/main"), desktop = BuiltInDisplay.SCREEN, track = false, desktopName = nextDesktop("steam")) { }
            }
        }
        PlayingService.start(ctx, "Steam")
        _stage.value = LaunchStage.Running("Steam", vr = false)
    }

    /** The game on screen (or starting): its exe name and title, for Quick Resume. */
    @Volatile private var gameExe: String? = null
    @Volatile private var gameTitle: String? = null

    /**
     * Quick Resume: a game left with "Leave" waits paused; [inSteam] = it runs next to Steam (only its
     * own processes are paused); [ready] = it got ready in the background (see [getLastGameReady]).
     */
    private data class Paused(val exe: String, val title: String, val inSteam: Boolean, val ready: Boolean = false)
    @Volatile private var paused: Paused? = null
        set(v) { field = v; updateBackgroundGame() }

    /** A game waiting in the background, for the app's top bar: getting ready, or [ready] (paused: Play shows it at once). */
    data class BackgroundGame(val title: String, val exe: String, val ready: Boolean)
    private val _backgroundGame = MutableStateFlow<BackgroundGame?>(null)
    val backgroundGame: StateFlow<BackgroundGame?> = _backgroundGame

    private fun updateBackgroundGame() {
        _backgroundGame.value = paused?.let { BackgroundGame(it.title, it.exe, ready = true) }
            ?: readying?.let { BackgroundGame(it.title, it.exe, ready = false) }
    }

    /** The game waiting paused for Quick Resume, if any (its title). */
    val pausedTitle: String? get() = paused?.title

    /**
     * "Leave" on the game screen with Quick Resume, like a console: the game waits paused (its
     * processes stopped, so no CPU, and Android can compress its memory), and Play brings it back in a
     * few seconds where you left it, instead of starting it again. Steam keeps running. Starting
     * another game, opening Steam's window or Close ends it.
     */
    fun pauseGame(ctx: Context) {
        val l = launcher
        val exe = gameExe
        val title = gameTitle
        val inSteam = sessionGame != null && steamRunning()
        if (l == null || exe == null || title == null || (!inSteam && !isRunning)) { leave(ctx); return }
        scope.launch {
            val n = withContext(Dispatchers.IO) { if (inSteam) l.pause(exe, true) else l.pauseAll(true) }
            if (n == 0) { leave(ctx); return@launch }
            paused = Paused(exe, title, inSteam)
            LogFile.setRunning(false)
            appendLog("$title is paused (Quick Resume): Play brings it back in a few seconds, where you left it. Close game ends it.")
            PlayingService.start(ctx, "$title (paused)")
            _stage.value = LaunchStage.Idle
        }
    }

    /** Play on the paused game: it continues where it was. */
    private suspend fun resumeGame(ctx: Context, p: Paused, l: GameLauncher) {
        val pressed = playPressedAt
        _stage.value = LaunchStage.Opening(p.title)
        gameFillsDisplay(true)
        val n = withContext(Dispatchers.IO) { if (p.inSteam) l.pause(p.exe, false) else l.pauseAll(false) }
        paused = null
        ALSAClient.setMuted(false)
        LogFile.setRunning(true)
        if (p.inSteam) { sessionGame = p.exe; foreground(steamLow = true) }
        gameExe = p.exe
        gameTitle = p.title
        BuiltInDisplay.expectWindow(p.exe.lowercase(), null)
        PlayingService.start(ctx, p.title)
        playPressedAt = 0L
        val secs = "%.1f".format(java.util.Locale.US, if (pressed > 0) (System.currentTimeMillis() - pressed) / 1000.0 else 0.0)
        if (p.ready) {
            appendLog("⏱ ${p.title} is on screen ${secs}s after Play (it got ready in the background; $n process(es) continued).")
            BuiltInDisplay.showStartTime("on screen ${secs}s after Play")
        } else {
            appendLog("⏱ ${p.title} is back ${secs}s after Play (Quick Resume, $n process(es) continued).")
            BuiltInDisplay.showStartTime("resumed in ${secs}s")
        }
        gameShownAt = System.currentTimeMillis()
        _stage.value = LaunchStage.Running(p.title, vr = false)
    }

    /** Ends the paused game (another game starts, Steam's window opens, or the session closes). */
    private suspend fun closePaused(ctx: Context) {
        val p = paused ?: return
        paused = null
        ALSAClient.setMuted(false)
        val l = launcher ?: return
        appendLog("Closing the paused ${p.title}.")
        if (p.inSteam) {
            withContext(Dispatchers.IO) { l.pause(p.exe, false); l.kill(p.exe) }
            if (sessionGame == p.exe) sessionGame = null
        } else {
            // Its session's exit isn't a game failing: the next start is already on its way.
            ++launchGen
            withContext(Dispatchers.IO) { l.pauseAll(false); l.stop() }
            BuiltInDisplay.stop()
        }
        gameExe = null
        gameTitle = null
    }

    /** The game getting ready in the background (see [getLastGameReady]) while it loads: its exe name, title and start time. */
    private data class Readying(val exe: String, val title: String, val startedAt: Long)
    @Volatile private var readying: Readying? = null
        set(v) { field = v; updateBackgroundGame() }
    private var readyJob: Job? = null

    /**
     * "Get my last game ready too" (see [LastGame]): once Steam has signed in in the background as the
     * app opens, the last game played next to Steam starts too, muted and out of sight, and waits
     * paused at its menu like a Quick Resume game: Play shows it in a few seconds instead of loading
     * it for a minute or more. Play while it still loads shows its loading screen; another game, or
     * Steam's window, closes it.
     */
    private fun getLastGameReady(ctx: Context, l: GameLauncher) {
        if (!LastGame.enabled(ctx)) return
        val game = LastGame.get(ctx) ?: return
        // Only while nothing else goes on: Play may have been pressed meanwhile.
        if (_stage.value !is LaunchStage.Idle || job?.isActive == true || sessionGame != null || paused != null || readying != null) return
        val base = steamEnv?.takeIf { steamRunning() } ?: return
        val title = game.title
        val name = game.exe.name
        readyJob = scope.launch {
            var t0 = System.currentTimeMillis()
            try {
                // Right after sign-in Steam's browser (if it runs) still holds a lot of memory until Android compresses it: wait for room.
                var free = withContext(Dispatchers.IO) { freeMemoryMb() }
                var waited = 0
                while (free < READY_MIN_FREE_MB && waited < 90 && steamRunning()) {
                    delay(3000)
                    waited += 3
                    free = withContext(Dispatchers.IO) { freeMemoryMb() }
                }
                if (!steamRunning()) return@launch
                if (free < READY_MIN_FREE_MB) {
                    appendLog("Not getting $title ready in the background: only $free MB of memory free (it needs $READY_MIN_FREE_MB MB to keep the library smooth). Play starts it as usual.")
                    return@launch
                }
                t0 = System.currentTimeMillis()
                appendLog("Getting $title ready in the background (muted, out of sight): Play shows it in a few seconds once it's ready (Runtime settings → Steam for Windows).")
                // Once started, it's always either readying or ended, also when Play cancels this.
                val started = withContext(NonCancellable) {
                    ALSAClient.setMuted(true)
                    launchInSession(ctx, l, base, game.exe, game.args, title, game.appId).ok.also { if (it) readying = Readying(name, title, t0) }
                }
                if (!started) return@launch
                PlayingService.start(ctx, "$title (getting ready)")
                var seenAt = 0L
                var frameAt = 0L
                while (true) {
                    if (!steamRunning()) { readying = null; return@launch } // onGameExit reports it
                    val now = System.currentTimeMillis()
                    if (withContext(Dispatchers.IO) { l.isRunning(name) }) seenAt = now
                    val ended = if (seenAt > 0) now - seenAt > 8_000 else now - t0 >= 60_000
                    val tooLong = now - t0 >= BUILT_IN_TIMEOUT_S * 1000L
                    if (ended || tooLong) {
                        readying = null
                        if (sessionGame == name) sessionGame = null
                        gameExe = null
                        gameTitle = null
                        LogFile.setRunning(false)
                        LastGame.clear(ctx)
                        if (tooLong && !ended) withContext(Dispatchers.IO) { l.kill(name) }
                        appendLog(
                            if (ended) "$title closed while getting ready in the background; Play starts it as usual."
                            else "$title didn't draw within ${BUILT_IN_TIMEOUT_S / 60} minutes in the background; closed it (Play starts it as usual)."
                        )
                        withContext(Dispatchers.IO) { appendNativeLog(); appendUnityLog() }
                        appendBackgroundErrors()
                        PlayingService.start(ctx, "Steam")
                        return@launch
                    }
                    if (frameAt == 0L && BuiltInDisplay.gameWindow.value) {
                        frameAt = now
                        appendLog("$title is drawing (${(now - t0) / 1000}s after it started in the background); it pauses once it has loaded.")
                    }
                    if (frameAt > 0 && withContext(Dispatchers.IO) { loadedEnough(now - frameAt) }) break
                    delay(1000)
                }
                withContext(NonCancellable) {
                    val n = withContext(Dispatchers.IO) { l.pause(name, true) }
                    readying = null
                    if (n == 0) {
                        if (sessionGame == name) sessionGame = null
                        gameExe = null
                        gameTitle = null
                        LogFile.setRunning(false)
                        appendLog("$title closed while getting ready in the background; Play starts it as usual.")
                        return@withContext
                    }
                    paused = Paused(name, title, inSteam = true, ready = true)
                    LogFile.setRunning(false)
                    PlayingService.start(ctx, "$title (ready)")
                    appendLog("✓ $title is ready (${(System.currentTimeMillis() - t0) / 1000}s in the background) and waits paused at its menu: Play shows it in a few seconds.")
                    appendLog("  " + withContext(Dispatchers.IO) { memoryNote() })
                    watchGameExit(ctx, l, name, title)
                }
            } finally {
                if (readying == null && paused?.ready != true) ALSAClient.setMuted(false)
            }
        }
    }

    /**
     * A game getting ready has loaded enough to pause, [sinceFrame] ms after its first frame: once its
     * Unity log has been quiet for 5 seconds (it's waiting at its menu), else 15 seconds after the first
     * frame; at most a minute.
     */
    private fun loadedEnough(sinceFrame: Long): Boolean {
        if (sinceFrame < 5_000) return false
        if (sinceFrame >= 60_000) return true
        val log = gameLogFile() ?: return sinceFrame >= 15_000
        return System.currentTimeMillis() - log.lastModified() >= 5_000
    }

    /** Play on the game that's getting ready in the background: its screen comes up now, with the game on it (maybe at once). */
    private suspend fun showReadying(ctx: Context, l: GameLauncher, r: Readying) {
        readying = null
        ALSAClient.setMuted(false)
        LogFile.setRunning(true)
        appendLog("${r.title} has been getting ready in the background for ${(System.currentTimeMillis() - r.startedAt) / 1000}s: it's on screen now.")
        foreground(steamLow = true)
        gameFillsDisplay(true)
        PlayingService.start(ctx, r.title)
        _stage.value = LaunchStage.Running(r.title, vr = false)
        watchOnScreen(ctx, l, r.title, r.exe)
        watchGameExit(ctx, l, r.exe, r.title)
    }

    /** Ends the game getting ready in the background (another game starts, or Steam's window opens). */
    private suspend fun closeReadying() {
        readyJob?.cancelAndJoin()
        val r = readying ?: return
        readying = null
        ALSAClient.setMuted(false)
        LogFile.setRunning(false)
        appendLog("Closing ${r.title}, which was getting ready in the background.")
        launcher?.let { l -> withContext(Dispatchers.IO) { l.kill(r.exe) } }
        if (sessionGame == r.exe) sessionGame = null
        gameExe = null
        gameTitle = null
    }

    /**
     * Cancel on the loading screen: the start stops, but a Steam that's running (or still signing in)
     * keeps running in the background, so the next Play doesn't wait for Steam to start again.
     */
    fun cancelStart(ctx: Context) {
        if (!steamRunning()) { stop(ctx); return }
        job?.cancel()
        playPressedAt = 0L
        leave(ctx)
    }

    /**
     * "Leave" on the game screen. A game started in Steam's session closes and Steam keeps running;
     * leaving Steam's own screen keeps Steam running in the background (Open Steam shows it again,
     * and games start in it at once). Anything else stops completely.
     */
    fun leave(ctx: Context) {
        val l = launcher
        val game = sessionGame
        // A start still on its way (waiting for Steam to sign in) stops with it; Steam keeps running.
        playGen++
        job?.takeIf { it.isActive }?.cancel()
        BuiltInDisplay.waitingText = null
        when {
            game != null && steamRunning() -> {
                sessionGame = null
                gameExe = null
                gameTitle = null
                LogFile.setRunning(false)
                val n = l?.kill(game) ?: 0
                appendLog("Closed $game ($n process(es)). Steam keeps running in the background.")
                BuiltInDisplay.expectWindow(null, SteamWindows.UI_WINDOW_CLASS)
                PlayingService.start(ctx, "Steam")
                _stage.value = LaunchStage.Idle
            }
            steamRunning() -> {
                LogFile.setRunning(false)
                appendLog(
                    applaunchGame?.let { "You left before Steam started $it: Steam keeps running in the background (the game closes if Steam starts it now), so Play starts it right away." }
                        ?: "Steam keeps running in the background: Open Steam shows it again, and games start in it right away.",
                )
                _stage.value = LaunchStage.Idle
            }
            else -> stop(ctx)
        }
    }

    private fun onGameExit(ctx: Context, title: String, code: Int) {
        // The session's main process ended (the game, or Steam with any game started in it).
        steamEnv = null
        sessionGame = null
        steamUiPaused = false
        steamSignedIn = false
        steamCoreOnly = false
        coreTrial = false
        steamRestarting = false
        applaunchGame = null
        paused = null
        readyJob?.cancel()
        readying = null
        ALSAClient.setMuted(false)
        gameExe = null
        gameTitle = null
        LogFile.setRunning(false)
        ControllerRelay.stop(ctx)
        PlayingService.stop(ctx)
        appendNativeLog()
        appendBackgroundErrors()
        appendLog(memoryNote())
        launcher?.processes()?.takeIf { it.isNotEmpty() }?.let { appendLog("[processes] " + it.joinToString(", ")) }
        appendWindows()
        appendSteamLogs()
        appendUnityLog()
        launcher?.killLeftovers()
        scope.launch {
            BuiltInDisplay.stop()
            BuiltInDisplay.waitingText = null
            // Steam ([title]) ended before the game on screen started, or the game itself while starting.
            fun failed(game: String) = LaunchStage.Failed(
                game, (if (title == "Steam" && !game.startsWith("Steam")) "Steam closed before $game started" else "The game closed while starting") +
                    " (code $code). The log below shows why.",
            )
            _stage.value = when (val st = _stage.value) {
                is LaunchStage.Working -> failed(st.title)
                is LaunchStage.Opening -> failed(st.title)
                // The game screen is up before the game draws (see DirectLaunch): one that never drew didn't start.
                is LaunchStage.Running -> if (!st.vr && gameShownAt == 0L && !st.title.startsWith("Steam")) failed(st.title) else LaunchStage.Idle
                else -> LaunchStage.Idle
            }
        }
    }

    /**
     * Wine's audio driver: "alsa" (Winlator's android_aserver plugin, to the app's ALSA server) or
     * none at all. Written into user.reg while Wine isn't running.
     */
    private suspend fun setWineAudio(l: GameLauncher, alsa: Boolean) = withContext(Dispatchers.IO) {
        runCatching {
            WineRegistry.setString(File(l.prefix, "user.reg"), "Software\\Wine\\Drivers", "Audio", if (alsa) "alsa" else "")
        }.onFailure { appendLog("Couldn't set Wine's sound driver: ${it.message}") }
    }

    @Volatile private var gameStartedAt = 0L
    @Volatile private var nativeLogShownUntil = 0L

    /**
     * Adds this app's own Android log lines about the GPU driver (Vortek, Vulkan, crashes) since the
     * game started, so a failure can be read from the in-app log. An app may read its own log lines.
     */
    private fun appendNativeLog() {
        val since = maxOf(gameStartedAt, nativeLogShownUntil)
        if (since <= 0L) return
        val fmt = java.text.SimpleDateFormat("MM-dd HH:mm:ss.SSS", java.util.Locale.US)
        val lines = runCatching {
            val p = ProcessBuilder("logcat", "-d", "-v", "time", "-T", fmt.format(java.util.Date(since)), "--pid=${android.os.Process.myPid()}")
                .redirectErrorStream(true).start()
            p.inputStream.bufferedReader().readLines().also { p.waitFor() }
        }.getOrElse { appendLog("[android log unavailable: ${it.message}]"); return }
        nativeLogShownUntil = System.currentTimeMillis()
        val keep = Regex("vortek|Vortek|VORTEK|vulkan|Vulkan|adreno|Adreno|AHardwareBuffer|linker|DEBUG|libc|FATAL|AndroidRuntime|UnsatisfiedLink|JNI|kgsl", RegexOption.IGNORE_CASE)
        // The Adreno driver prints a long info dump for every Vulkan device; only warnings/errors matter.
        val noise = Regex("\\bI/Adreno|W/Adreno-AppProfiles|I/AdrenoVK|Searching for layers|already have|I/vulkan")
        val picked = lines.filter { keep.containsMatchIn(it) && !noise.containsMatchIn(it) && !it.contains("logcat") }.takeLast(40)
        if (picked.isNotEmpty()) {
            appendLog("[android log]")
            picked.forEach { appendLog("  " + it.substringAfter(' ').substringAfter(' ').take(300)) }
        }
    }

    /** The Unity Player.log the game has written since it started, if it's a Unity game. */
    private fun gameLogFile(): File? {
        val l = launcher ?: return null
        val since = gameStartedAt
        return File(l.prefix, "drive_c/users").listFiles().orEmpty()
            .mapNotNull { File(it, "AppData/LocalLow").takeIf(File::isDirectory) }
            .flatMap { low -> low.walkTopDown().maxDepth(3).filter { it.name == "Player.log" && it.lastModified() >= since - 5_000 }.toList() }
            .maxByOrNull { it.lastModified() }
    }

    /** The last [n] lines the game wrote to its Unity Player.log since it started (nothing for other games). */
    private fun gameLogTail(n: Int): List<String> {
        val log = gameLogFile() ?: return emptyList()
        return runCatching { log.readLines() }.getOrDefault(emptyList()).filter { it.isNotBlank() }.takeLast(n).map { it.take(200) }
    }

    /**
     * Unity games write why they stopped (e.g. "Failed to initialize graphics") to Player.log in
     * AppData\LocalLow\<company>\<game>; its important lines go into the log.
     */
    private fun appendUnityLog() {
        val l = launcher ?: return
        val since = gameStartedAt
        val users = File(l.prefix, "drive_c/users")
        val logs = users.listFiles().orEmpty().mapNotNull { File(it, "AppData/LocalLow").takeIf(File::isDirectory) }
            .flatMap { low -> low.walkTopDown().maxDepth(3).filter { it.name == "Player.log" && it.lastModified() >= since - 5_000 }.toList() }
        val log = logs.maxByOrNull { it.lastModified() } ?: return
        val keep = Regex("d3d|dxgi|direct3d|vulkan|gfxdevice|graphics|error|fail|exception|gpu|renderer|adapter|crash|signal|access violation|Version:|Vendor:|VRAM|Driver:|Initialize engine|UnityPlayer|mono-2|MonoBleeding|GameAssembly|\\.exe \\(|Mono path|Fallback handler|Crash!!!", RegexOption.IGNORE_CASE)
        // A crash dumps hundreds of symbol lookups that fail under Wine; they say nothing.
        val noise = Regex("SymGet|SymInit|SymFrom|SymLoad|\\(0x[0-9A-Fa-f]+\\) \\(|^\\s*0x[0-9A-Fa-f]+ ")
        val raw = runCatching { log.readLines() }.getOrDefault(emptyList())
        val all = raw.filter { it.isNotBlank() && !noise.containsMatchIn(it) }
        val lines = all.filter { keep.containsMatchIn(it) }.let { if (it.size > 40) it.take(25) + it.takeLast(15) else it }
        // The end of the log says what the game did last (e.g. waiting for Steam).
        val tail = all.takeLast(12).filter { it !in lines }
        if (lines.isNotEmpty() || tail.isNotEmpty()) {
            appendLog("[game log: ${log.parentFile?.name}/Player.log]")
            lines.forEach { appendLog("  " + it.take(300)) }
            if (tail.isNotEmpty()) {
                appendLog("  … last lines:")
                tail.forEach { appendLog("  " + it.take(300)) }
            }
        }
        locateCrash(raw)
        appendDxvkLogs()
    }

    /**
     * Names the DLL a crash happened in: Wine's "page fault … at address X" and Unity's stack addresses,
     * matched against the module list Unity writes ("C:\…\Foo.dll:Foo.dll (BASE), size: N").
     */
    private fun locateCrash(playerLog: List<String>) {
        val moduleLine = Regex("([^\\\\:]+\\.(?:dll|exe|drv)) \\(([0-9A-Fa-f]{8,16})\\), size: (\\d+)", RegexOption.IGNORE_CASE)
        val modules = playerLog.mapNotNull { line ->
            val m = moduleLine.find(line) ?: return@mapNotNull null
            val base = m.groupValues[2].toULongOrNull(16) ?: return@mapNotNull null
            val size = m.groupValues[3].toULongOrNull() ?: return@mapNotNull null
            Triple(m.groupValues[1], base, size)
        }.distinctBy { it.second }
        if (modules.isEmpty()) return
        fun where(addr: ULong): String = modules.firstOrNull { addr >= it.second && addr < it.second + it.third }
            ?.let { "${it.first}+0x${(addr - it.second).toString(16)}" } ?: "0x${addr.toString(16)} (outside any loaded DLL)"
        val wineFault = Regex("page fault on (\\w+) access to ([0-9A-Fa-f]+) at address ([0-9A-Fa-f]+)").findAll(_log.value).lastOrNull()
        if (wineFault != null) {
            val at = wineFault.groupValues[3].toULongOrNull(16)
            if (at != null) appendLog("[crash] ${wineFault.groupValues[1]} of 0x${wineFault.groupValues[2]} in ${where(at)}")
        }
        val addrRegex = Regex("Address: ([0-9A-Fa-f]{8,16})")
        val stack = playerLog.mapNotNull { addrRegex.find(it)?.groupValues?.get(1)?.toULongOrNull(16) }.distinct().take(8)
        if (stack.isNotEmpty()) appendLog("[crash stack] " + stack.joinToString(" <- ") { where(it) })
    }

    @Volatile var lastGameDir: File? = null

    /** DXVK writes <game>_d3d11.log / <game>_dxgi.log next to the game: which GPU it found and why it failed. */
    private fun appendDxvkLogs() {
        val dir = lastGameDir ?: return
        val since = gameStartedAt
        val logs = dir.walkTopDown().maxDepth(3)
            .filter { it.isFile && (it.name.endsWith("_d3d11.log") || it.name.endsWith("_dxgi.log") || it.name.endsWith("_d3d9.log")) && it.lastModified() >= since - 5_000 }
            .toList()
        val keep = Regex("err:|warn:|DXVK:|Found device|Driver:|Vulkan:|Required|not supported|Failed|Feature level", RegexOption.IGNORE_CASE)
        for (f in logs) {
            val lines = runCatching { f.readLines() }.getOrDefault(emptyList()).filter { keep.containsMatchIn(it) }.takeLast(20)
            if (lines.isEmpty()) continue
            appendLog("[DXVK log: ${f.name}]")
            lines.forEach { appendLog("  " + it.take(300)) }
        }
    }

    private fun appendSteamLogs() {
        val l = launcher ?: return
        if (!SteamWindows.isInstalled(l)) return
        val tails = SteamWindows.logTails(l, gameStartedAt)
        if (tails.isEmpty()) { appendLog("[steam logs] none written since the start"); return }
        appendLog("[steam logs]")
        tails.forEach(::appendLog)
    }

    private fun appendWindows() {
        val w = BuiltInDisplay.describeWindows()
        if (w.isEmpty()) return
        appendLog("[windows]")
        w.forEach(::appendLog)
    }

    fun stop(ctx: Context) {
        appendNativeLog()
        appendBackgroundErrors()
        appendLog(memoryNote())
        launcher?.processes()?.takeIf { it.isNotEmpty() }?.let { appendLog("[processes] " + it.joinToString(", ")) }
        appendWindows()
        appendSteamLogs()
        appendUnityLog()
        job?.cancel()
        warmJob?.cancel()
        displayJob?.cancel()
        readyJob?.cancel()
        playGen++
        applaunchGame = null
        BuiltInDisplay.waitingText = null
        // A paused game or Steam browser couldn't close with Steam.
        paused?.let { p -> launcher?.let { l -> if (p.inSteam) { l.pause(p.exe, false); l.kill(p.exe) } else l.pauseAll(false) } }
        paused = null
        readying?.let { r -> launcher?.kill(r.exe) }
        readying = null
        ALSAClient.setMuted(false)
        gameExe = null
        gameTitle = null
        resumeSteamUi()
        LogFile.setRunning(false)
        val gen = ++launchGen
        val l = launcher
        val steam = steamEnv?.takeIf { steamRunning() }
        steamEnv = null
        sessionGame = null
        steamSignedIn = false
        steamCoreOnly = false
        coreTrial = false
        steamRestarting = false
        ControllerRelay.stop(ctx)
        PlayingService.stop(ctx)
        _stage.value = LaunchStage.Idle
        appendLog("[stopped]")
        if (l == null || steam == null) {
            l?.stop()
            BuiltInDisplay.stop()
            return
        }
        // Steam saves its sign-in and settings when it closes itself; killed, it can lose them.
        appendLog("Closing Steam…")
        scope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    l.launchExe(SteamWindows.exe(l), BuiltInDisplay.DISPLAY, steam, listOf("-shutdown"), desktop = BuiltInDisplay.SCREEN, track = false, desktopName = nextDesktop("steam")) { }
                }
                val t0 = System.currentTimeMillis()
                while (System.currentTimeMillis() - t0 < 20_000 && l.isRunning("steam.exe")) delay(1000)
            }
            if (gen != launchGen) return@launch // something new started; it closes what's left itself
            appendLog(if (withContext(Dispatchers.IO) { l.isRunning("steam.exe") }) "Steam didn't close in 20s; ended it." else "Steam closed.")
            withContext(Dispatchers.IO) { l.stop() }
            BuiltInDisplay.stop()
        }
    }

    /**
     * A Unity game draws at the size it saved last time, which can be bigger than its desktop (Wine
     * then grows the desktop, and the chosen game resolution is lost). Unity's own options set the
     * size for this start, unless the launch options already do.
     */
    private fun unitySizeArgs(exe: File, argv: List<String>, desktop: String): List<String> {
        if (argv.any { it.equals("-screen-width", ignoreCase = true) || it.equals("-screen-height", ignoreCase = true) }) return emptyList()
        if (!File(exe.parentFile ?: return emptyList(), "UnityPlayer.dll").isFile) return emptyList()
        val size = desktop.split('x').mapNotNull { it.toIntOrNull() }
        if (size.size != 2) return emptyList()
        return listOf("-screen-width", size[0].toString(), "-screen-height", size[1].toString())
    }

    /** The log line saying how the game's picture is shown. */
    private fun pictureNote(ctx: Context, desktop: String, sizeArgs: List<String>): String =
        (if (BuiltInDisplay.directView(ctx)) "Game picture: direct view (only the game's own frames, scaled to the screen like a game stream), game draws at $desktop"
        else "Game picture: whole Windows desktop, $desktop") + (if (sizeArgs.isNotEmpty()) " (Unity: ${sizeArgs.joinToString(" ")})." else ".")

    /** Written once per process. Later Plays only close leftover Wine processes. */
    @Volatile private var runtimeReady = false

    /**
     * What every start does first (a game, Steam, or the game display getting ready): Winlator's
     * per-game Box64 file, DXVK's settings, Wine processes left over, Wine's network info and DLL
     * settings. Steam's library is updated only when opening Steam, not when starting a game.
     */
    private suspend fun prepareRuntime(ctx: Context, l: GameLauncher, gamesOnly: Boolean = false) {
        withContext(Dispatchers.IO) {
            if (!gamesOnly) {
                runCatching { SteamWindows.dedupeExe(l)?.let { appendLog("Steam folder: $it (two copies of steam.exe).") } }
            }
            if (!runtimeReady) {
                var ok = true
                // Box64's per-game settings from Winlator (bundled as an asset by CI).
                runCatching {
                    val rc = File(l.rootfs, "etc/config.box64rc")
                    // Without Winlator's pre-exec hook (see RuntimeCore.disablePreExec): it made Steam.exe wait forever.
                    val bytes = RuntimeCore.sanitizeBox64Rc(ctx.assets.open("graphics/default.box64rc").use { String(it.readBytes()) }).toByteArray()
                    if (!rc.isFile || !rc.readBytes().contentEquals(bytes)) { rc.parentFile?.mkdirs(); rc.writeBytes(bytes) }
                }.onFailure { ok = false }
                // DXVK settings: Winlator's (constant buffer range checks avoid out-of-bounds reads), plus less
                // memory: the Quest's GPU shares its RAM with everything else.
                // Games see 2 GB of video memory (and size their caches to it), and two shader compiler
                // threads run instead of one per CPU core.
                runCatching {
                    File(l.rootfs, "home/xuser/.config/dxvk.conf").apply { parentFile?.mkdirs() }.writeText(
                        "d3d11.constantBufferRangeCheck = \"True\"\n" +
                            "dxgi.maxDeviceMemory = 2048\n" +
                            "dxvk.numCompilerThreads = 2\n"
                    )
                }.onFailure { ok = false }
                runCatching { NetworkInfo.write(ctx, l.rootfs) }.onFailure { ok = false; appendLog("Couldn't write the network info for Wine: ${it.message}") }
                // Games started by Steam itself get the same DLLs as the app's own launches: Winlator's
                // settings replace WINEDLLOVERRIDES for steam.exe, and Steam's games inherit that.
                runCatching {
                    val reg = File(l.prefix, "user.reg")
                    for ((dll, order) in RuntimeCore.dllOverrides(l.environment(null)["WINEDLLOVERRIDES"].orEmpty())) {
                        WineRegistry.setString(reg, "Software\\Wine\\DllOverrides", dll, order)
                    }
                }.onFailure { ok = false; appendLog("Couldn't write Wine's DLL settings: ${it.message}") }
                if (ok) runtimeReady = true
            }
            val k = l.killLeftovers()
            if (k > 0) appendLog("Closed $k Wine process(es) left over from the last game.")
            if (!gamesOnly && SteamWindows.isInstalled(l)) runCatching {
                SteamWindows.keepOffGpu(l)
                SteamWindows.applyFastSettings(l)
                val names = DownloadManager.get(ctx)
                val added = SteamLibrary.register(SteamWindows.dir(l), l.gamesDir) { id -> names.nameOf(id).takeIf { it != "game" } }
                if (added.isNotEmpty()) appendLog("Steam's library: added ${added.size} game(s) downloaded by the app (${added.joinToString { names.nameOf(it) }}); Play in Steam starts them.")
            }.onFailure { appendLog("Couldn't update Steam's library: ${it.message}") }
        }
    }

    /** Leaves the game display up once it is showing. Loading text is only for before that. */
    private fun keepShownGame(title: String, step: String) {
        if (_stage.value is LaunchStage.Opening || _stage.value is LaunchStage.Running) return
        _stage.value = LaunchStage.Working(title, step)
    }

    /**
     * The game screen is up before the game draws (see [DirectLaunch]). Until it draws, the stats at the top of the
     * game screen say what Play waits for (Steam signing in, Steam starting the game, the game loading), and every
     * 15 seconds the log says what runs; then the start-time line (see [noteOnScreen]). Only a window of the game's
     * own exe ([exeName]) counts.
     */
    private fun watchOnScreen(ctx: Context, l: GameLauncher, title: String, exeName: String) {
        val play = playGen
        val cls = exeName.lowercase()
        scope.launch {
            val started = System.currentTimeMillis()
            var lastNote = 0L
            var lastGameLog = ""
            try {
                while (play == playGen && _stage.value.let { it !is LaunchStage.Idle && it !is LaunchStage.Failed }) {
                    if (BuiltInDisplay.gameWindow.value && BuiltInDisplay.expectedWindowClass == cls) {
                        gameShownAt = System.currentTimeMillis()
                        noteOnScreen(title)
                        return@launch
                    }
                    val secs = (System.currentTimeMillis() - started) / 1000
                    if (secs >= BUILT_IN_TIMEOUT_S) { appendLog("No game window after ${secs}s. The game display stays open."); return@launch }
                    val wait = steamWait(exeName)
                    BuiltInDisplay.waitingText = DirectLaunch.waitingNote(title, secs, wait, SteamWindows.lastSignInS(ctx, steamCoreOnly))
                    if (secs - lastNote >= 15) {
                        lastNote = secs
                        appendLog(
                            "[waiting ${secs}s] $title hasn't drawn its window yet (${BuiltInDisplay.clientCount()} X clients on the display)" + when (wait) {
                                DirectLaunch.Wait.STEAM_SIGNING_IN -> "; Steam is still signing in"
                                DirectLaunch.Wait.STEAM_STARTING_GAME -> "; Steam is starting it"
                                DirectLaunch.Wait.NONE -> ""
                            }
                        )
                        withContext(Dispatchers.IO) { l.processes() }.takeIf { it.isNotEmpty() }?.let { appendLog("  running: " + it.joinToString(", ")) }
                        appendLog("  " + withContext(Dispatchers.IO) { memoryNote() })
                        appendBackgroundErrors()
                        // What the game itself says while it loads (Unity's Player.log), so a hang shows where.
                        withContext(Dispatchers.IO) { gameLogTail(4) }.takeIf { it.isNotEmpty() && it.joinToString() != lastGameLog }?.let { t ->
                            lastGameLog = t.joinToString()
                            appendLog("  game says:"); t.forEach { appendLog("    $it") }
                        }
                    }
                    delay(500)
                }
            } finally {
                if (play == playGen) BuiltInDisplay.waitingText = null
            }
        }
    }

    /** What the game [exeName] on its screen waits for before it runs (see [watchOnScreen]). */
    private fun steamWait(exeName: String): DirectLaunch.Wait = when {
        (steamEnv != null || steamRestarting) && !steamSignedIn -> DirectLaunch.Wait.STEAM_SIGNING_IN
        applaunchGame.equals(exeName, ignoreCase = true) && sessionGame == null -> DirectLaunch.Wait.STEAM_STARTING_GAME
        else -> DirectLaunch.Wait.NONE
    }

    /**
     * Steam was asked to start [exe] as it started (its own Play command, -applaunch; see [start]): once it has
     * signed in, Steam starts the game itself. When the game's process shows up it becomes the session's game,
     * watched like one the app started ([adoptGame]). If Steam hasn't started it [APPLAUNCH_WAIT_S] seconds after
     * signing in (a Steam dialog nobody sees, an update it wants first…), the app starts it next to Steam itself,
     * as it does for every later game. A game Steam starts after you left its screen is closed.
     */
    private fun watchApplaunch(ctx: Context, l: GameLauncher, exe: File, args: String, title: String, appId: Int, gen: Int) {
        val name = exe.name
        applaunchGame = name
        scope.launch {
            val t0 = System.currentTimeMillis()
            var signedInAt = 0L
            try {
                while (gen == launchGen && applaunchGame == name && steamRunning()) {
                    val now = System.currentTimeMillis()
                    val onScreen = _stage.value.let { it is LaunchStage.Running && !it.vr && it.title == title }
                    if (withContext(Dispatchers.IO) { l.isRunning(name) }) {
                        if (onScreen) adoptGame(ctx, l, exe, args, title, appId, "Steam started $title (${(now - t0) / 1000}s after Play). Steam's window stays off.")
                        else {
                            withContext(Dispatchers.IO) { l.kill(name) }
                            appendLog("Steam started $title after you left its screen: closed it. Steam keeps running, so Play starts the game in it right away.")
                        }
                        return@launch
                    }
                    if (signedInAt == 0L && withContext(Dispatchers.IO) { checkSteamSignIn(l) }) signedInAt = now
                    if (signedInAt == 0L && now - t0 >= STEAM_SIGN_IN_LIMIT_S * 1000L) {
                        appendLog("Steam didn't sign in within ${STEAM_SIGN_IN_LIMIT_S / 60} minutes, so it couldn't start $title.")
                        if (onScreen) _stage.value = LaunchStage.Failed(
                            title, "Steam didn't sign in within ${STEAM_SIGN_IN_LIMIT_S / 60} minutes, so $title couldn't start. Open Steam (Runtime settings → Steam for Windows) to check its sign-in.",
                        )
                        return@launch
                    }
                    val sinceSignIn = if (signedInAt > 0) (now - signedInAt) / 1000 else 0L
                    if (onScreen && sinceSignIn >= APPLAUNCH_WAIT_S) {
                        appendLog("Steam signed in ${sinceSignIn}s ago but hasn't started $title (a Steam dialog off screen, or an update it wants first): the app starts it next to Steam itself.")
                        applaunchGame = null
                        val base = steamEnv ?: return@launch
                        val result = launchInSession(ctx, l, base, exe, args, title, appId)
                        if (!result.ok) { _stage.value = LaunchStage.Failed(title, result.message); return@launch }
                        LastGame.set(ctx, exe, args, title, appId)
                        watchGameExit(ctx, l, name, title)
                        return@launch
                    }
                    // Nobody waits for it on screen any more: if Steam still starts it, it's closed (see above), for a while.
                    if (!onScreen && sinceSignIn >= APPLAUNCH_GIVE_UP_S) return@launch
                    delay(1000)
                }
            } finally {
                if (applaunchGame == name) applaunchGame = null
            }
        }
    }

    /** The game Steam started (see [watchApplaunch]) is the session's game from now on, watched like one the app started. */
    private suspend fun adoptGame(ctx: Context, l: GameLauncher, exe: File, args: String, title: String, appId: Int, note: String) {
        applaunchGame = null
        gameLaunchedAt = System.currentTimeMillis()
        gameGen++
        sessionGame = exe.name
        gameExe = exe.name
        gameTitle = title
        appendLog(note)
        LastGame.set(ctx, exe, args, title, appId)
        foreground(steamLow = true)
        watchMemory(title)
        watchGameExit(ctx, l, exe.name, title)
    }

    /** The game's install folder: <games>/<appId>/..., or the exe's folder for loose imports. */
    private fun gameRoot(l: GameLauncher, exe: File): File {
        val games = l.gamesDir.canonicalPath
        var d = exe.parentFile ?: return exe
        while (d.parentFile != null && d.parentFile!!.canonicalPath != games) d = d.parentFile ?: break
        return if (d.parentFile?.canonicalPath == games) d else exe.parentFile!!
    }

    /** After this, show the game's display even if no window was detected (it may be there after all). */
    private const val WINDOW_TIMEOUT_S = 90
    /** The built-in display knows exactly when the game draws, so it can wait longer for slow first starts. */
    private const val BUILT_IN_TIMEOUT_S = 300
    /** Steam's first start downloads and installs the whole client. */
    private const val STEAM_TIMEOUT_S = 40 * 60
    /** The test of Steam without its browser: gives up when Steam hasn't tried to connect by then, or hasn't signed in by then. */
    private const val CORE_SILENT_S = 40
    private const val CORE_TRIAL_S = 90
    /** The same for a start after the test passed (more patient: a slow network is not the browser's fault). */
    private const val CORE_KNOWN_SILENT_S = 60
    private const val CORE_KNOWN_S = 150
    /** A game gets ready in the background only with at least this much memory free. */
    private const val READY_MIN_FREE_MB = 1000
    /** A game that closed by itself gets ready again only if it had been running at least this long (2 minutes). */
    private const val READY_AGAIN_MIN_RUN_MS = 120_000L
    /** Steam's own Play command (see [watchApplaunch]): how long Steam may take after signing in to start the game before the app starts it itself. */
    private const val APPLAUNCH_WAIT_S = 45L
    /** How long after Steam signed in a game it starts after you left its screen is still closed. */
    private const val APPLAUNCH_GIVE_UP_S = 300L
    /** A Steam that hasn't signed in by then won't by itself (its sign-in needs its window). */
    private const val STEAM_SIGN_IN_LIMIT_S = 240

    /** The latest lines from the game and the display (not our own waiting notes). */
    private fun logTail(n: Int) = _log.value.trimEnd().lines().filter { it.isNotBlank() && !it.startsWith("[waiting") }.takeLast(n).joinToString("\n")

    /** Puts the app's loading screen back in front (after opening the game window behind it). */
    private fun bringBack(ctx: Context) {
        val i = android.content.Intent(ctx, RuntimeActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        runCatching { ctx.startActivity(i) }
    }
}
