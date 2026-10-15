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
import com.toer15.queststeam.launcher.WineRegistry
import com.toer15.queststeam.launcher.RuntimeCore
import com.toer15.queststeam.launcher.RuntimeInstaller
import com.toer15.queststeam.launcher.XProbe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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
 * time), controller bridge, VR bridge or flat mode, the game display, Wine, then waits until the
 * game has really opened its window before showing it ("Loading…" → "Opening game now").
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

    private fun priorityNow(): Priority = when (val st = _stage.value) {
        is LaunchStage.Running -> if (sessionGame == null && st.title.startsWith("Steam")) Priority.Normal else Priority.SteamLow
        is LaunchStage.Working, is LaunchStage.Opening -> if (sessionGame != null) Priority.SteamLow else Priority.Normal
        else -> if (sessionGame != null) Priority.SteamLow else Priority.AllLow
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
        if (steamRunning() && sessionGame == null) { showSteam(ctx); return }
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

    fun play(ctx: Context, exe: File, args: String, mode: String, title: String) {
        val busy = _stage.value is LaunchStage.Working || _stage.value is LaunchStage.Opening
        if (busy && job?.isActive == true) return
        val app = ctx.applicationContext
        _stage.value = LaunchStage.Working(title, "Getting ready…")
        job = scope.launch {
            try {
                val installer = RuntimeInstaller(app)
                if (!installer.isInstalled) {
                    appendLog("First launch: downloading the Windows runtime (Box64 + Wine, ~157 MB)…")
                    withContext(Dispatchers.IO) {
                        installer.install(::appendLog) { p -> _stage.value = LaunchStage.Working(title, "Downloading the Windows runtime (first time only): ${p.stage}", p.fraction) }
                    }
                    appendLog("Runtime downloaded and installed.")
                }
                val l = launcher ?: GameLauncher(app).also { launcher = it }
                val vrGame = mode == RuntimeActivity.MODE_VR && VrBridge.isVrGame(gameRoot(l, exe))
                if (!SteamWindows.isSteam(exe) && !vrGame && steamRunning()) {
                    // Steam is already running: the game starts inside its session right away.
                    startInSteam(app, exe, args, title)
                    return@launch
                }
                if (SteamWindows.isSteam(exe) && steamRunning()) { showSteam(app); return@launch }
                if (!SteamWindows.isSteam(exe) && SteamWindows.startWithGames(app) && SteamWindows.isInstalled(l) && !SteamWindows.isSignedIn(l)) {
                    // The game needs Steam signed in first: show Steam's sign-in in the app instead.
                    appendLog("Sign in to Steam first: opening Steam. When you're signed in, leave Steam and press Play on $title again.")
                    ensureSteamClient(l, "Steam")
                    start(ctx, SteamWindows.exe(l), SteamWindows.args(app).joinToString(" "), RuntimeActivity.MODE_FLAT, "Steam: sign in, then play $title")
                } else start(ctx, exe, args, mode, title)
                if (SteamWindows.isSteam(exe) || _stage.value.let { it is LaunchStage.Running && it.title.startsWith("Steam") }) watchSteamSignIn(l)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                appendLog("Couldn't start: $e")
                _stage.value = LaunchStage.Failed(title, e.message ?: e.toString())
            }
        }
    }

    private suspend fun start(ctx: Context, requested: File, args: String, mode: String, title: String) {
        val l = launcher ?: GameLauncher(ctx.applicationContext).also { launcher = it }
        val gen = ++launchGen
        steamEnv = null
        sessionGame = null
        gameStartedAt = System.currentTimeMillis()
        markBackgroundErrors()
        withContext(Dispatchers.IO) {
            runCatching { SteamWindows.dedupeExe(l)?.let { appendLog("Steam folder: $it (two copies of steam.exe).") } }
            // Box64's per-game settings from Winlator (bundled as an asset by CI).
            runCatching {
                val rc = File(l.rootfs, "etc/config.box64rc")
                // Without Winlator's pre-exec hook (see RuntimeCore.disablePreExec): it made Steam.exe wait forever.
                val bytes = RuntimeCore.sanitizeBox64Rc(ctx.assets.open("graphics/default.box64rc").use { String(it.readBytes()) }).toByteArray()
                if (!rc.isFile || !rc.readBytes().contentEquals(bytes)) { rc.parentFile?.mkdirs(); rc.writeBytes(bytes) }
            }
            // DXVK settings, as Winlator writes them (constant buffer range checks avoid out-of-bounds reads).
            runCatching {
                File(l.rootfs, "home/xuser/.config/dxvk.conf").apply { parentFile?.mkdirs() }
                    .writeText("d3d11.constantBufferRangeCheck = \"True\"\n")
            }
            val k = l.killLeftovers()
            if (k > 0) appendLog("Closed $k Wine process(es) left over from the last game.")
            runCatching { NetworkInfo.write(ctx, l.rootfs) }.onFailure { appendLog("Couldn't write the network info for Wine: ${it.message}") }
            // Games started by Steam itself get the same DLLs as the app's own launches: Winlator's
            // settings replace WINEDLLOVERRIDES for steam.exe, and Steam's games inherit that.
            runCatching {
                val reg = File(l.prefix, "user.reg")
                for ((dll, order) in RuntimeCore.dllOverrides(l.environment(null)["WINEDLLOVERRIDES"].orEmpty())) {
                    WineRegistry.setString(reg, "Software\\Wine\\DllOverrides", dll, order)
                }
            }.onFailure { appendLog("Couldn't write Wine's DLL settings: ${it.message}") }
            if (SteamWindows.isInstalled(l)) runCatching {
                SteamWindows.keepOffGpu(l)
                SteamWindows.applyFastSettings(l)
                val names = DownloadManager.get(ctx)
                val added = SteamLibrary.register(SteamWindows.dir(l), l.gamesDir) { id -> names.nameOf(id).takeIf { it != "game" } }
                if (added.isNotEmpty()) appendLog("Steam's library: added ${added.size} game(s) downloaded by the app (${added.joinToString { names.nameOf(it) }}); Play in Steam starts them.")
            }.onFailure { appendLog("Couldn't update Steam's library: ${it.message}") }
        }
        // The copy of steam.exe that was asked for may have been the stale one removed above.
        val exe = if (SteamWindows.isSteam(requested) && !requested.isFile) SteamWindows.exe(l) else requested
        val disp = display(ctx)
        val gameDir = gameRoot(l, exe)
        lastGameDir = exe.parentFile
        val env = mutableMapOf<String, String>()
        val wantVr = mode == RuntimeActivity.MODE_VR
        _stage.value = LaunchStage.Working(title, "Preparing ${if (wantVr) "VR" else "flat screen"} mode…")
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
            _stage.value = LaunchStage.Working(title, "Starting the game display…")
            // The game's own windows have its exe name as their class; Steam's UI is drawn by other processes.
            BuiltInDisplay.expectedWindowClass = if (SteamWindows.isSteam(exe)) null else exe.name.lowercase()
            BuiltInDisplay.expectedUiWindowClass = if (SteamWindows.isSteam(exe)) SteamWindows.UI_WINDOW_CLASS else null
            if (SteamWindows.isSteam(exe)) env.putAll(SteamWindows.translationCacheEnv(l))
            // No Steam overlay in games (too heavy under Box64).
            env["WINEDLLOVERRIDES"] = (l.environment(null)["WINEDLLOVERRIDES"] ?: "") + ";" + SteamWindows.DLL_OVERRIDES
            val useVortek = Vortek.selected(ctx) == Vortek.VORTEK && withContext(Dispatchers.IO) {
                runCatching { Vortek.prepare(ctx, l.rootfs, ::appendLog) }
                    .onFailure { appendLog("Vortek GPU driver setup failed: ${it.message}") }.getOrDefault(false)
            }
            env.putAll(BuiltInDisplay.start(ctx, l.rootfs, useVortek, ::appendLog))
            setWineAudio(l, env.containsKey("ANDROID_ALSA_SERVER"))
        } else if (termux) {
            if (!GameDisplay.isAppInstalled(ctx)) {
                appendLog("The game display app (Termux:X11) isn't installed. Install it, or clear the display setting to use the built-in display.")
                _needsDisplay.value = true
                _stage.value = LaunchStage.Idle
                return
            }
            _stage.value = LaunchStage.Working(title, "Starting the game display…")
            val problem = withContext(Dispatchers.IO) { GameDisplay.ensureRunning(ctx, l.rootfs, ::appendLog) }
            if (problem != null) { appendLog(problem); _stage.value = LaunchStage.Failed(title, problem); return }
            setWineAudio(l, false) // no sound server with Termux:X11: no audio device instead of a broken one
            GameDisplay.configureApp(ctx)
            if (!inVr) {
                GameDisplay.show(ctx)
                delay(1500)
                bringBack(ctx)
            }
            baseline = withContext(Dispatchers.IO) { XProbe.visibleWindows()?.size ?: 0 }
        }

        // Steam for Windows in the background, for games that need the Steam client (Steamworks).
        if (builtIn && !SteamWindows.isSteam(exe) && SteamWindows.startWithGames(ctx) && SteamWindows.isInstalled(l)) {
            val appId = gameDir.name.toIntOrNull() ?: 0
            if (!SteamWindows.isSignedIn(l)) {
                appendLog("Steam for Windows isn't signed in yet (Runtime settings → Steam for Windows → Open Steam). Starting the game without it.")
            } else {
                _stage.value = LaunchStage.Working(title, "Starting Steam…")
                val offset = SteamWindows.connectionLog(l).let { if (it.isFile) it.length() else 0L }
                var steamLines = 0
                val onSteamLine: (String) -> Unit = { line ->
                    val keep = (line.contains("err:") || line.startsWith("wine:") || line.contains("Box64", ignoreCase = true)) && !line.contains("fixme")
                    if (steamLines < 60 && keep) { steamLines++; appendLog("[steam] $line") }
                }
                val steamExe = SteamWindows.exe(l)
                val steam = withContext(Dispatchers.IO) {
                    // Flat games: Steam gets the game desktop and keeps running after the game (see steamEnv),
                    // so the next game starts at once. VR games keep the old way (Steam beside the game).
                    if (inVr) l.runWine(
                        listOf(steamExe.path, "-silent") + SteamWindows.args(ctx), BuiltInDisplay.DISPLAY,
                        SteamWindows.dir(l), env + SteamWindows.translationCacheEnv(l), onExit = { appendLog("[Steam closed ($it)]") }, onLine = onSteamLine,
                    ) else l.launchExe(
                        steamExe, BuiltInDisplay.DISPLAY, env + SteamWindows.translationCacheEnv(l), listOf("-silent") + SteamWindows.args(ctx),
                        desktop = BuiltInDisplay.SCREEN, onExit = { code -> if (gen == launchGen) onGameExit(ctx, "Steam", code) }, onLine = onSteamLine,
                    )
                }
                if (!steam.ok) appendLog("Steam didn't start: ${steam.message}")
                else {
                    appendLog("Steam for Windows is starting in the background…")
                    val t0 = System.currentTimeMillis()
                    var signedIn = false
                    while (true) {
                        val secs = (System.currentTimeMillis() - t0) / 1000
                        if (withContext(Dispatchers.IO) { SteamWindows.loggedOnSince(l, offset) }) { signedIn = true; break }
                        if (!inVr && !isRunning) { appendLog("Steam closed while starting."); return }
                        if (secs >= 240) break
                        _stage.value = LaunchStage.Working(
                            title, "Starting Steam… ${secs}s",
                            detail = "Steam signs in before the game starts, like on a PC. This takes a minute or two under the Windows translation.",
                        )
                        delay(2000)
                    }
                    appendLog(if (signedIn) "Steam is signed in." else "Steam didn't sign in within 4 minutes; starting the game anyway.")
                    if (!inVr) {
                        steamEnv = env.toMap()
                        watchPriority()
                        startInSteam(ctx, exe, args, title)
                        return
                    }
                    if (appId > 0) {
                        withContext(Dispatchers.IO) { SteamWindows.writeAppId(exe.parentFile ?: gameDir, appId) }
                        env["SteamAppId"] = appId.toString()
                        env["SteamGameId"] = appId.toString()
                    }
                }
            }
        }

        _stage.value = LaunchStage.Working(title, "Starting $title…")
        val argv = GameLauncher.splitArgs(args)
        if (argv.isNotEmpty() && !SteamWindows.isSteam(exe)) appendLog("Launch options from Steam: $args")
        val result = withContext(Dispatchers.IO) {
            l.launchExe(
                exe, if (builtIn) BuiltInDisplay.DISPLAY else disp, env, argv,
                desktop = if (builtIn) BuiltInDisplay.SCREEN else null,
                onExit = { code -> if (gen == launchGen) onGameExit(ctx, title, code) }, onLine = ::appendLog,
            )
        }
        appendLog(result.message)
        if (!result.ok) { _stage.value = LaunchStage.Failed(title, result.message); return }
        // Steam itself: its session stays up for games (see steamEnv); games don't get Steam's translation cache.
        if (builtIn && SteamWindows.isSteam(exe)) {
            steamEnv = env - SteamWindows.translationCacheEnv(l).keys
            watchPriority()
        }
        PlayingService.start(ctx, title)

        if (inVr) {
            _stage.value = LaunchStage.Working(title, "Opening the VR view…")
            delay(2500)
            VrBridge.enterVr(ctx)
            _stage.value = LaunchStage.Running(title, vr = true)
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
                _stage.value = LaunchStage.Working(title, "Loading $title… ${secs}s", detail = logTail(5), canShowWindow = secs >= 60)
                delay(500)
            }
            _stage.value = LaunchStage.Opening(title)
            delay(900)
            if (termux) GameDisplay.show(ctx)
        }
        _stage.value = LaunchStage.Running(title, vr = false)
        if (termux && pads.isNotEmpty()) {
            // The Termux:X11 window gets focus; the relay badge keeps the controller going to the game.
            if (ControllerRelay.canOverlay(ctx)) { delay(800); ControllerRelay.start(ctx) }
            else appendLog("To use the controller while the game window is in front, allow \"Display over other apps\" for Quest Steam.")
        }
    }

    /**
     * Starts a game inside Steam's running session (same display, wineserver and environment): no
     * Steam restart. The game's own Windows process is watched (the launcher process only hands it to
     * the session's desktop); when it ends you're back in the app and Steam keeps running.
     */
    private suspend fun startInSteam(ctx: Context, exe: File, args: String, title: String) {
        val l = launcher ?: return
        val base = steamEnv ?: return
        gameStartedAt = System.currentTimeMillis()
        lastGameDir = exe.parentFile
        sessionGame?.let { old -> withContext(Dispatchers.IO) { l.kill(old) } }
        val gameDir = gameRoot(l, exe)
        val env = base.toMutableMap()
        val appId = gameDir.name.toIntOrNull() ?: 0
        if (appId > 0) {
            withContext(Dispatchers.IO) { SteamWindows.writeAppId(exe.parentFile ?: gameDir, appId) }
            env["SteamAppId"] = appId.toString()
            env["SteamGameId"] = appId.toString()
        }
        appendLog("Starting $title next to the running Steam.")
        markBackgroundErrors()
        appendLog(withContext(Dispatchers.IO) { memoryNote() })
        foreground(steamLow = true)
        BuiltInDisplay.expectWindow(exe.name.lowercase(), null)
        _stage.value = LaunchStage.Working(title, "Starting $title…")
        val argv = GameLauncher.splitArgs(args)
        if (argv.isNotEmpty()) appendLog("Launch options from Steam: $args")
        // Its own game desktop on the same display (Steam's desktop belongs to Steam's explorer).
        val result = withContext(Dispatchers.IO) {
            l.launchExe(exe, BuiltInDisplay.DISPLAY, env, argv, desktop = BuiltInDisplay.SCREEN, track = false, desktopName = nextDesktop("game"), onLine = ::appendLog)
        }
        if (!result.ok) { appendLog(result.message); _stage.value = LaunchStage.Failed(title, result.message); return }
        val name = exe.name
        sessionGame = name
        PlayingService.start(ctx, title)
        val started = System.currentTimeMillis()
        var seenAt = 0L
        var lastNote = 0L
        var lastGameLog = ""
        showNow = false
        while (true) {
            if (!steamRunning()) return // onGameExit reports it
            if (showNow || BuiltInDisplay.gameWindow.value) break
            val now = System.currentTimeMillis()
            val secs = (now - started) / 1000
            if (withContext(Dispatchers.IO) { l.isRunning(name) }) seenAt = now
            // Ended: its process has been gone for a while after running (Wine renames a process
            // while it starts), or it never showed up at all.
            if (if (seenAt > 0) now - seenAt > 8_000 else secs >= 60) {
                sessionGame = null
                appendLog("$title closed while starting${if (seenAt > 0) "" else " (it never appeared)"}.")
                appendLog(withContext(Dispatchers.IO) { memoryNote() })
                withContext(Dispatchers.IO) { appendNativeLog(); appendUnityLog(); appendDxvkLogs() }
                appendBackgroundErrors()
                _stage.value = LaunchStage.Failed(title, "The game closed while starting. The log below shows why.")
                return
            }
            if (secs - lastNote >= 15) {
                lastNote = secs
                appendLog("[waiting ${secs}s] $title hasn't drawn its window yet (${BuiltInDisplay.clientCount()} X clients on the display)")
                withContext(Dispatchers.IO) { l.processes() }.takeIf { it.isNotEmpty() }?.let { appendLog("  running: " + it.joinToString(", ")) }
                appendLog("  " + withContext(Dispatchers.IO) { memoryNote() })
                appendBackgroundErrors()
                // What the game itself says while it loads (Unity's Player.log), so a hang shows where.
                withContext(Dispatchers.IO) { gameLogTail(4) }.takeIf { it.isNotEmpty() && it.joinToString() != lastGameLog }?.let { t ->
                    lastGameLog = t.joinToString()
                    appendLog("  game says:"); t.forEach { appendLog("    $it") }
                }
            }
            if (secs >= BUILT_IN_TIMEOUT_S) { appendLog("No game window after ${secs}s; showing the game display anyway."); break }
            _stage.value = LaunchStage.Working(title, "Loading $title… ${secs}s", detail = logTail(5), canShowWindow = secs >= 60)
            delay(500)
        }
        _stage.value = LaunchStage.Opening(title)
        delay(900)
        _stage.value = LaunchStage.Running(title, vr = false)
        scope.launch {
            var misses = 0
            while (sessionGame == name && steamRunning()) {
                delay(2000)
                misses = if (withContext(Dispatchers.IO) { l.isRunning(name) }) 0 else misses + 1
                if (misses >= 3) break
            }
            if (sessionGame != name) return@launch // left with "Leave game", or another game started
            sessionGame = null
            if (!steamRunning()) return@launch
            appendLog("$title closed. Steam keeps running in the background, so the next game starts faster.")
            withContext(Dispatchers.IO) { appendUnityLog() }
            BuiltInDisplay.expectWindow(null, SteamWindows.UI_WINDOW_CLASS)
            PlayingService.start(ctx, "Steam")
            if (_stage.value.let { it is LaunchStage.Running && it.title == title }) _stage.value = LaunchStage.Idle
        }
    }

    /**
     * Shows the running Steam: Steam started in the background (-silent) has no window yet, so it's
     * asked to open its main window (a second steam.exe hands the request to the running one).
     */
    private fun showSteam(ctx: Context) {
        val l = launcher ?: return
        val env = steamEnv ?: return
        BuiltInDisplay.expectWindow(null, SteamWindows.UI_WINDOW_CLASS)
        scope.launch {
            foreground(steamLow = false)
            withContext(Dispatchers.IO) {
                l.launchExe(SteamWindows.exe(l), BuiltInDisplay.DISPLAY, env, listOf("steam://open/main"), desktop = BuiltInDisplay.SCREEN, track = false, desktopName = nextDesktop("steam")) { }
            }
        }
        PlayingService.start(ctx, "Steam")
        _stage.value = LaunchStage.Running("Steam", vr = false)
    }

    /**
     * "Leave" on the game screen. A game started in Steam's session closes and Steam keeps running;
     * leaving Steam's own screen keeps Steam running in the background (Open Steam shows it again,
     * and games start in it at once). Anything else stops completely.
     */
    fun leave(ctx: Context) {
        val l = launcher
        val game = sessionGame
        when {
            game != null && steamRunning() -> {
                sessionGame = null
                val n = l?.kill(game) ?: 0
                appendLog("Closed $game ($n process(es)). Steam keeps running in the background.")
                BuiltInDisplay.expectWindow(null, SteamWindows.UI_WINDOW_CLASS)
                PlayingService.start(ctx, "Steam")
                _stage.value = LaunchStage.Idle
            }
            steamRunning() -> {
                appendLog("Steam keeps running in the background: Open Steam shows it again, and games start in it right away.")
                _stage.value = LaunchStage.Idle
            }
            else -> stop(ctx)
        }
    }

    private fun onGameExit(ctx: Context, title: String, code: Int) {
        // The session's main process ended (the game, or Steam with any game started in it).
        steamEnv = null
        sessionGame = null
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
            _stage.value = when (_stage.value) {
                is LaunchStage.Working, is LaunchStage.Opening ->
                    LaunchStage.Failed(title, "The game closed while starting (code $code). The log below shows why.")
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

    /** The last [n] lines the game wrote to its Unity Player.log since it started (nothing for other games). */
    private fun gameLogTail(n: Int): List<String> {
        val l = launcher ?: return emptyList()
        val since = gameStartedAt
        val log = File(l.prefix, "drive_c/users").listFiles().orEmpty()
            .mapNotNull { File(it, "AppData/LocalLow").takeIf(File::isDirectory) }
            .flatMap { low -> low.walkTopDown().maxDepth(3).filter { it.name == "Player.log" && it.lastModified() >= since - 5_000 }.toList() }
            .maxByOrNull { it.lastModified() } ?: return emptyList()
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
        val gen = ++launchGen
        val l = launcher
        val steam = steamEnv?.takeIf { steamRunning() }
        steamEnv = null
        sessionGame = null
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

    /** The latest lines from the game and the display (not our own waiting notes). */
    private fun logTail(n: Int) = _log.value.trimEnd().lines().filter { it.isNotBlank() && !it.startsWith("[waiting") }.takeLast(n).joinToString("\n")

    /** Puts the app's loading screen back in front (after opening the game window behind it). */
    private fun bringBack(ctx: Context) {
        val i = android.content.Intent(ctx, RuntimeActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        runCatching { ctx.startActivity(i) }
    }
}
