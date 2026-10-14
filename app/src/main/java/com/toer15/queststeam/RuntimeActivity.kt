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

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 32, 32, 32) }
        fun btn(label: String, onClick: () -> Unit) = Button(this).apply { text = label; setOnClickListener { onClick() } }.also { root.addView(it) }

        root.addView(TextView(this).apply { textSize = 20f; text = "Runtime (Box64 + Wine) — ${DeviceMode.label(this@RuntimeActivity)}" })
        status = TextView(this).also { root.addView(it) }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000 }.also { root.addView(it) }

        btn("Install / reinstall runtime (downloads ~157 MB)") { install() }
        btn("Smoke test: wine cmd /c ver (no display needed)") { launcher.smokeTest(::appendLog).also { appendLog(it.message) } }

        root.addView(TextView(this).apply { text = "X display (Termux:X11 TCP, blank = none):" })
        displayField = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            setText(prefs.getString("display", "127.0.0.1:0"))
        }.also { root.addView(it) }

        btn("Import game folder…") { pickFolder.launch(null) }
        btn("Import single .exe…") { pickExe.launch(arrayOf("*/*")) }
        btn("Run an imported game…") { chooseAndRun() }
        btn("Stop") { launcher.stop(); appendLog("[stopped]") }

        logScroll = ScrollView(this)
        logView = TextView(this).apply { textSize = 12f; setTextIsSelectable(true); typeface = android.graphics.Typeface.MONOSPACE }
        logScroll.addView(logView)
        root.addView(logScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        displayField.setOnFocusChangeListener { _, _ -> prefs.edit().putString("display", displayField.text.toString()).apply() }
        refreshStatus()
        intent.getStringExtra(EXTRA_EXE)?.let { path ->
            title = intent.getStringExtra(EXTRA_TITLE) ?: "Play"
            val display = displayField.text.toString().trim().ifBlank { null }
            if (!launcher.isInstalled) appendLog("Runtime not installed: tap Install runtime first, then Play again.")
            else {
                if (display != null && display.startsWith("127.0.0.1")) tryOpenTermuxX11()
                appendLog(launcher.launchExe(File(path), display, ::appendLog).message)
            }
        } ?: if (intent.hasExtra(EXTRA_TITLE)) appendLog("No .exe found for this game.") else Unit
    }

    private fun refreshStatus() {
        status.text = if (launcher.isInstalled) "Runtime installed at ${launcher.rootfs.path}" else "Runtime not installed"
    }

    private fun appendLog(line: String) = runOnUiThread {
        log.append(line).append('\n')
        if (log.length > 200_000) log.delete(0, log.length - 150_000)
        logView.text = log
        logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun install() {
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    RuntimeInstaller(this@RuntimeActivity).install(::appendLog) { p ->
                        runOnUiThread { status.text = "${p.stage} ${(p.fraction * 100).toInt()}%"; progress.progress = (p.fraction * 1000).toInt() }
                    }
                }
                appendLog("Runtime installed.")
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
            appendLog(launcher.launchExe(exes[i], display, ::appendLog).message)
        }.show()
    }

    /** Brings Termux:X11 to the front if installed so the game window is visible. */
    private fun tryOpenTermuxX11() {
        packageManager.getLaunchIntentForPackage("com.termux.x11")?.let {
            runCatching { startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        } ?: appendLog("Termux:X11 not installed — see README (Display setup).")
    }
}
