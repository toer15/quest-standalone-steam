package com.toer15.queststeam.play

import android.content.Context
import com.toer15.queststeam.RuntimeActivity
import com.toer15.queststeam.VrBridge
import com.toer15.queststeam.display.GameDisplay
import com.toer15.queststeam.display.PlayingService
import com.toer15.queststeam.gamepad.ControllerRelay
import com.toer15.queststeam.gamepad.Controllers
import com.toer15.queststeam.gamepad.GamepadBridge
import com.toer15.queststeam.launcher.GameLauncher
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

    fun appendLog(line: String) {
        val s = _log.value + line + "\n"
        _log.value = if (s.length > 120_000) s.takeLast(100_000) else s
    }

    val isRunning get() = launcher?.current?.isAlive == true

    /** The display games use (Runtime screen setting; default = the built-in game display). */
    fun display(ctx: Context): String? =
        (ctx.getSharedPreferences("runtime", 0).getString("display", GameDisplay.DISPLAY) ?: GameDisplay.DISPLAY).trim().ifBlank { null }

    fun dismissFailure() { if (_stage.value is LaunchStage.Failed) _stage.value = LaunchStage.Idle }

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
                start(ctx, exe, args, mode, title)
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
        val disp = display(ctx)
        val gameDir = gameRoot(l, exe)
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

        // The game's window: start the display server ourselves (no Termux needed). VR games need it too.
        val localDisplay = disp != null && (disp.startsWith("127.0.0.1") || disp.startsWith("localhost") || disp.startsWith(":"))
        var baseline = 0
        if (localDisplay) {
            if (!GameDisplay.isAppInstalled(ctx)) {
                appendLog("The game display app (Termux:X11) isn't installed, so the game would have no window. Tap \"Install game display app\", then Play again.")
                _needsDisplay.value = true
                _stage.value = LaunchStage.Idle
                return
            }
            _stage.value = LaunchStage.Working(title, "Starting the game display…")
            val problem = withContext(Dispatchers.IO) { GameDisplay.ensureRunning(ctx, l.rootfs, ::appendLog) }
            if (problem != null) { appendLog(problem); _stage.value = LaunchStage.Failed(title, problem); return }
            baseline = withContext(Dispatchers.IO) { XProbe.visibleWindows()?.size ?: 0 }
        }

        _stage.value = LaunchStage.Working(title, "Starting $title…")
        val argv = GameLauncher.splitArgs(args)
        if (argv.isNotEmpty()) appendLog("Launch options from Steam: $args")
        val result = withContext(Dispatchers.IO) {
            l.launchExe(exe, disp, env, argv, onExit = { code -> onGameExit(ctx, title, code) }, onLine = ::appendLog)
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
        if (localDisplay) {
            // Loading screen until the game really has a window (Unity/Unreal games take a while under Box64).
            val started = System.currentTimeMillis()
            var lastNote = 0L
            while (true) {
                if (!isRunning) return // onGameExit reports it
                val secs = (System.currentTimeMillis() - started) / 1000
                val windows = withContext(Dispatchers.IO) { XProbe.visibleWindows() }
                if ((windows?.size ?: 0) > baseline) {
                    appendLog("Game window is open (${windows!!.joinToString { "${it.width}x${it.height}" }}).")
                    break
                }
                if (secs - lastNote >= 15) {
                    lastNote = secs
                    appendLog(
                        if (windows == null) "[waiting ${secs}s] can't ask the game display for windows: ${XProbe.lastError}"
                        else "[waiting ${secs}s] the game hasn't opened its window yet (${windows.size} on the display)"
                    )
                }
                if (secs >= WINDOW_TIMEOUT_S) { appendLog("No game window detected after ${secs}s; showing the game display."); break }
                _stage.value = LaunchStage.Working(title, "Loading $title… ${secs}s", detail = logTail(3), canShowWindow = secs >= 20)
                delay(700)
            }
            _stage.value = LaunchStage.Opening(title)
            delay(1200)
            GameDisplay.show(ctx)
        }
        _stage.value = LaunchStage.Running(title, vr = false)
        if (pads.isNotEmpty() && localDisplay) {
            // The game window gets focus; the relay badge keeps the controller going to the game.
            if (ControllerRelay.canOverlay(ctx)) { delay(800); ControllerRelay.start(ctx) }
            else appendLog("To use the controller while the game window is in front, allow \"Display over other apps\" for Quest Steam.")
        }
    }

    private fun onGameExit(ctx: Context, title: String, code: Int) {
        ControllerRelay.stop(ctx)
        PlayingService.stop(ctx)
        scope.launch {
            _stage.value = when (_stage.value) {
                is LaunchStage.Working, is LaunchStage.Opening ->
                    LaunchStage.Failed(title, "The game closed while starting (code $code). The log below shows why.")
                else -> LaunchStage.Idle
            }
        }
    }

    fun stop(ctx: Context) {
        job?.cancel()
        launcher?.stop()
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

    private fun logTail(n: Int) = _log.value.trimEnd().lines().filter { it.isNotBlank() }.takeLast(n).joinToString("\n")
}
