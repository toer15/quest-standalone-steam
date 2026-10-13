package com.toer15.queststeam.launcher

import android.content.Context
import android.system.Os
import com.github.luben.zstd.ZstdInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipFile

/**
 * Installs a Linux arm64 userland + Box64 + Wine + Wine prefix by reusing the open-source
 * Winlator release (LGPL-2.1, https://github.com/brunodev85/winlator).
 *
 * The Winlator APK contains these assets (verified in v11.2.0):
 *   assets/rootfs.tzst             glibc arm64 rootfs incl. x86_64 Wine in /opt/wine
 *   assets/box64/box64-0.4.4.tzst  box64 -> /usr/local/bin/box64
 *   assets/container_pattern.tzst  a ready Wine prefix (.wine/)
 *   assets/dxwrapper/dxvk-*.tzst   DXVK (optional)
 *
 * Winlator's binaries hardcode "/data/data/com.winlator/files/rootfs/..." (e.g. box64's ELF
 * interpreter). Our applicationId is chosen so "/data/data/com.qssteam//" has exactly the same
 * length, which lets us byte-patch every file and symlink at extraction time without breaking ELF
 * layouts. That's why the rootfs lives at filesDir/rootfs.
 */
class RuntimeInstaller(private val ctx: Context) {

    data class Progress(val stage: String, val fraction: Float)

    companion object {
        const val WINLATOR_VERSION = "v11.2.0"
        const val WINLATOR_APK_URL =
            "https://github.com/brunodev85/winlator/releases/download/v11.2.0/Winlator_11.2.apk"
        private val FROM = "/data/data/com.winlator/".toByteArray()
        private const val MAX_PATCH_SIZE = 96L * 1024 * 1024
    }

    private val toBytes: ByteArray = run {
        val base = "/data/data/${ctx.packageName}"
        val pad = FROM.size - base.length
        require(pad >= 1) { "packageName too long for same-length patching" }
        (base + "/".repeat(pad)).toByteArray()
    }

    val rootfs = File(ctx.filesDir, "rootfs")
    private val marker = File(rootfs, ".qss-installed")
    val isInstalled get() = marker.exists() && File(rootfs, "usr/local/bin/box64").exists()

    fun install(onProgress: (Progress) -> Unit) {
        val apk = File(ctx.getExternalFilesDir(null) ?: ctx.cacheDir, "Winlator_11.2.apk")
        download(apk) { onProgress(Progress("Downloading Winlator $WINLATOR_VERSION (~157 MB)", it)) }

        rootfs.deleteRecursively(); rootfs.mkdirs()
        ZipFile(apk).use { zip ->
            fun extract(asset: String, dest: File, label: String) {
                val e = zip.getEntry(asset) ?: error("Missing $asset in Winlator APK")
                dest.mkdirs()
                CountingStream(zip.getInputStream(e), e.size) { onProgress(Progress(label, it)) }
                    .use { untarZstd(it, dest) }
            }
            extract("assets/rootfs.tzst", rootfs, "Extracting rootfs + Wine")
            extract("assets/box64/box64-0.4.4.tzst", rootfs, "Extracting Box64")
            extract("assets/container_pattern.tzst", File(rootfs, "home/xuser"), "Creating Wine prefix")
            extract("assets/dxwrapper/dxvk-2.4.1.tzst", File(rootfs, "opt/dxvk"), "Extracting DXVK (optional)")
        }
        File(rootfs, "tmp/shm").mkdirs()
        File(rootfs, "home/xuser/.wine/drive_c/games").mkdirs()
        marker.writeText(WINLATOR_VERSION)
        onProgress(Progress("Runtime installed", 1f))
    }

    private fun download(dest: File, onFraction: (Float) -> Unit) {
        var have = if (dest.exists()) dest.length() else 0L
        val conn = URL(WINLATOR_APK_URL).openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = true
        if (have > 0) conn.setRequestProperty("Range", "bytes=$have-")
        conn.connect()
        if (conn.responseCode == 416) { onFraction(1f); return } // already complete
        if (conn.responseCode == 200) have = 0
        val total = have + conn.contentLengthLong.coerceAtLeast(0)
        FileOutputStream(dest, have > 0).use { out ->
            conn.inputStream.use { inp ->
                val buf = ByteArray(1 shl 16); var n: Int; var done = have
                while (inp.read(buf).also { n = it } >= 0) {
                    out.write(buf, 0, n); done += n
                    if (total > 0) onFraction(done.toFloat() / total)
                }
            }
        }
    }

    private fun untarZstd(input: InputStream, dest: File) {
        val destPath = dest.canonicalPath
        TarArchiveInputStream(ZstdInputStream(BufferedInputStream(input, 1 shl 16))).use { tar ->
            while (true) {
                val entry: TarArchiveEntry = tar.nextEntry ?: break
                val out = File(dest, entry.name.removePrefix("./"))
                if (!out.absolutePath.startsWith(destPath) && !out.canonicalPath.startsWith(destPath)) continue
                when {
                    entry.isDirectory -> out.mkdirs()
                    entry.isSymbolicLink -> {
                        out.parentFile?.mkdirs(); out.delete()
                        runCatching { Os.symlink(patchString(entry.linkName), out.path) }
                    }
                    entry.isLink -> {
                        val src = File(dest, entry.linkName.removePrefix("./"))
                        out.parentFile?.mkdirs(); if (src.exists()) src.copyTo(out, true)
                    }
                    else -> {
                        out.parentFile?.mkdirs()
                        if (entry.size in 1..MAX_PATCH_SIZE) {
                            val bytes = tar.readBytes(); patchBytes(bytes); out.writeBytes(bytes)
                        } else FileOutputStream(out).use { tar.copyTo(it) }
                        runCatching { Os.chmod(out.path, (entry.mode and 0x1FF) or 0x180) }
                    }
                }
            }
        }
    }

    private fun patchString(s: String) = String(s.toByteArray().also { patchBytes(it) })

    /** Same-length in-place replace of FROM -> toBytes. */
    private fun patchBytes(b: ByteArray) {
        val f = FROM; var i = 0
        outer@ while (i <= b.size - f.size) {
            for (j in f.indices) if (b[i + j] != f[j]) { i++; continue@outer }
            System.arraycopy(toBytes, 0, b, i, f.size); i += f.size
        }
    }

    private class CountingStream(val inner: InputStream, val total: Long, val cb: (Float) -> Unit) : InputStream() {
        var n = 0L; var last = 0L
        private fun tick(k: Int) { if (k > 0) { n += k; if (n - last > 1_000_000) { last = n; cb(if (total > 0) n.toFloat() / total else 0f) } } }
        override fun read(): Int = inner.read().also { if (it >= 0) tick(1) }
        override fun read(b: ByteArray, o: Int, l: Int): Int = inner.read(b, o, l).also { tick(it) }
        override fun close() = inner.close()
    }
}
