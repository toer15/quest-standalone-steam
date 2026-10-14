package com.toer15.queststeam

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import com.toer15.queststeam.launcher.DeviceMode
import com.toer15.queststeam.launcher.GameLauncher
import com.toer15.queststeam.launcher.RuntimeInstaller
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Runtime installer + "pick a game and run it with Box64/Wine" screen, with live logs. */
class RuntimeActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_EXE = "exe"
        const val EXTRA_TITLE = "title"
    }

    private lateinit var launcher: GameLauncher
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var displayField: EditText
    private val log = StringBuilder()

    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) importGame(uri, isTree = true)
    }
    private val pickExe = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importGame(uri, isTree = false)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        launcher = GameLauncher(this)
        val prefs = getSharedPreferences("runtime", MODE_PRIVATE)

        window.statusBarColor = 0xFF0E141B.toInt()
        window.navigationBarColor = 0xFF070B10.toInt()
        val dp = resources.displayMetrics.density
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * dp).toInt(), (8 * dp).toInt(), (16 * dp).toInt(), (16 * dp).toInt())
            setBackgroundColor(0xFF0E141B.toInt())
        }
        fun btn(label: String, onClick: () -> Unit) = Button(this).apply { text = label; isAllCaps = false; setOnClickListener { onClick() } }.also { root.addView(it) }

        // Top bar: X (back to the library) on the left, then the title.
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
        bar.addView(TextView(this).apply {
            text = "✕"
            textSize = 26f
            setTextColor(0xFFE8F0F6.toInt())
            contentDescription = "Close"
            gravity = android.view.Gravity.CENTER
            val size = (52 * dp).toInt()
            layoutParams = LinearLayout.LayoutParams(size, size)
            isClickable = true
            isFocusable = true
            setBackgroundResource(android.R.drawable.list_selector_background)
            setOnClickListener { finish() }
        })
        bar.addView(TextView(this).apply {
            textSize = 20f
            setTextColor(0xFFFFFFFF.toInt())
            text = intent.getStringExtra(EXTRA_TITLE) ?: "Runtime & settings"
            setPadding((8 * dp).toInt(), 0, 0, 0)
        })
        root.addView(bar)
        root.addView(TextView(this).apply { setTextColor(0xFF8F98A0.toInt()); text = "Box64 + Wine · ${DeviceMode.label(this@RuntimeActivity)}" })
        status = TextView(this).apply { setTextColor(0xFFE8F0F6.toInt()) }.also { root.addView(it) }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000 }.also { root.addView(it) }

        btn("Install / reinstall runtime (downloads ~157 MB)") { install() }
        btn("Smoke test: wine cmd /c ver (no display needed)") { launcher.smokeTest(::appendLog).also { appendLog(it.message) } }

        root.addView(TextView(this).apply { setTextColor(0xFF8F98A0.toInt()); text = "X display (Termux:X11 TCP, blank = none):" })
        displayField = EditText(this).apply {
            setTextColor(0xFFE8F0F6.toInt())
            inputType = InputType.TYPE_CLASS_TEXT
            setText(prefs.getString("display", "127.0.0.1:0"))
        }.also { root.addView(it) }

        btn("Import game folder…") { pickFolder.launch(null) }
        btn("Import single .exe…") { pickExe.launch(arrayOf("*/*")) }
        btn("Run an imported game…") { chooseAndRun() }
        btn("Stop") { launcher.stop(); appendLog("[stopped]") }
        if (BuildConfig.QUEST_BUILD) btn("Enter VR view (SteamVR bridge)") {
            if (VrBridge.isAvailable(this)) VrBridge.enterVr(this) else appendLog("VR bridge DLL is missing from this build.")
        }

        logScroll = ScrollView(this)
        logView = TextView(this).apply { setTextColor(0xFFC8D2DA.toInt()); textSize = 12f; setTextIsSelectable(true); typeface = android.graphics.Typeface.MONOSPACE }
        logScroll.addView(logView)
        root.addView(logScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        displayField.setOnFocusChangeListener { _, _ -> prefs.edit().putString("display", displayField.text.toString()).apply() }
        refreshStatus()
        intent.getStringExtra(EXTRA_EXE)?.let { path ->
            title = intent.getStringExtra(EXTRA_TITLE) ?: "Play"
            val display = displayField.text.toString().trim().ifBlank { null }
            val play = { launchGame(File(path), display) }
            if (launcher.isInstalled) play()
            else {
                // First Play on this device: set up Box64 + Wine, then start the game.
                appendLog("First launch: installing the Windows runtime (Box64 + Wine, ~157 MB)…")
                install(onInstalled = play)
            }
        } ?: if (intent.hasExtra(EXTRA_TITLE)) appendLog("No .exe found for this game.") else Unit
    }

    private fun refreshStatus() {
        status.text = when {
            launcher.isInstalled -> "Runtime installed at ${launcher.rootfs.path}"
            launcher.needsUpdate -> "Runtime update available (GPU driver + smoke test fix): tap Install. Your games are kept."
            else -> "Runtime not installed"
        }
    }

    private fun appendLog(line: String) = runOnUiThread {
        log.append(line).append('\n')
        if (log.length > 200_000) log.delete(0, log.length - 150_000)
        logView.text = log
        logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun install(onInstalled: () -> Unit = {}) {
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    RuntimeInstaller(this@RuntimeActivity).install(::appendLog) { p ->
                        runOnUiThread { status.text = "${p.stage} ${(p.fraction * 100).toInt()}%"; progress.progress = (p.fraction * 1000).toInt() }
                    }
                }
                appendLog("Runtime installed.")
                refreshStatus()
                onInstalled()
            } catch (e: Exception) {
                appendLog("Install failed: $e"); e.cause?.let { appendLog("  cause: $it") }
            }
            refreshStatus()
        }
    }

    private fun importGame(uri: Uri, isTree: Boolean) {
        if (!launcher.isInstalled) { appendLog("Install the runtime first."); return }
        lifecycleScope.launch {
            appendLog("Importing $uri …")
            val dest = withContext(Dispatchers.IO) {
                runCatching {
                    if (isTree) {
                        val doc = DocumentFile.fromTreeUri(this@RuntimeActivity, uri)!!
                        val dir = File(launcher.gamesDir, doc.name ?: "game").apply { mkdirs() }
                        copyTree(doc, dir); dir
                    } else {
                        val name = DocumentFile.fromSingleUri(this@RuntimeActivity, uri)?.name ?: "game.exe"
                        val dir = File(launcher.gamesDir, name.substringBeforeLast('.')).apply { mkdirs() }
                        contentResolver.openInputStream(uri)!!.use { i -> File(dir, name).outputStream().use { i.copyTo(it) } }
                        dir
                    }
                }
            }
            dest.onSuccess { appendLog("Imported to $it") }.onFailure { appendLog("Import failed: $it") }
        }
    }

    private fun copyTree(doc: DocumentFile, dir: File) {
        for (child in doc.listFiles()) {
            val name = child.name ?: continue
            if (child.isDirectory) copyTree(child, File(dir, name).apply { mkdirs() })
            else contentResolver.openInputStream(child.uri)?.use { i -> File(dir, name).outputStream().use { i.copyTo(it) } }
        }
    }

    private fun chooseAndRun() {
        val exes = launcher.findExes(launcher.gamesDir)
        if (exes.isEmpty()) { appendLog("No .exe imported yet."); return }
        val labels = exes.map { it.relativeTo(launcher.gamesDir).path }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Run which .exe?").setItems(labels) { _, i ->
            val display = displayField.text.toString().trim().ifBlank { null }
            getSharedPreferences("runtime", MODE_PRIVATE).edit().putString("display", display ?: "").apply()
            if (display != null && display.startsWith("127.0.0.1")) tryOpenTermuxX11()
            launchGame(exes[i], display)
        }.show()
    }

    /**
     * Starts the game. On Quest, a SteamVR game gets the OpenVR bridge: its openvr_api.dll is swapped
     * for ours, the shared memory is created, and the headset switches to the immersive VR view.
     */
    private fun launchGame(exe: File, display: String?) {
        val gameDir = gameRoot(exe)
        val vr = VrBridge.canPlayInVr(this) && VrBridge.isVrGame(gameDir)
        val env = mutableMapOf<String, String>()
        if (vr) {
            val n = VrBridge.install(this, gameDir, ::appendLog)
            if (n > 0) {
                env["QSS_VR_SHM"] = VrBridge.prepareShm(this).path
                appendLog("SteamVR game: starting in VR (bridge in $n place(s)). The headset view opens in a moment.")
            } else appendLog("This game's openvr_api.dll is 32-bit; VR needs a 64-bit game for now. Starting flat.")
        } else if (BuildConfig.QUEST_BUILD && VrBridge.isVrGame(gameDir) && !VrBridge.isAvailable(this)) {
            appendLog("VR bridge DLL is missing from this build; starting flat.")
        }
        if (display != null && display.startsWith("127.0.0.1")) tryOpenTermuxX11()
        appendLog(launcher.launchExe(exe, display, env, onLine = ::appendLog).message)
        if (env.containsKey("QSS_VR_SHM")) logScroll.postDelayed({ VrBridge.enterVr(this) }, 2500)
    }

    /** The game's install folder: <games>/<appId>/..., or the exe's folder for loose imports. */
    private fun gameRoot(exe: File): File {
        var d = exe.parentFile ?: return exe
        while (d.parentFile != null && d.parentFile!!.canonicalPath != launcher.gamesDir.canonicalPath) {
            d = d.parentFile ?: break
        }
        return if (d.parentFile?.canonicalPath == launcher.gamesDir.canonicalPath) d else exe.parentFile!!
    }

    /** Brings Termux:X11 to the front if installed so the game window is visible. */
    private fun tryOpenTermuxX11() {
        packageManager.getLaunchIntentForPackage("com.termux.x11")?.let {
            runCatching { startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        } ?: appendLog("Termux:X11 not installed — see README (Display setup).")
    }
}
