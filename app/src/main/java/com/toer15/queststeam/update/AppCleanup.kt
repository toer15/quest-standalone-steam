package com.toer15.queststeam.update

import android.content.Context
import com.toer15.queststeam.BuildConfig
import java.io.File

/**
 * After the app is updated, deletes what older versions left behind: downloaded update APKs,
 * the bundled display installer copy, caches, crash/error logs, stale shared-memory files and
 * shader caches built by older GPU drivers. Never touches downloaded games, the sign-in, the
 * library, settings or the Windows runtime itself (an outdated runtime shows "Update runtime").
 */
object AppCleanup {
    private const val PREFS = "cleanup"
    private const val KEY = "cleaned_for_version"

    /** Runs once per installed version (cheap check on every start). Returns bytes freed. */
    fun runIfUpdated(ctx: Context): Long {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val current = BuildConfig.VERSION_CODE
        if (prefs.getInt(KEY, -1) == current) return 0
        val freed = runCatching { clean(ctx) }.getOrDefault(0L)
        // v0.11: games use the display built into the app; switch the old Termux:X11 default over.
        val rt = ctx.getSharedPreferences("runtime", Context.MODE_PRIVATE)
        if (rt.getString("display", null) == "127.0.0.1:0") rt.edit().putString("display", ":0").apply()
        prefs.edit().putInt(KEY, current).apply()
        return freed
    }

    fun clean(ctx: Context): Long {
        var freed = 0L
        fun del(f: File?) {
            if (f == null || !f.exists()) return
            freed += if (f.isDirectory) f.walkBottomUp().filter { it.isFile }.sumOf { it.length() } else f.length()
            f.deleteRecursively()
        }
        // Update downloads and other cached files from older versions. Vortek's texture cache stays: it
        // makes the next game start faster, and its files don't depend on the app's version.
        ctx.cacheDir.listFiles()?.filter { it.name != "vortek" }?.forEach(::del)
        ctx.externalCacheDir?.listFiles()?.forEach(::del)
        // Old logs (a crash report from the previous version is no longer useful).
        del(File(ctx.filesDir, "errors.log"))
        del(File(ctx.filesDir, "last-crash.txt"))
        // Runtime temp files: shared memory and old shader caches (DXVK's state cache now lives in
        // home/xuser/.cache/dxvk, which stays).
        val tmp = File(ctx.filesDir, "rootfs/tmp")
        tmp.listFiles()?.forEach { f ->
            val n = f.name
            if (n.endsWith(".shm") || n == "mesa-cache" || n.endsWith(".dxvk-cache") || n.startsWith(".X") || n.endsWith(".part")) del(f)
        }
        // Half-finished runtime downloads.
        ctx.filesDir.listFiles()?.filter { it.name.endsWith(".part") || it.name.endsWith(".tmp") }?.forEach(::del)
        return freed
    }
}
