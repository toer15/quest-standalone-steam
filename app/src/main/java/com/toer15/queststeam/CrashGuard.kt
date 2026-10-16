package com.toer15.queststeam

import android.app.Application
import android.content.Context
import android.os.Looper
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Installs [CrashGuard] for the whole app process. */
class QssApp : Application(), coil.ImageLoaderFactory {
    /**
     * Game pictures: a smaller memory cache than Coil's default (a quarter of the app's memory), so
     * more of the headset's memory is left for games and Steam; pictures reload from the disk cache.
     */
    override fun newImageLoader(): coil.ImageLoader = coil.ImageLoader.Builder(this)
        .memoryCache { coil.memory.MemoryCache.Builder(this).maxSizePercent(0.10).build() }
        .build()

    override fun onCreate() {
        super.onCreate()
        // First start of a new version: delete what older versions left behind (before the crash
        // guard reads last-crash.txt, which belongs to the old version).
        com.toer15.queststeam.update.AppCleanup.runIfUpdated(this)
        CrashGuard.install(this)
        // The log is also kept in a file; after an unexpected close it comes back with the reason.
        com.toer15.queststeam.play.GameRunner.initLog(this)
        com.toer15.queststeam.gamepad.Controllers.start(this)
    }
}

/**
 * Keeps background failures from closing the app, and remembers real crashes.
 *
 * - An exception on a background thread (Steam connection, downloader, Wine log readers) is
 *   written to a log and reported to [onBackgroundError] (the download shows "failed" with the
 *   reason) instead of killing the app.
 * - A crash on the main thread still closes the app (its state can't be trusted), but the
 *   stack trace is saved and shown on the next launch so it can be copied and reported.
 */
object CrashGuard {
    @Volatile var onBackgroundError: ((String) -> Unit)? = null
    private lateinit var crashFile: File
    private lateinit var logFile: File

    fun install(ctx: Context) {
        crashFile = File(ctx.filesDir, "last-crash.txt")
        logFile = File(ctx.filesDir, "errors.log")
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            val report = describe(thread, error)
            if (Looper.getMainLooper().thread === thread) {
                runCatching { crashFile.writeText(report) }
                previous?.uncaughtException(thread, error)
            } else {
                runCatching { if (logFile.length() > 512_000) logFile.delete(); logFile.appendText(report + "\n") }
                runCatching { onBackgroundError?.invoke(summary(error)) }
            }
        }
    }

    /** The last crash report, if the app closed unexpectedly last time. */
    fun lastCrash(): String? = if (::crashFile.isInitialized && crashFile.exists()) crashFile.readText() else null

    fun clearLastCrash() { if (::crashFile.isInitialized) crashFile.delete() }

    fun summary(e: Throwable): String {
        var root: Throwable = e
        while (root.cause != null && root.cause !== root) root = root.cause!!
        val msg = root.message?.takeIf { it.isNotBlank() } ?: root.javaClass.simpleName
        return "${root.javaClass.simpleName}: $msg".take(300)
    }

    fun describe(thread: Thread, e: Throwable): String {
        val sw = StringWriter()
        e.printStackTrace(PrintWriter(sw))
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        return "Quest Steam ${BuildConfig.VERSION_NAME} · $time · thread ${thread.name}\n$sw"
    }
}
