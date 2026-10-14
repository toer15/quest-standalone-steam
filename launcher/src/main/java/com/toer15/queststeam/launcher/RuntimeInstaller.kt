package com.toer15.queststeam.launcher

import android.content.Context
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Installs a Linux arm64 userland + Box64 + Wine + Wine prefix by reusing the open-source
 * Winlator release (LGPL-2.1, https://github.com/brunodev85/winlator). Logic lives in [RuntimeCore].
 *
 * Winlator's binaries hardcode "/data/data/com.winlator/files/rootfs/...". Our applicationId makes
 * "/data/data/com.qssteam//" the same length, so files and symlinks are byte-patched while extracting.
 */
class RuntimeInstaller(private val ctx: Context) {

    data class Progress(val stage: String, val fraction: Float)

    companion object {
        private val running = AtomicBoolean(false)
        const val GAMES_SUBDIR = "home/xuser/.wine/drive_c/games"
    }

    val rootfs = File(ctx.filesDir, "rootfs")
    private val marker = File(rootfs, ".qss-installed")
    /** Downloaded and extracted (any version). */
    val isInstalled get() = marker.exists() && File(rootfs, "usr/local/bin/box64").exists()
    /** Installed by an older app version: works (the prefix is repaired on launch) but lacks newer parts like Turnip. */
    val needsUpdate get() = isInstalled && runCatching { marker.readText().trim() }.getOrNull() != installedTag
    /** Bytes used by the runtime, excluding downloaded games. */
    fun sizeOnDisk(): Long = rootfs.walkTopDown().onEnter { it.canonicalPath != File(rootfs, GAMES_SUBDIR).canonicalPath }
        .filter { it.isFile && !java.nio.file.Files.isSymbolicLink(it.toPath()) }.sumOf { it.length() }

    /**
     * Deletes the runtime and the cached Winlator download. Downloaded games are moved aside and come
     * back when the runtime is downloaded again.
     */
    fun delete(log: (String) -> Unit) {
        if (running.get()) { log("Install is running; wait for it to finish."); return }
        val games = File(rootfs, GAMES_SUBDIR)
        val kept = File(ctx.filesDir, "games-keep")
        if (games.isDirectory && (games.list()?.isNotEmpty() == true)) { kept.deleteRecursively(); games.renameTo(kept) }
        rootfs.deleteRecursively()
        val dir = ctx.getExternalFilesDir(null) ?: ctx.cacheDir
        File(dir, "Winlator_11.2.apk").delete()
        File(dir, "Winlator_11.2.apk.part").delete()
        log("Runtime deleted.")
    }
    private val installedTag = "${RuntimeCore.WINLATOR_VERSION}/layout${RuntimeCore.LAYOUT_VERSION}"

    fun install(log: (String) -> Unit, onProgress: (Progress) -> Unit) {
        if (!running.compareAndSet(false, true)) { log("Install already running."); return }
        try {
            val dir = ctx.getExternalFilesDir(null) ?: ctx.cacheDir
            // Clean up the unverified file name used by v0.2.0 (it could be corrupt).
            File(dir, "Winlator_11.2.apk").takeIf { it.exists() && !RuntimeCore.isValidApk(it) }?.delete()
            val apk = RuntimeCore.downloadVerified(File(dir, "Winlator_11.2.apk"), log) {
                onProgress(Progress("Downloading Winlator ${RuntimeCore.WINLATOR_VERSION} (157 MB)", it))
            }
            log("Extracting into ${rootfs.path}")
            // Keep downloaded games (they live inside the Wine prefix) across a reinstall/upgrade.
            val games = File(rootfs, GAMES_SUBDIR)
            val kept = File(ctx.filesDir, "games-keep")
            if (games.isDirectory) { kept.deleteRecursively(); games.renameTo(kept) }
            rootfs.deleteRecursively(); rootfs.mkdirs()
            RuntimeCore.extractAll(apk, rootfs, ctx.packageName) { stage, f -> onProgress(Progress(stage, f)) }
            if (kept.isDirectory) { games.deleteRecursively(); games.parentFile?.mkdirs(); kept.renameTo(games) }
            marker.writeText(installedTag)
            onProgress(Progress("Runtime installed", 1f))
        } finally {
            running.set(false)
        }
    }
}
