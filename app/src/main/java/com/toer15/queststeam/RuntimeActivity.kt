package com.toer15.queststeam

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import com.toer15.queststeam.display.BuiltInDisplay
import com.toer15.queststeam.display.GameDisplay
import com.toer15.queststeam.display.GameSurface
import com.toer15.queststeam.display.LeaveGameDialog
import com.toer15.queststeam.display.gameKeyEvent
import com.toer15.queststeam.display.GameDisplayRow
import com.toer15.queststeam.display.Vortek
import com.toer15.queststeam.display.PlayingService
import com.toer15.queststeam.gamepad.ControllerRelay
import com.toer15.queststeam.play.GameRunner
import com.toer15.queststeam.play.LaunchStage
import com.toer15.queststeam.play.LaunchOverlay
import com.toer15.queststeam.gamepad.Controllers
import com.toer15.queststeam.gamepad.GamepadBridge
import com.toer15.queststeam.ui.ControllerStatus
import com.toer15.queststeam.ui.OverlayPermissionRow
import com.toer15.queststeam.ui.focusRing
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.toer15.queststeam.launcher.DeviceMode
import com.toer15.queststeam.launcher.GameLauncher
import com.toer15.queststeam.launcher.RuntimeInstaller
import com.toer15.queststeam.ui.LocalDeviceUi
import com.toer15.queststeam.ui.SteamColors
import com.toer15.queststeam.ui.SteamTheme
import com.toer15.queststeam.ui.formatBytes
import com.toer15.queststeam.ui.steamBackdrop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Windows runtime screen (Box64 + Wine): download/delete the runtime, smoke test, run games, logs. */
class RuntimeActivity : ComponentActivity() {

    companion object {
        const val EXTRA_EXE = "exe"
        const val EXTRA_TITLE = "title"
        const val EXTRA_ARGS = "args"
        /** [MODE_VR] or [MODE_FLAT], from the Play popup. */
        const val EXTRA_MODE = "mode"
        const val MODE_VR = "vr"
        const val MODE_FLAT = "flat"
        /** Steam app id, for the game's picture on the loading screen. */
        const val EXTRA_APPID = "appid"
    }

    private val vm by lazy { androidx.lifecycle.ViewModelProvider(this)[RuntimeViewModel::class.java] }

    // While a game runs and this screen is in front, the controller goes to the game.
    // While a game runs: controller → game (XInput); with the game on screen, keys → the game's keyboard
    // (Back still asks "Leave game?").
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!vm.gameRunning) return super.dispatchKeyEvent(event)
        val gameOnScreen = BuiltInDisplay.view?.isAttachedToWindow == true
        return (if (gameOnScreen) gameKeyEvent(event) else GamepadBridge.onKey(event)) || super.dispatchKeyEvent(event)
    }

    override fun onPause() {
        super.onPause()
        BuiltInDisplay.view?.let { if (it.isAttachedToWindow) it.onPause() }
    }

    override fun onResume() {
        super.onResume()
        BuiltInDisplay.view?.let { if (it.isAttachedToWindow) it.onResume() }
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean =
        (vm.gameRunning && GamepadBridge.onMotion(event)) || super.dispatchGenericMotionEvent(event)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = 0xFF0E141B.toInt()
        window.navigationBarColor = 0xFF070B10.toInt()
        val exe = intent.getStringExtra(EXTRA_EXE)
        val title = intent.getStringExtra(EXTRA_TITLE)
        val args = intent.getStringExtra(EXTRA_ARGS).orEmpty()
        val mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_VR
        val appId = intent.getIntExtra(EXTRA_APPID, 0)
        setContent { SteamTheme { RuntimeScreen(title, exe, args, mode, appId, onClose = { finish() }) } }
    }
}

/** Runtime state that survives panel resizes. */
class RuntimeViewModel(app: Application) : AndroidViewModel(app) {
    val launcher = GameLauncher(app)
    private val installer = RuntimeInstaller(app)
    private val prefs = app.getSharedPreferences("runtime", 0)

    /** Shared with the game starter (GameRunner), so the VR panel and this screen show the same log. */
    val log: StateFlow<String> = GameRunner.log
    private val _installed = MutableStateFlow(installer.isInstalled)
    val installed: StateFlow<Boolean> = _installed
    private val _needsUpdate = MutableStateFlow(installer.needsUpdate)
    val needsUpdate: StateFlow<Boolean> = _needsUpdate
    private val _progress = MutableStateFlow<RuntimeInstaller.Progress?>(null)
    val progress: StateFlow<RuntimeInstaller.Progress?> = _progress
    private val _size = MutableStateFlow(-1L)
    val size: StateFlow<Long> = _size
    private var startedPlay = false
    val stage: StateFlow<LaunchStage> = GameRunner.stage

    var display: String
        get() = prefs.getString("display", BuiltInDisplay.DISPLAY) ?: BuiltInDisplay.DISPLAY
        set(v) { prefs.edit().putString("display", v).apply() }

    /** Set when a game can't start because the game display app isn't installed (shows the install popup). */
    val needsDisplay: StateFlow<Boolean> = GameRunner.needsDisplay
    fun dismissDisplayPrompt() = GameRunner.dismissDisplayPrompt()

    init { refresh() }

    fun appendLog(line: String) = GameRunner.appendLog(line)

    fun refresh() {
        _installed.value = installer.isInstalled
        _needsUpdate.value = installer.needsUpdate
        viewModelScope.launch {
            _size.value = if (installer.isInstalled) withContext(Dispatchers.IO) { runCatching { installer.sizeOnDisk() }.getOrDefault(-1L) } else -1L
        }
    }

    fun install(onInstalled: () -> Unit = {}) {
        if (_progress.value != null) return
        _progress.value = RuntimeInstaller.Progress("Starting…", 0f)
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { installer.install(::appendLog) { _progress.value = it } }
                appendLog("Runtime downloaded and installed.")
                refresh()
                onInstalled()
            } catch (e: Exception) {
                appendLog("Download failed: $e")
                e.cause?.let { appendLog("  cause: $it") }
            } finally {
                _progress.value = null
                refresh()
            }
        }
    }

    fun delete() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { installer.delete(::appendLog) }
            refresh()
        }
    }

    fun smokeTest() {
        viewModelScope.launch(Dispatchers.IO) {
            appendLog("Smoke test: wine cmd /c ver (should print a Windows version)")
            appendLog(launcher.smokeTest(::appendLog).message)
        }
    }

    fun stop() = GameRunner.stop(getApplication<Application>())

    /** True while a started game's process is alive. */
    val gameRunning get() = GameRunner.isRunning

    /** Starts a game (see GameRunner): the loading screen follows [stage]. */
    fun launchGame(ctx: Context, exe: File, args: String = "", mode: String = RuntimeActivity.MODE_VR, title: String = exe.nameWithoutExtension) =
        GameRunner.play(ctx, exe, args, mode, title)

    /** Play from a game card: once per screen (rotating the panel doesn't start it again). */
    fun playOnce(ctx: Context, exePath: String, args: String, mode: String, title: String?) {
        if (startedPlay) return
        startedPlay = true
        val exe = File(exePath)
        launchGame(ctx, exe, args, mode, title ?: exe.nameWithoutExtension)
    }

    fun importGame(ctx: Context, uri: Uri, isTree: Boolean) {
        viewModelScope.launch {
            appendLog("Importing $uri …")
            val dest = withContext(Dispatchers.IO) {
                runCatching {
                    launcher.gamesDir.mkdirs()
                    if (isTree) {
                        val doc = DocumentFile.fromTreeUri(ctx, uri)!!
                        val dir = File(launcher.gamesDir, doc.name ?: "game").apply { mkdirs() }
                        copyTree(ctx, doc, dir)
                        dir
                    } else {
                        val name = DocumentFile.fromSingleUri(ctx, uri)?.name ?: "game.exe"
                        val dir = File(launcher.gamesDir, name.substringBeforeLast('.')).apply { mkdirs() }
                        ctx.contentResolver.openInputStream(uri)!!.use { i -> File(dir, name).outputStream().use { i.copyTo(it) } }
                        dir
                    }
                }
            }
            dest.onSuccess { appendLog("Imported to $it") }.onFailure { appendLog("Import failed: $it") }
        }
    }

    private fun copyTree(ctx: Context, doc: DocumentFile, dir: File) {
        for (child in doc.listFiles()) {
            val name = child.name ?: continue
            if (child.isDirectory) copyTree(ctx, child, File(dir, name).apply { mkdirs() })
            else ctx.contentResolver.openInputStream(child.uri)?.use { i -> File(dir, name).outputStream().use { i.copyTo(it) } }
        }
    }

    fun importedExes(): List<File> = launcher.findExes(launcher.gamesDir)


}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RuntimeScreen(title: String?, exePath: String?, args: String, mode: String, appId: Int, onClose: () -> Unit, vm: RuntimeViewModel = viewModel()) {
    // While the game runs on the built-in display, this window shows the game itself; when the game
    // ends (or you leave it) you're back in the app: the library if you came from a game card.
    val playStage by vm.stage.collectAsState()
    val gameView by BuiltInDisplay.viewState.collectAsState()
    var askLeave by remember { mutableStateOf(false) }
    var wasPlaying by remember { mutableStateOf(false) }
    LaunchedEffect(playStage) {
        if (playStage is LaunchStage.Running || playStage is LaunchStage.Opening) wasPlaying = true
        else if (playStage == LaunchStage.Idle && wasPlaying && exePath != null) onClose()
    }
    val inGame = gameView != null && (playStage as? LaunchStage.Running)?.vr == false
    androidx.activity.compose.BackHandler(enabled = inGame) { askLeave = true }
    if (inGame) {
        GameSurface(gameView!!, onLeave = { askLeave = true })
        if (askLeave) LeaveGameDialog(
            title ?: "the game",
            onStay = { askLeave = false },
            onLeave = { askLeave = false; vm.stop(); if (exePath != null) onClose() },
        )
        return
    }
    val ctx = LocalContext.current
    val ui = LocalDeviceUi.current
    val installed by vm.installed.collectAsState()
    val needsUpdate by vm.needsUpdate.collectAsState()
    val progress by vm.progress.collectAsState()
    val size by vm.size.collectAsState()
    val log by vm.log.collectAsState()
    var confirmDelete by remember { mutableStateOf(false) }
    var chooseExe by remember { mutableStateOf<List<File>?>(null) }
    var display by remember { mutableStateOf(vm.display) }

    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) vm.importGame(ctx, uri, true)
    }
    val pickExe = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) vm.importGame(ctx, uri, false)
    }

    LaunchedEffect(exePath) {
        if (exePath != null) vm.playOnce(ctx, exePath, args, mode, title)
        else if (title != null) vm.appendLog("No .exe found for this game.")
    }

    Box(Modifier.fillMaxSize().steamBackdrop(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 1100.dp).fillMaxSize().padding(horizontal = ui.gutter)) {
            // Top bar: X back to the library, then the title.
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(ui.iconButton + 4.dp).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.08f))
                        .clickable(onClick = onClose),
                    contentAlignment = Alignment.Center,
                ) { Text("✕", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold) }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(title ?: "Runtime & settings", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                    Text("App ${BuildConfig.VERSION_NAME} · ${DeviceMode.label(ctx)}", color = SteamColors.Muted, fontSize = 13.sp)
                }
            }

            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                // Runtime card: (Downloaded) badge and a Download / Delete button.
                Card {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Windows runtime", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.width(10.dp))
                        if (installed) Pill("(Downloaded)", SteamColors.GreenBright)
                        else if (progress == null) Pill("Not downloaded", SteamColors.Muted)
                    }
                    Text(
                        when {
                            progress != null -> "Downloading and installing…"
                            installed && needsUpdate -> "Box64 + Wine" + (if (size > 0) " · ${formatBytes(size)}" else "") +
                                " · an update with the GPU driver (Turnip + DXVK) is available"
                            installed -> "Box64 + Wine + Turnip + DXVK" + (if (size > 0) " · ${formatBytes(size)}" else "")
                            else -> "Box64 + Wine + GPU driver, about 157 MB to download"
                        },
                        color = SteamColors.Muted, fontSize = 13.sp, lineHeight = 18.sp,
                    )
                    val p = progress
                    if (p != null) {
                        Spacer(Modifier.height(12.dp))
                        LinearProgressIndicator(
                            progress = { p.fraction.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(50)),
                            color = SteamColors.Accent,
                            trackColor = Color.White.copy(alpha = 0.1f),
                        )
                        Spacer(Modifier.height(6.dp))
                        Text("${p.stage} · ${(p.fraction * 100).toInt()}%", color = SteamColors.Text, fontSize = 13.sp)
                    }
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (p == null) {
                            if (installed) {
                                if (needsUpdate) PrimaryButton("Update runtime") { vm.install() }
                                DangerButton("Delete this runtime download") { confirmDelete = true }
                            } else {
                                PrimaryButton("Download runtime") { vm.install() }
                            }
                        }
                    }
                }

                // Game window: the bundled display app, installed with one tap.
                Card {
                    Text("Game display", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(10.dp))
                    if (BuiltInDisplay.isBuiltIn(display.trim())) {
                        Text("✓ Built into this app: games show right here in this window.", color = SteamColors.GreenBright, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text("Nothing to install. Point and pull the trigger to click in a game; Back or ✕ Leave game returns to the app.", color = SteamColors.Muted, fontSize = 13.sp, lineHeight = 18.sp)
                    } else GameDisplayRow(onMessage = vm::appendLog)
                    Spacer(Modifier.height(14.dp))
                    GpuDriverRow()
                }

                // Controllers: the exact model when connected, and the permission for flat games.
                Card {
                    Text("Controllers", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(10.dp))
                    ControllerStatus()
                    Spacer(Modifier.height(10.dp))
                    OverlayPermissionRow { if (!ControllerRelay.requestOverlay(ctx)) vm.appendLog("This headset has no settings page for that. On a PC run: adb shell appops set com.qssteam SYSTEM_ALERT_WINDOW allow") }
                    Text(
                        "With it allowed, a \"🎮 → game\" badge passes the controller to flat games; tap the badge to give the keyboard back.",
                        color = SteamColors.Muted, fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 6.dp),
                    )
                }

                Card {
                    Text("Test & play", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(10.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        SecondaryButton("Smoke test (wine cmd /c ver)", enabled = installed) { vm.smokeTest() }
                        SecondaryButton("Run an imported game…", enabled = installed) {
                            val exes = vm.importedExes()
                            if (exes.isEmpty()) vm.appendLog("No .exe imported yet.") else chooseExe = exes
                        }
                        SecondaryButton("Import game folder…") { pickFolder.launch(null) }
                        SecondaryButton("Import single .exe…") { pickExe.launch(arrayOf("*/*")) }
                        SecondaryButton("Stop game") { vm.stop() }
                        SecondaryButton("Open the app in VR") {
                            if (VrBridge.isAvailable(ctx)) VrBridge.enterVr(ctx) else vm.appendLog("VR bridge DLL is missing from this build.")
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = display,
                        onValueChange = { display = it; vm.display = it },
                        singleLine = true,
                        label = { Text("Game display (:0 = built into this app; 127.0.0.1:0 = the Termux:X11 app)") },
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = SteamColors.Accent, unfocusedBorderColor = Color.White.copy(alpha = 0.16f),
                            focusedTextColor = SteamColors.Text, unfocusedTextColor = SteamColors.Text, cursorColor = SteamColors.Accent,
                            focusedLabelColor = SteamColors.Accent, unfocusedLabelColor = SteamColors.Muted,
                        ),
                    )
                }

                Card {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Log", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        SecondaryButton("Clear log", enabled = log.isNotBlank()) { GameRunner.clearLog() }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        log.ifBlank { "Messages from the runtime and games show up here." },
                        color = Color(0xFFC8D2DA), fontSize = 12.sp, fontFamily = FontFamily.Monospace, lineHeight = 16.sp,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color.Black.copy(alpha = 0.35f)).padding(12.dp),
                    )
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    // Loading screen: "Loading…" until the game's window is up, then "Opening game now", then the game.
    val stage by vm.stage.collectAsState()
    // Opened from a game's Play button: cancelling, closing an error, stopping the game or the game
    // ending goes straight back to the library, not to this settings screen.
    val fromCard = exePath != null
    LaunchOverlay(
        stage = stage, appId = appId, log = log,
        onShowGame = { GameRunner.showGameNow(ctx) },
        onStop = { vm.stop(); if (fromCard) onClose() },
        onDismiss = { GameRunner.dismissFailure(); if (fromCard) onClose() },
    )

    // A game was started without the display app: install it here, then play.
    val needsDisplay by vm.needsDisplay.collectAsState()
    if (needsDisplay) {
        val ready = com.toer15.queststeam.display.rememberDisplayInstalled()
        AlertDialog(
            onDismissRequest = vm::dismissDisplayPrompt,
            title = { Text(if (ready) "Game display installed" else "Install the game display") },
            text = {
                Column {
                    Text(
                        if (ready) "Everything's ready. Tap Play to start ${title ?: "the game"}."
                        else "Games show their window through the game display app (Termux:X11). It comes with Quest Steam; tap Install and confirm. You don't need the Termux app.",
                        fontSize = 14.sp, lineHeight = 20.sp,
                    )
                    if (!ready) { Spacer(Modifier.height(12.dp)); GameDisplayRow(onMessage = vm::appendLog) }
                }
            },
            confirmButton = {
                if (ready && exePath != null) TextButton(onClick = { vm.dismissDisplayPrompt(); vm.launchGame(ctx, File(exePath), args, mode, title ?: File(exePath).nameWithoutExtension) }) { Text("Play") }
            },
            dismissButton = { TextButton(onClick = vm::dismissDisplayPrompt) { Text("Close") } },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this runtime download?") },
            text = { Text("Frees the space used by Box64, Wine and the GPU driver. Your downloaded games are kept and come back when you download the runtime again.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; vm.delete() }) { Text("Delete", color = SteamColors.Danger) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }

    val exes = chooseExe
    if (exes != null) {
        AlertDialog(
            onDismissRequest = { chooseExe = null },
            title = { Text("Run which .exe?") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    exes.forEach { f ->
                        Text(
                            f.relativeTo(vm.launcher.gamesDir).path,
                            color = SteamColors.Text, fontSize = 14.sp,
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                                .clickable { chooseExe = null; vm.launchGame(ctx, f) }
                                .padding(vertical = 10.dp, horizontal = 6.dp),
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { chooseExe = null }) { Text("Close") } },
        )
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 8.dp).clip(RoundedCornerShape(22.dp))
            .background(Color.White.copy(alpha = 0.05f))
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(22.dp))
            .padding(18.dp),
    ) { content() }
}

@Composable
private fun Pill(text: String, color: Color) {
    Text(
        text, color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.15f)).padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

@Composable
private fun PrimaryButton(label: String, onClick: () -> Unit) {
    Button(
        onClick = onClick, shape = RoundedCornerShape(50), modifier = Modifier.height(LocalDeviceUi.current.buttonHeight),
        colors = ButtonDefaults.buttonColors(containerColor = SteamColors.Accent, contentColor = Color(0xFF0E141B)),
    ) { Text(label, fontWeight = FontWeight.Bold) }
}

@Composable
private fun DangerButton(label: String, onClick: () -> Unit) {
    Button(
        onClick = onClick, shape = RoundedCornerShape(50), modifier = Modifier.height(LocalDeviceUi.current.buttonHeight),
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF5A2328), contentColor = Color(0xFFFFD6D6)),
    ) { Text(label, fontWeight = FontWeight.Bold) }
}

/** GPU driver for games: Vortek (the headset's own Vulkan driver, default) or Mesa Turnip. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GpuDriverRow() {
    val ctx = LocalContext.current
    var driver by remember { mutableStateOf(Vortek.selected(ctx)) }
    Text("GPU driver", color = SteamColors.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(6.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((id, label) in listOf(Vortek.VORTEK to "Vortek (recommended)", Vortek.TURNIP to "Turnip")) {
            val on = driver == id
            Text(
                (if (on) "✓ " else "") + label,
                color = if (on) Color(0xFF0E141B) else SteamColors.Text, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(if (on) SteamColors.Accent else Color.White.copy(alpha = 0.08f))
                    .focusRing().clickable { driver = id; Vortek.select(ctx, id) }.padding(horizontal = 18.dp, vertical = 10.dp),
            )
        }
    }
    Text(
        if (driver == Vortek.VORTEK) "Games draw with the Quest's own GPU driver. Works on the Quest; pick Turnip only to test."
        else "Mesa Turnip talks to the GPU directly; the Quest usually refuses it (\"VK_ERROR_OUT_OF_DEVICE_MEMORY\").",
        color = SteamColors.Muted, fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 6.dp),
    )
}

@Composable
private fun SecondaryButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, enabled = enabled, shape = RoundedCornerShape(50), modifier = Modifier.height(LocalDeviceUi.current.buttonHeight)) {
        Text(label, color = if (enabled) SteamColors.Text else SteamColors.Muted)
    }
}
