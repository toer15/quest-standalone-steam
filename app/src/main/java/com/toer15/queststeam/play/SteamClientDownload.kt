package com.toer15.queststeam.play

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipInputStream

/**
 * Downloads and unpacks the Steam for Windows client natively (fast), instead of letting Steam's
 * Windows updater do it under the Windows translation (slow on the headset). Same files from
 * Valve's update server: the manifest steam_client_win64 / steam_client_win32 lists the packages
 * (zip files) that make up the client; they are unpacked into the Steam folder and kept in
 * Steam/package with the manifest, as Steam's own updater does.
 */
object SteamClientDownload {
    private const val BASE = "https://client-update.steamstatic.com/"

    data class Package(val file: String, val size: Long)
    data class Manifest(val platform: String, val version: String, val text: String, val packages: List<Package>)

    /** True when the client has been unpacked (steamui.dll is part of every Steam client). */
    fun isBootstrapped(steamDir: File) = steamDir.listFiles()?.any { it.name.equals("steamui.dll", true) } == true

    fun fetchManifest(): Manifest {
        var lastError: Exception? = null
        for (platform in listOf("win64", "win32")) {
            try {
                val text = httpText(BASE + "steam_client_$platform")
                val root = Vdf.parse(text)
                val top = root.values.firstOrNull() as? Map<*, *> ?: continue
                val version = top["version"] as? String ?: ""
                val packages = top.values.mapNotNull { v ->
                    val m = v as? Map<*, *> ?: return@mapNotNull null
                    val file = m["file"] as? String ?: return@mapNotNull null
                    Package(file, (m["size"] as? String)?.toLongOrNull() ?: -1L)
                }
                if (packages.isNotEmpty()) return Manifest(platform, version, text, packages)
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw IOException("Couldn't read Steam's package list" + (lastError?.let { ": ${it.message}" } ?: ""))
    }

    /**
     * Downloads every package (4 at a time) into steamDir/package and unpacks it into steamDir.
     * [progress] gets (bytes done, bytes total, what's happening).
     */
    fun install(steamDir: File, log: (String) -> Unit, progress: (Long, Long, String) -> Unit) {
        val manifest = fetchManifest()
        val total = manifest.packages.sumOf { it.size.coerceAtLeast(0) }
        log("Steam ${manifest.platform} client ${manifest.version}: ${manifest.packages.size} packages, ${total / 1_000_000} MB.")
        val pkgDir = File(steamDir, "package").apply { mkdirs() }
        val done = AtomicLong(0)
        val pool = Executors.newFixedThreadPool(4)
        try {
            val jobs: List<Future<File>> = manifest.packages.map { p ->
                pool.submit<File> {
                    val dest = File(pkgDir, p.file)
                    if (dest.isFile && (p.size < 0 || dest.length() == p.size)) {
                        done.addAndGet(dest.length())
                    } else {
                        download(BASE + p.file, dest) { n -> progress(done.addAndGet(n), total, "Downloading Steam") }
                        if (p.size >= 0 && dest.length() != p.size) throw IOException("${p.file}: got ${dest.length()} of ${p.size} bytes")
                    }
                    progress(done.get(), total, "Downloading Steam")
                    dest
                }
            }
            val files = jobs.map { it.get() }
            files.forEachIndexed { i, f ->
                progress(total, total, "Unpacking Steam (${i + 1}/${files.size})")
                unzip(f, steamDir)
            }
        } finally {
            pool.shutdownNow()
        }
        File(pkgDir, "steam_client_${manifest.platform}.manifest").writeText(manifest.text)
        log("Steam's files are in place (${manifest.packages.size} packages).")
    }

    private fun httpText(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 20_000; c.readTimeout = 60_000
        try {
            if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode} for $url")
            return c.inputStream.bufferedReader().readText()
        } finally { c.disconnect() }
    }

    private fun download(url: String, dest: File, onBytes: (Long) -> Unit) {
        val part = File(dest.path + ".part")
        var attempt = 0
        while (true) {
            attempt++
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 20_000; c.readTimeout = 60_000
            try {
                if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode} for $url")
                c.inputStream.use { inp ->
                    part.outputStream().use { out ->
                        val buf = ByteArray(1 shl 16)
                        while (true) {
                            val n = inp.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            onBytes(n.toLong())
                        }
                    }
                }
                dest.delete()
                if (!part.renameTo(dest)) throw IOException("Couldn't save ${dest.name}")
                return
            } catch (e: IOException) {
                if (attempt >= 3) throw e
                onBytes(-part.length().coerceAtLeast(0))
                part.delete()
                Thread.sleep(1500L * attempt)
            } finally { c.disconnect() }
        }
    }

    private fun unzip(zip: File, dest: File) {
        val root = dest.canonicalPath
        ZipInputStream(zip.inputStream().buffered()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                val name = e.name.replace('\\', '/').trimStart('/')
                if (name.isEmpty() || name.split('/').contains("..")) continue
                val out = File(dest, name)
                if (!out.canonicalPath.startsWith(root)) continue
                if (e.isDirectory) { out.mkdirs(); continue }
                out.parentFile?.mkdirs()
                out.outputStream().use { z.copyTo(it) }
            }
        }
    }
}

/** Valve's KeyValues text format ("key" "value" / "key" { … }), enough for Steam's manifests. */
object Vdf {
    fun parse(text: String): Map<String, Any> {
        val tokens = tokenize(text)
        var i = 0
        fun block(): MutableMap<String, Any> {
            val m = LinkedHashMap<String, Any>()
            while (i < tokens.size) {
                val t = tokens[i++]
                if (t == "}") return m
                if (i >= tokens.size) break
                val next = tokens[i]
                if (next == "{") { i++; m[t] = block() } else { i++; m[t] = next }
            }
            return m
        }
        return block()
    }

    private fun tokenize(s: String): List<String> {
        val out = ArrayList<String>()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c.isWhitespace() -> i++
                c == '/' && i + 1 < s.length && s[i + 1] == '/' -> { while (i < s.length && s[i] != '\n') i++ }
                c == '{' || c == '}' -> { out += c.toString(); i++ }
                c == '"' -> {
                    val sb = StringBuilder(); i++
                    while (i < s.length && s[i] != '"') { if (s[i] == '\\' && i + 1 < s.length) { i++ }; sb.append(s[i]); i++ }
                    i++; out += sb.toString()
                }
                else -> { val sb = StringBuilder(); while (i < s.length && !s[i].isWhitespace() && s[i] != '{' && s[i] != '}') { sb.append(s[i]); i++ }; out += sb.toString() }
            }
        }
        return out
    }
}
