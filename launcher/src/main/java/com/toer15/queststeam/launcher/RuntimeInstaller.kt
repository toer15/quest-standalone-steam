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

    companion object { private val running = AtomicBoolean(false) }

    val rootfs = File(ctx.filesDir, "rootfs")
    private val marker = File(rootfs, ".qss-installed")
    val isInstalled get() = marker.exists() && File(rootfs, "usr/local/bin/box64").exists()

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
            rootfs.deleteRecursively(); rootfs.mkdirs()
            RuntimeCore.extractAll(apk, rootfs, ctx.packageName) { stage, f -> onProgress(Progress(stage, f)) }
            marker.writeText(RuntimeCore.WINLATOR_VERSION)
            onProgress(Progress("Runtime installed", 1f))
        } finally {
            running.set(false)
        }
    }
}
