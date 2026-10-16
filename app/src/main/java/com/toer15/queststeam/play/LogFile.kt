package com.toer15.queststeam.play

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import java.io.File
import java.io.FileWriter
import java.io.Writer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * The in-app log, also kept in a file: when the app's process ends while a game or Steam runs
 * (Android closes it when memory runs out, a crash in native code, or it was closed because the
 * headset stopped responding), the log of that run survives, and the next start shows it again
 * together with the reason Android gives.
 */
object LogFile {
    private const val MAX_BYTES = 2_000_000L
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "qss-log").apply { isDaemon = true } }
    @Volatile private var file: File? = null
    /** Exists while a game or Steam runs (see [setRunning]). */
    @Volatile private var marker: File? = null
    private var writer: Writer? = null
    private var written = 0L

    /**
     * Starts this run's log file (the last run's becomes previous.log). Returns the log to show
     * now: the previous run's end, with why it ended, if the app closed unexpectedly or while a
     * game or Steam was running.
     */
    fun start(ctx: Context): String? {
        if (file != null) return null
        val dir = File(ctx.filesDir, "logs").apply { mkdirs() }
        val current = File(dir, "session.log")
        val previous = File(dir, "previous.log")
        if (current.isFile && current.length() > 0) { previous.delete(); current.renameTo(previous) }
        file = current
        val running = File(dir, "running")
        marker = running
        val wasRunning = running.exists()
        running.delete()
        val exit = runCatching { lastExit(ctx, wasRunning) }.getOrNull() ?: return null
        val old = previous.takeIf { it.isFile }?.readLines().orEmpty()
        val nativeErrors = if (exit.native) runCatching { recentNativeErrors() }.getOrDefault(emptyList()) else emptyList()
        // Errors the app caught on its background threads (e.g. the game display's renderer) in that run.
        val caught = File(ctx.filesDir, "errors.log").takeIf { it.isFile && it.lastModified() >= previous.lastModified() - 3_600_000 }
            ?.readLines()?.filter { it.isNotBlank() }?.takeLast(30).orEmpty()
        return buildString {
            appendLine("== The app was closed${exit.whenText}: ${exit.reason} ==")
            exit.details.forEach { appendLine("  $it") }
            if (nativeErrors.isNotEmpty()) {
                appendLine("[native crash]")
                nativeErrors.forEach { appendLine("  $it") }
            }
            if (caught.isNotEmpty()) {
                appendLine("[app errors]")
                caught.forEach { appendLine("  ${it.take(240)}") }
            }
            if (old.isNotEmpty()) {
                appendLine("== Log from before it closed (last ${minOf(old.size, 400)} lines) ==")
                old.takeLast(400).forEach { appendLine(it) }
            }
            appendLine("== End of the log from before ==")
        }
    }

    fun append(line: String) {
        val f = file ?: return
        io.execute {
            runCatching {
                if (written > MAX_BYTES) { writer?.close(); writer = null; f.delete(); written = 0 }
                val w = writer ?: FileWriter(f, true).also { writer = it; written = f.length() }
                w.write(line); w.write("\n"); w.flush()
                written += line.length + 1
            }
        }
    }

    fun clear() {
        val f = file ?: return
        io.execute { runCatching { writer?.close(); writer = null; f.delete(); written = 0 } }
    }

    /**
     * [on] while a game or Steam runs: if the app's process ends meanwhile, however that happens
     * (also when it's closed because the headset stopped responding), the next start shows this log.
     */
    fun setRunning(on: Boolean) {
        val m = marker ?: return
        io.execute { runCatching { if (on) m.createNewFile() else m.delete() } }
    }

    private class Exit(val reason: String, val whenText: String, val details: List<String>, val native: Boolean)

    /**
     * How the app's last process ended, if that should be shown: unexpectedly (Android's record,
     * not shown yet), or in any way while a game or Steam was running ([wasRunning]).
     */
    private fun lastExit(ctx: Context, wasRunning: Boolean): Exit? {
        val record = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) runCatching { androidExit(ctx, wasRunning) }.getOrNull() else null
        return record ?: if (wasRunning) Exit(WHILE_RUNNING, "", emptyList(), false) else null
    }

    private const val WHILE_RUNNING = "it ended while a game or Steam was running"

    @RequiresApi(Build.VERSION_CODES.R)
    private fun androidExit(ctx: Context, wasRunning: Boolean): Exit? {
        val am = ctx.getSystemService(ActivityManager::class.java) ?: return null
        val info = am.getHistoricalProcessExitReasons(ctx.packageName, 0, 5)
            .firstOrNull { it.processName == ctx.packageName } ?: return null
        val prefs = ctx.getSharedPreferences("runtime", 0)
        if (prefs.getLong("exit_seen", 0) >= info.timestamp) return null
        prefs.edit().putLong("exit_seen", info.timestamp).apply()
        val reason = when (info.reason) {
            ApplicationExitInfo.REASON_LOW_MEMORY -> "Android ran out of memory and closed it (with the game and Steam)"
            ApplicationExitInfo.REASON_CRASH -> "it crashed (Java)"
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "it crashed in native code"
            ApplicationExitInfo.REASON_ANR -> "it stopped responding and Android closed it"
            ApplicationExitInfo.REASON_SIGNALED -> "it was killed (signal ${info.status})"
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "Android closed it for using too many resources"
            ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "it failed to start"
            ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "a process it depends on died"
            ApplicationExitInfo.REASON_OTHER -> "Android closed it (other reason)"
            ApplicationExitInfo.REASON_UNKNOWN -> "unknown reason"
            // Closed normally (by the user, an update, or the app itself): only worth showing when a
            // game was running, e.g. the app was closed because the headset stopped responding.
            ApplicationExitInfo.REASON_USER_REQUESTED, ApplicationExitInfo.REASON_USER_STOPPED ->
                if (wasRunning) "$WHILE_RUNNING (closed by you or the headset)" else return null
            else -> if (wasRunning) WHILE_RUNNING else return null
        }
        val details = listOfNotNull(
            info.description?.takeIf { it.isNotBlank() }?.let { "Android says: $it" },
            "memory used by the app process then: ${info.pss / 1024} MB (PSS), ${info.rss / 1024} MB (RSS)",
            "importance: ${info.importance}",
        )
        val at = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(info.timestamp))
        return Exit(reason, " at $at", details, info.reason == ApplicationExitInfo.REASON_CRASH_NATIVE || info.reason == ApplicationExitInfo.REASON_SIGNALED)
    }

    /** The crash report Android's debuggerd wrote to the log for this app (it stays readable after a restart). */
    private fun recentNativeErrors(): List<String> {
        val p = ProcessBuilder("logcat", "-d", "-t", "3000", "-v", "brief").redirectErrorStream(true).start()
        val lines = p.inputStream.bufferedReader().readLines()
        p.waitFor()
        val keep = Regex("[EF]/DEBUG|Fatal signal|Abort message|backtrace|#\\d\\d pc |signal \\d+ \\(SIG", RegexOption.IGNORE_CASE)
        return lines.filter { keep.containsMatchIn(it) }.takeLast(40).map { it.take(240) }
    }
}
