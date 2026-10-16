package com.toer15.queststeam.steamclient

import `in`.dragonbra.javasteam.depotdownloader.DepotDownloader
import `in`.dragonbra.javasteam.depotdownloader.IDownloadListener
import `in`.dragonbra.javasteam.depotdownloader.data.AppItem
import `in`.dragonbra.javasteam.depotdownloader.data.DownloadItem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicLong

open class SteamDownloadException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** No data for a while: the caller reconnects and resumes. */
class SteamStallException(message: String) : SteamDownloadException(message)

/**
 * Downloads a game's Windows depots from Steam's CDN with JavaSteam's DepotDownloader,
 * using the signed-in [SteamSession]. Files already present are validated and reused, so a
 * retry after a failure resumes instead of starting over.
 */
class SteamDownloader(private val session: SteamSession) {

    // DepotDownloader keeps a process-wide depot config store, so run one game at a time.
    private val oneAtATime = Mutex()

    /**
     * @param onStatus short phase text for the UI ("Connecting to Steam…", "Reading manifests…").
     * @param progress bytes written so far and the estimated total (-1 while unknown).
     */
    suspend fun download(
        appId: Int,
        dest: File,
        onStatus: (String) -> Unit = {},
        progress: (Long, Long) -> Unit,
    ) = oneAtATime.withLock {
        onStatus("Connecting to Steam…")
        val client = session.ensureOnline()
        val licenses = session.licenses()
        dest.mkdirs()
        // The real install size from Steam's app info, so the card shows "0% of 4.5 GB" from the start.
        val sizeHint = runCatching { session.appSizes(listOf(appId))[appId] }.getOrNull() ?: -1L
        if (sizeHint > 0) progress(0L, sizeHint)

        val tracker = DepotProgress()
        val lastActivity = AtomicLong(System.currentTimeMillis())
        val validating = java.util.concurrent.atomic.AtomicBoolean(false)
        val done = CompletableDeferred<Unit>()
        fun touch() = lastActivity.set(System.currentTimeMillis())
        fun report(p: Pair<Long, Long>) {
            val (d, t) = p
            // Depot sizes become known one by one; Steam's total is right from the start.
            val total = if (sizeHint > 0) maxOf(sizeHint, t) else t
            progress(d.coerceAtMost(if (total > 0) total else d), total)
        }

        val dd = DepotDownloader(client, licenses, maxDownloads = 8)
        dd.addListener(object : IDownloadListener {
            override fun onStatusUpdate(message: String) {
                touch()
                validating.set(message.startsWith("Validating"))
                onStatus(
                    when {
                        message.startsWith("Downloading manifest") -> "Reading game manifest…"
                        message.startsWith("Validating") -> "Checking existing files…"
                        message.startsWith("Allocating") -> "Preparing files…"
                        else -> message
                    }
                )
            }

            override fun onChunkCompleted(depotId: Int, depotPercentComplete: Float, compressedBytes: Long, uncompressedBytes: Long) {
                touch()
                validating.set(false)
                report(tracker.chunk(depotId, depotPercentComplete, uncompressedBytes))
            }

            override fun onFileCompleted(depotId: Int, fileName: String, depotPercentComplete: Float) {
                touch()
                report(tracker.fraction(depotId, depotPercentComplete))
            }

            override fun onDepotCompleted(depotId: Int, compressedBytes: Long, uncompressedBytes: Long) {
                touch()
                report(tracker.depotDone(depotId, uncompressedBytes))
            }

            override fun onDownloadCompleted(item: DownloadItem) { done.complete(Unit) }

            override fun onDownloadFailed(item: DownloadItem, error: Throwable) {
                done.completeExceptionally(SteamDownloadException(error.message ?: error.toString(), error))
            }
        })

        try {
            withContext(Dispatchers.IO) {
                dd.add(
                    AppItem(
                        appId = appId,
                        installDirectory = dest.absolutePath,
                        os = "windows",
                        osArch = "64",
                        language = "english",
                    )
                )
                dd.finishAdding()
            }
            coroutineScope {
                // DepotDownloader can wait forever if a chunk fails on every CDN server: give up after a quiet
                // period (the caller retries and resumes). Checking existing files sends no callbacks, so
                // while files are being written or validated, growing files on disk also count as activity.
                val watchdog = launch(Dispatchers.IO) {
                    var lastDisk = diskBytes(dest)
                    var ticks = 0
                    while (true) {
                        delay(5_000)
                        if (++ticks % 3 == 0) {
                            val disk = diskBytes(dest)
                            if (disk != lastDisk) { lastDisk = disk; touch() }
                        }
                        val limit = if (validating.get()) VALIDATE_STALL_MS else STALL_MS
                        if (System.currentTimeMillis() - lastActivity.get() > limit) {
                            done.completeExceptionally(SteamStallException("Steam sent no data for ${limit / 60_000} minutes."))
                        }
                    }
                }
                try { done.await() } finally { watchdog.cancel() }
            }
            // Finished: show 100% even if Steam's size estimate included depots this install skips.
            val (d, t) = tracker.totals()
            val end = maxOf(d, t, 0L).let { if (it > 0) it else maxOf(sizeHint, 0L) }
            if (end > 0) progress(end, end)
        } finally {
            withContext(Dispatchers.IO) { runCatching { dd.close() } }
        }
        // Remember how Steam starts this game, so Play runs the right .exe (not just the biggest one).
        runCatching { session.launchOptions(appId) }.getOrNull()?.let { o ->
            val lines = listOfNotNull(o.flat ?: o.vr, o.vr).flatMap { listOf(it.executable, it.arguments, it.workingDir) }
            if (lines.isNotEmpty()) File(dest, LAUNCH_FILE).writeText(lines.joinToString("\n", postfix = "\n"))
        }
    }

    /** Bytes of all files under [dir] (cheap enough every 15 s, even for big games). */
    private fun diskBytes(dir: File): Long = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    companion object {
        const val STALL_MS = 2 * 60_000L
        /** Checking existing files of a big game can take a while on the headset. */
        const val VALIDATE_STALL_MS = 20 * 60_000L
        /** Written into the game folder: executable, arguments, working dir (one per line); then the same for the VR entry, if any. */
        const val LAUNCH_FILE = ".qss-launch"
    }
}

/**
 * Turns DepotDownloader's per-depot callbacks into one (bytesDone, bytesTotal) pair.
 * Chunk callbacks carry bytes + fraction, which gives each depot's size; file callbacks only
 * carry the fraction. Total is -1 until at least one depot's size is known.
 */
class DepotProgress {
    private class Depot(var fraction: Float = 0f, var size: Long = -1)
    private val depots = LinkedHashMap<Int, Depot>()

    @Synchronized
    fun chunk(depotId: Int, fraction: Float, bytes: Long): Pair<Long, Long> {
        val d = depots.getOrPut(depotId) { Depot() }
        d.fraction = maxOf(d.fraction, fraction.coerceIn(0f, 1f))
        if (fraction > 0.0005f && bytes > 0) d.size = maxOf(Math.round(bytes / fraction.toDouble()), bytes)
        return totals()
    }

    @Synchronized
    fun fraction(depotId: Int, fraction: Float): Pair<Long, Long> {
        val d = depots.getOrPut(depotId) { Depot() }
        d.fraction = maxOf(d.fraction, fraction.coerceIn(0f, 1f))
        return totals()
    }

    @Synchronized
    fun depotDone(depotId: Int, bytes: Long): Pair<Long, Long> {
        val d = depots.getOrPut(depotId) { Depot() }
        d.fraction = 1f
        if (bytes > 0) d.size = bytes
        return totals()
    }

    @Synchronized
    fun totals(): Pair<Long, Long> {
        val known = depots.values.filter { it.size > 0 }
        if (known.isEmpty()) return 0L to -1L
        val total = known.sumOf { it.size }
        val done = known.sumOf { (it.size * it.fraction.toDouble()).toLong() }
        return done.coerceAtMost(total) to total
    }
}
