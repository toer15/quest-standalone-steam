package com.toer15.queststeam

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
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
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = 0xFF0E141B.toInt()
        window.navigationBarColor = 0xFF070B10.toInt()
        val exe = intent.getStringExtra(EXTRA_EXE)
        val title = intent.getStringExtra(EXTRA_TITLE)
        setContent { SteamTheme { RuntimeScreen(title, exe, onClose = { finish() }) } }
    }
}

/** Runtime state that survives panel resizes. */
class RuntimeViewModel(app: Application) : AndroidViewModel(app) {
    val launcher = GameLauncher(app)
    private val installer = RuntimeInstaller(app)
    private val prefs = app.getSharedPreferences("runtime", 0)

    private val _log = MutableStateFlow("")
    val log: StateFlow<String> = _log
    private val _installed = MutableStateFlow(installer.isInstalled)
    val installed: StateFlow<Boolean> = _installed
    private val _needsUpdate = MutableStateFlow(installer.needsUpdate)
    val needsUpdate: StateFlow<Boolean> = _needsUpdate
    private val _progress = MutableStateFlow<RuntimeInstaller.Progress?>(null)
    val progress: StateFlow<RuntimeInstaller.Progress?> = _progress
    private val _size = MutableStateFlow(-1L)
    val size: StateFlow<Long> = _size
    private var startedPlay = false

    var display: String
        get() = prefs.getString("display", "127.0.0.1:0") ?: "127.0.0.1:0"
        set(v) { prefs.edit().putString("display", v).apply() }

    init { refresh() }

    fun appendLog(line: String) {
        val s = _log.value + line + "\n"
        _log.value = if (s.length > 120_000) s.takeLast(100_000) else s
    }

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

    fun stop() {
        launcher.stop()
        appendLog("[stopped]")
    }

    /**
     * Starts a game. On Quest, a SteamVR game gets the OpenVR bridge: its openvr_api.dll is swapped
     * for ours, the shared memory is created, and the headset switches to the immersive VR view.
     */
    fun launchGame(ctx: Context, exe: File) {
        viewModelScope.launch {
            val disp = display.trim().ifBlank { null }
            val gameDir = gameRoot(exe)
            val env = mutableMapOf<String, String>()
            withContext(Dispatchers.IO) {
                if (VrBridge.canPlayInVr(ctx) && VrBridge.isVrGame(gameDir)) {
                    val n = VrBridge.install(ctx, gameDir, ::appendLog)
                    if (n > 0) {
                        env["QSS_VR_SHM"] = VrBridge.prepareShm(ctx).path
                        appendLog("SteamVR game: starting in VR (bridge in $n place(s)). The headset view opens in a moment.")
                    } else {
                        appendLog("This game's openvr_api.dll is 32-bit; VR needs a 64-bit game for now. Starting flat.")
                    }
                } else if (VrBridge.isVrGame(gameDir) && !VrBridge.isAvailable(ctx)) {
                    appendLog("VR bridge DLL is missing from this build; starting flat.")
                }
            }
            if (disp != null && disp.startsWith("127.0.0.1")) openTermuxX11(ctx)
            val result = withContext(Dispatchers.IO) { launcher.launchExe(exe, disp, env, onLine = ::appendLog) }
            appendLog(result.message)
            if (result.ok && env.containsKey("QSS_VR_SHM")) {
                delay(2500)
                VrBridge.enterVr(ctx)
            }
        }
    }

    /** Play from a game card: downloads the runtime first if needed, once. */
    fun playOnce(ctx: Context, exePath: String) {
        if (startedPlay) return
        startedPlay = true
        val exe = File(exePath)
        if (installer.isInstalled) {
            launchGame(ctx, exe)
        } else {
            appendLog("First launch: downloading the Windows runtime (Box64 + Wine, ~157 MB)…")
            install { launchGame(ctx, exe) }
        }
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

    /** The game's install folder: <games>/<appId>/..., or the exe's folder for loose imports. */
    private fun gameRoot(exe: File): File {
        val games = launcher.gamesDir.canonicalPath
        var d = exe.parentFile ?: return exe
        while (d.parentFile != null && d.parentFile!!.canonicalPath != games) d = d.parentFile ?: break
        return if (d.parentFile?.canonicalPath == games) d else exe.parentFile!!
    }

    /** Brings Termux:X11 to the front if installed so the game window is visible. */
    private fun openTermuxX11(ctx: Context) {
        val intent = ctx.packageManager.getLaunchIntentForPackage("com.termux.x11")
        if (intent != null) runCatching { ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        else appendLog("Termux:X11 not installed: games need it for their window (see README, Display setup).")
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RuntimeScreen(title: String?, exePath: String?, onClose: () -> Unit, vm: RuntimeViewModel = viewModel()) {
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
        if (exePath != null) vm.playOnce(ctx, exePath)
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
                        SecondaryButton("Enter VR view (SteamVR bridge)") {
                            if (VrBridge.isAvailable(ctx)) VrBridge.enterVr(ctx) else vm.appendLog("VR bridge DLL is missing from this build.")
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = display,
                        onValueChange = { display = it; vm.display = it },
                        singleLine = true,
                        label = { Text("Game window display (Termux:X11, blank = none)") },
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
                    Text("Log", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
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

@Composable
private fun SecondaryButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, enabled = enabled, shape = RoundedCornerShape(50), modifier = Modifier.height(LocalDeviceUi.current.buttonHeight)) {
        Text(label, color = if (enabled) SteamColors.Text else SteamColors.Muted)
    }
}
