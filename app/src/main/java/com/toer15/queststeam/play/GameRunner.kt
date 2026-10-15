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
import com.toer15.queststeam.gamepad.Controllers
import com.toer15.queststeam.gamepad.GamepadBridge
import com.toer15.queststeam.launcher.GameLauncher
import com.toer15.queststeam.launcher.WineRegistry
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

    fun clearLog() { _log.value = "" }

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
    }

    val isRunning get() = launcher?.current?.isAlive == true

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
        if (job?.isActive == true) return
        val app = ctx.applicationContext
        if (SteamClientDownload.isBootstrapped(SteamWindows.dir(l))) {
            play(ctx, SteamWindows.exe(l), SteamWindows.STEAM_ARGS.joinToString(" "), RuntimeActivity.MODE_FLAT, "Steam")
            return
        }
        job = scope.launch {
            ensureSteamClient(l, "Steam")
            _stage.value = LaunchStage.Idle
            play(app, SteamWindows.exe(l), SteamWindows.STEAM_ARGS.joinToString(" "), RuntimeActivity.MODE_FLAT, "Steam")
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
                if (!SteamWindows.isSteam(exe) && SteamWindows.startWithGames(app) && SteamWindows.isInstalled(l) && !SteamWindows.isSignedIn(l)) {
                    // The game needs Steam signed in first: show Steam's sign-in in the app instead.
                    appendLog("Sign in to Steam first: opening Steam. When you're signed in, leave Steam and press Play on $title again.")
                    ensureSteamClient(l, "Steam")
                    start(ctx, SteamWindows.exe(l), SteamWindows.STEAM_ARGS.joinToString(" "), RuntimeActivity.MODE_FLAT, "Steam: sign in, then play $title")
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

    private suspend fun start(ctx: Context, exe: File, args: String, mode: String, title: String) {
        val l = launcher ?: GameLauncher(ctx.applicationContext).also { launcher = it }
        gameStartedAt = System.currentTimeMillis()
        withContext(Dispatchers.IO) {
            // Box64's per-game settings from Winlator (bundled as an asset by CI).
            runCatching {
                val rc = File(l.rootfs, "etc/config.box64rc")
                val bytes = ctx.assets.open("graphics/default.box64rc").use { it.readBytes() }
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
        }
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
                val steam = withContext(Dispatchers.IO) {
                    l.runWine(
                        listOf(SteamWindows.exe(l).path, "-silent") + SteamWindows.STEAM_ARGS, BuiltInDisplay.DISPLAY,
                        SteamWindows.dir(l), env + SteamWindows.translationCacheEnv(l), onExit = { appendLog("[Steam closed ($it)]") },
                    ) { line -> if (steamLines < 30 && line.contains("err:") && !line.contains("fixme")) { steamLines++; appendLog("[steam] $line") } }
                }
                if (!steam.ok) appendLog("Steam didn't start: ${steam.message}")
                else {
                    appendLog("Steam for Windows is starting in the background…")
                    val t0 = System.currentTimeMillis()
                    var signedIn = false
                    while (true) {
                        val secs = (System.currentTimeMillis() - t0) / 1000
                        if (withContext(Dispatchers.IO) { SteamWindows.loggedOnSince(l, offset) }) { signedIn = true; break }
                        if (secs >= 240) break
                        _stage.value = LaunchStage.Working(
                            title, "Starting Steam… ${secs}s",
                            detail = "Steam signs in before the game starts, like on a PC. This takes a minute or two under the Windows translation.",
                        )
                        delay(2000)
                    }
                    appendLog(if (signedIn) "Steam is signed in." else "Steam didn't sign in within 4 minutes; starting the game anyway.")
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
                onExit = { code -> onGameExit(ctx, title, code) }, onLine = ::appendLog,
            )
        }
        appendLog(result.message)
        if (!result.ok) { _stage.value = LaunchStage.Failed(title, result.message); return }
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
                        appendLog("[waiting ${secs}s] the game hasn't drawn its window yet ($n windows on the display)")
                        withContext(Dispatchers.IO) { l.processes() }.takeIf { it.isNotEmpty() }?.let { appendLog("  running: " + it.joinToString(", ")) }
                        if (secs in 45..59) BuiltInDisplay.describeWindows().takeIf { it.isNotEmpty() }?.let { w ->
                            appendLog("[waiting] windows on the game screen:"); w.forEach(::appendLog)
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
                            "Steam's first start downloads and installs Steam itself (a few hundred MB): 10–20 minutes on the headset. " +
                                "When it's done, Steam's window opens here by itself — nothing to press.",
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

    private fun onGameExit(ctx: Context, title: String, code: Int) {
        ControllerRelay.stop(ctx)
        PlayingService.stop(ctx)
        appendNativeLog()
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
        launcher?.processes()?.takeIf { it.isNotEmpty() }?.let { appendLog("[processes] " + it.joinToString(", ")) }
        appendWindows()
        appendSteamLogs()
        appendUnityLog()
        job?.cancel()
        launcher?.stop()
        BuiltInDisplay.stop()
        ControllerRelay.stop(ctx)
        PlayingService.stop(ctx)
        _stage.value = LaunchStage.Idle
        appendLog("[stopped]")
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
