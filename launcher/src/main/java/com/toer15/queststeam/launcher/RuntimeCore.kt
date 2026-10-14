package com.toer15.queststeam.launcher

import com.github.luben.zstd.ZstdInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * Pure-JVM runtime install logic (no Android APIs) so it can be unit-tested on a desktop JVM
 * against the real Winlator APK. See RuntimeCoreTest.
 */
object RuntimeCore {

    data class Asset(val zipEntry: String, val destSubdir: String, val label: String)

    const val WINLATOR_VERSION = "v11.2.0"
    const val WINLATOR_APK_URL =
        "https://github.com/brunodev85/winlator/releases/download/v11.2.0/Winlator_11.2.apk"
    const val WINLATOR_APK_SIZE = 157_549_075L
    const val WINLATOR_APK_SHA256 = "ea5c81bf0e5b90a6b9ccb8007121ab6738c8646a5a60b341ce0ac2b33f1d100a"
    const val FROM_PREFIX = "/data/data/com.winlator/"

    val ASSETS = listOf(
        Asset("assets/rootfs.tzst", "", "Extracting rootfs + Wine"),
        Asset("assets/box64/box64-0.4.4.tzst", "", "Extracting Box64"),
        Asset("assets/container_pattern.tzst", "home/xuser", "Creating Wine prefix"),
        Asset("assets/rootfs_patches.tzst", "", "Applying Winlator patches"),
        // GPU: Mesa Turnip (Vulkan for Adreno, e.g. Quest 3's XR2 Gen 2) + DXVK (D3D9/10/11 -> Vulkan) in the prefix.
        Asset("assets/graphics_driver/turnip-26.2.0.tzst", "", "Installing Turnip GPU driver"),
        Asset("assets/dxwrapper/dxvk-2.4.1.tzst", "home/xuser/.wine/drive_c/windows", "Installing DXVK"),
    )

    /** Bumped when [ASSETS] change so existing installs are redone. */
    const val LAYOUT_VERSION = 3

    /** Environment for `box64 wine ...` in the installed runtime at [r] (pure, so tests use the same one). */
    fun wineEnvironment(r: String, display: String?, mode: String, extra: Map<String, String> = emptyMap()): Map<String, String> =
        buildMap {
            put("HOME", "$r/home/xuser")
            put("USER", "xuser")
            put("TMPDIR", "$r/tmp")
            put("WINEPREFIX", "$r/home/xuser/.wine")
            put("WINEDEBUG", "-all,err+all")
            put("PATH", "$r/usr/local/bin:$r/opt/wine/bin:$r/usr/bin:/system/bin")
            put("LD_LIBRARY_PATH", "$r/usr/lib:/system/lib64")
            put("BOX64_LD_LIBRARY_PATH", "$r/usr/lib/x86_64-linux-gnu:$r/opt/wine/lib/wine/x86_64-unix")
            put("BOX64_PATH", "$r/opt/wine/bin")
            put("BOX64_DYNAREC", "1")
            put("BOX64_LOG", "0")
            // DXVK (installed into the prefix) for Direct3D 9/10/11, the game's own openvr_api.dll (our bridge).
            put("WINEDLLOVERRIDES", "d3d11,dxgi,d3d10core,d3d9=n,b;openvr_api=n,b;winemenubuilder.exe=d")
            // Turnip: Mesa's Vulkan driver for Adreno GPUs (Quest 3 = Adreno 740), from Winlator.
            put("VK_ICD_FILENAMES", "$r/usr/share/vulkan/icd.d/freedreno_icd.aarch64.json")
            put("TU_OVERRIDE_HEAP_SIZE", "4096")
            put("MESA_VK_WSI_PRESENT_MODE", "mailbox")
            put("MESA_SHADER_CACHE_DIR", "$r/tmp/mesa-cache")
            put("vblank_mode", "0")
            put("DXVK_LOG_LEVEL", "warn")
            put("DXVK_STATE_CACHE_PATH", "$r/tmp")
            put("QSS_MODE", mode)
            if (!display.isNullOrBlank()) put("DISPLAY", display)
            putAll(extra)
        }

    /** "/data/data/<pkg>" padded with '/' to the same length as [FROM_PREFIX]. */
    fun replacementPrefix(packageName: String): String {
        val base = "/data/data/$packageName"
        val pad = FROM_PREFIX.length - base.length
        require(pad >= 1) { "packageName '$packageName' too long for same-length patching" }
        return base + "/".repeat(pad)
    }

    fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { i -> val b = ByteArray(1 shl 16); while (true) { val n = i.read(b); if (n < 0) break; md.update(b, 0, n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun isValidApk(f: File) = f.isFile && f.length() == WINLATOR_APK_SIZE && sha256(f) == WINLATOR_APK_SHA256

    /**
     * Downloads to "<dest>.part", resuming only if the server answers 206 with a matching
     * Content-Range; a 200 restarts from zero (truncating). Then verifies size + SHA-256 and
     * atomically renames to [dest]. Returns the verified file or throws with a clear message.
     */
    fun downloadVerified(dest: File, log: (String) -> Unit, progress: (Float) -> Unit): File {
        if (dest.exists()) {
            log("Found cached ${dest.name} (${dest.length()} bytes), verifying SHA-256…")
            if (isValidApk(dest)) { log("Cache OK."); progress(1f); return dest }
            log("Cache invalid, deleting."); dest.delete()
        }
        val part = File(dest.path + ".part")
        for (attempt in 1..3) {
            try {
                downloadOnce(part, log, progress)
            } catch (e: IOException) {
                log("Download attempt $attempt failed after ${part.length()} bytes: $e")
                continue
            }
            val size = part.length()
            if (size != WINLATOR_APK_SIZE) {
                log("Size mismatch: got $size, expected $WINLATOR_APK_SIZE. Deleting and retrying.")
                part.delete(); continue
            }
            val hash = sha256(part)
            log("Downloaded $size bytes, sha256=$hash")
            if (hash != WINLATOR_APK_SHA256) {
                log("SHA-256 mismatch (expected $WINLATOR_APK_SHA256). Deleting and retrying.")
                part.delete(); continue
            }
            if (!part.renameTo(dest)) { part.copyTo(dest, true); part.delete() }
            return dest
        }
        throw IOException("Could not download a valid Winlator APK after 3 attempts")
    }

    private fun downloadOnce(part: File, log: (String) -> Unit, progress: (Float) -> Unit) {
        var have = if (part.exists()) part.length() else 0L
        if (have > WINLATOR_APK_SIZE) { part.delete(); have = 0 }
        if (have == WINLATOR_APK_SIZE) return
        val conn = URL(WINLATOR_APK_URL).openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = true
        conn.connectTimeout = 30_000; conn.readTimeout = 60_000
        conn.setRequestProperty("Accept-Encoding", "identity")
        if (have > 0) conn.setRequestProperty("Range", "bytes=$have-")
        val code = conn.responseCode
        val ctype = conn.contentType ?: "?"
        val append = when (code) {
            206 -> {
                val cr = conn.getHeaderField("Content-Range") ?: ""
                if (cr.startsWith("bytes $have-")) true else { log("Unexpected Content-Range '$cr', restarting"); have = 0; false }
            }
            200 -> { if (have > 0) log("Server ignored Range (200), restarting from 0"); have = 0; false }
            416 -> { log("416 for resume, restarting"); part.delete(); throw IOException("HTTP 416") }
            else -> throw IOException("HTTP $code ($ctype) from ${conn.url}")
        }
        if (ctype.startsWith("text/")) throw IOException("Got $ctype instead of an APK from ${conn.url}")
        log("HTTP $code, ${conn.contentLengthLong} bytes to fetch, resume from $have, host ${conn.url.host}")
        if (!append) {
            // Server sends from 0: must not resume. Start fresh (but keep reading this response).
            if (have != 0L) have = 0
        }
        val out: OutputStream = java.io.FileOutputStream(part, append)
        out.use { o ->
            conn.inputStream.use { inp ->
                val buf = ByteArray(1 shl 16); var done = have; var last = 0L
                while (true) {
                    val n = inp.read(buf); if (n < 0) break
                    o.write(buf, 0, n); done += n
                    if (done - last > 2_000_000) { last = done; progress(done.toFloat() / WINLATOR_APK_SIZE) }
                }
                progress(done.toFloat() / WINLATOR_APK_SIZE)
            }
        }
    }

    /** Extracts all [ASSETS] from the APK (random-access ZipFile) into [rootfs], patching paths. */
    fun extractAll(apk: File, rootfs: File, packageName: String, progress: (String, Float) -> Unit) {
        val to = replacementPrefix(packageName).toByteArray()
        val from = FROM_PREFIX.toByteArray()
        ZipFile(apk).use { zip ->
            for (a in ASSETS) {
                val e = zip.getEntry(a.zipEntry) ?: throw IOException("Missing ${a.zipEntry} in ${apk.name}")
                val dest = if (a.destSubdir.isEmpty()) rootfs else File(rootfs, a.destSubdir)
                dest.mkdirs()
                CountingStream(zip.getInputStream(e), e.compressedSize.coerceAtLeast(e.size)) { progress(a.label, it) }
                    .use { untarZstd(it, dest, from, to) }
                // The prefix template has an empty system32: Winlator fills it from Wine when it creates a
                // container. Without this Wine exits with "could not load kernel32.dll" (exit code 53).
                if (a.zipEntry == "assets/container_pattern.tzst") installWineDlls(rootfs)
                progress(a.label, 1f)
            }
        }
        File(rootfs, "tmp/shm").mkdirs()
        File(rootfs, "home/xuser/.wine/drive_c/games").mkdirs()
    }

    /**
     * Links Wine's builtin Windows binaries into the prefix: x86_64-windows -> system32 and
     * i386-windows -> syswow64. Symlinks save ~280 MB; DXVK, extracted later, replaces its own DLLs.
     */
    fun installWineDlls(rootfs: File) {
        val wineLib = File(rootfs, "opt/wine/lib/wine")
        val windows = File(rootfs, "home/xuser/.wine/drive_c/windows")
        for ((src, dst) in listOf("x86_64-windows" to "system32", "i386-windows" to "syswow64")) {
            val from = File(wineLib, src)
            val to = File(windows, dst).apply { mkdirs() }
            from.listFiles()?.forEach { f ->
                if (!f.isFile) return@forEach
                val out = File(to, f.name)
                if (out.exists()) return@forEach
                try {
                    Files.createSymbolicLink(out.toPath(), f.toPath())
                } catch (e: Exception) {
                    f.copyTo(out)
                }
            }
        }
    }

    /** True when Wine's core DLLs are in the prefix (else wine exits with code 53, "could not load kernel32.dll"). */
    fun prefixHealthy(rootfs: File) =
        File(rootfs, "home/xuser/.wine/drive_c/windows/system32/kernel32.dll").exists() &&
            File(rootfs, "home/xuser/.wine/drive_c/windows/system32/ntdll.dll").exists()

    /** Repairs a prefix from an older install by linking Wine's DLLs in. Returns true if it changed anything. */
    fun repairPrefix(rootfs: File): Boolean {
        if (prefixHealthy(rootfs) || !File(rootfs, "opt/wine/lib/wine/x86_64-windows/kernel32.dll").exists()) return false
        installWineDlls(rootfs)
        File(rootfs, "tmp").mkdirs()
        return prefixHealthy(rootfs)
    }

    fun untarZstd(input: InputStream, dest: File, from: ByteArray, to: ByteArray) {
        val destPath = dest.canonicalPath
        TarArchiveInputStream(ZstdInputStream(BufferedInputStream(input, 1 shl 16))).use { tar ->
            while (true) {
                val entry: TarArchiveEntry = tar.nextEntry ?: break
                val name = entry.name.removePrefix("./").trimEnd('/')
                if (name.isEmpty() || name.split('/').contains("..")) continue
                val out = File(dest, name)
                when {
                    entry.isDirectory -> out.mkdirs()
                    entry.isSymbolicLink -> {
                        out.parentFile?.mkdirs()
                        Files.deleteIfExists(out.toPath())
                        val target = String(entry.linkName.toByteArray().also { patchInPlace(it, from, to) })
                        Files.createSymbolicLink(out.toPath(), File(target).toPath())
                    }
                    entry.isLink -> {
                        val src = File(dest, entry.linkName.removePrefix("./"))
                        out.parentFile?.mkdirs(); if (src.exists()) src.copyTo(out, true)
                    }
                    else -> {
                        out.parentFile?.mkdirs()
                        Files.deleteIfExists(out.toPath())
                        out.outputStream().use { copyPatched(tar, it, from, to) }
                        // Winlator ships some binaries (e.g. box64) as 0644 and chmods at runtime.
                        setMode(out, if (looksExecutable(out)) entry.mode or 0x49 else entry.mode)
                    }
                }
                check(out.toPath().toAbsolutePath().normalize().toString().startsWith(destPath) || out.absolutePath.startsWith(destPath))
            }
        }
    }

    /** ELF binaries and shebang scripts. */
    fun looksExecutable(f: File): Boolean = runCatching {
        f.inputStream().use { i ->
            val h = ByteArray(4); val n = i.read(h)
            (n == 4 && h[0] == 0x7f.toByte() && h[1] == 'E'.code.toByte() && h[2] == 'L'.code.toByte() && h[3] == 'F'.code.toByte()) ||
                (n >= 2 && h[0] == '#'.code.toByte() && h[1] == '!'.code.toByte())
        }
    }.getOrDefault(false)

    private fun setMode(f: File, mode: Int) {
        val p = HashSet<PosixFilePermission>()
        p += PosixFilePermission.OWNER_READ; p += PosixFilePermission.OWNER_WRITE
        if (mode and 0x40 != 0) p += PosixFilePermission.OWNER_EXECUTE
        if (mode and 0x20 != 0) p += PosixFilePermission.GROUP_READ
        if (mode and 0x08 != 0) p += PosixFilePermission.GROUP_EXECUTE
        if (mode and 0x04 != 0) p += PosixFilePermission.OTHERS_READ
        if (mode and 0x01 != 0) p += PosixFilePermission.OTHERS_EXECUTE
        runCatching { Files.setPosixFilePermissions(f.toPath(), p) }.onFailure {
            if (mode and 0x40 != 0) f.setExecutable(true, false)
        }
    }

    /** Streams [inp] to [out] replacing [from] with same-length [to], also across chunk boundaries. */
    fun copyPatched(inp: InputStream, out: OutputStream, from: ByteArray, to: ByteArray) {
        require(from.size == to.size)
        val keep = from.size - 1
        val buf = ByteArray((1 shl 16) + keep)
        var len = 0
        while (true) {
            val n = inp.read(buf, len, buf.size - len)
            if (n < 0) break
            len += n
            if (len < buf.size) continue
            patchInPlace(buf, from, to, len)
            out.write(buf, 0, len - keep)
            System.arraycopy(buf, len - keep, buf, 0, keep); len = keep
        }
        patchInPlace(buf, from, to, len)
        out.write(buf, 0, len)
    }

    fun patchInPlace(b: ByteArray, from: ByteArray, to: ByteArray, len: Int = b.size) {
        var i = 0
        outer@ while (i <= len - from.size) {
            for (j in from.indices) if (b[i + j] != from[j]) { i++; continue@outer }
            System.arraycopy(to, 0, b, i, from.size); i += from.size
        }
    }

    private class CountingStream(val inner: InputStream, val total: Long, val cb: (Float) -> Unit) : InputStream() {
        var n = 0L; var last = 0L
        private fun tick(k: Int) { if (k > 0) { n += k; if (n - last > 1_000_000) { last = n; cb((n.toFloat() / total).coerceAtMost(1f)) } } }
        override fun read(): Int = inner.read().also { if (it >= 0) tick(1) }
        override fun read(b: ByteArray, o: Int, l: Int): Int = inner.read(b, o, l).also { tick(it) }
        override fun close() = inner.close()
    }
}
