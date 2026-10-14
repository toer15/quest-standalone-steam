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

class SteamDownloadException(message: String, cause: Throwable? = null) : Exception(message, cause)

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

        val tracker = DepotProgress()
        val lastActivity = AtomicLong(System.currentTimeMillis())
        val done = CompletableDeferred<Unit>()
        fun touch() = lastActivity.set(System.currentTimeMillis())

        val dd = DepotDownloader(client, licenses, maxDownloads = 8)
        dd.addListener(object : IDownloadListener {
            override fun onStatusUpdate(message: String) {
                touch()
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
                val (d, t) = tracker.chunk(depotId, depotPercentComplete, uncompressedBytes)
                progress(d, t)
            }

            override fun onFileCompleted(depotId: Int, fileName: String, depotPercentComplete: Float) {
                touch()
                val (d, t) = tracker.fraction(depotId, depotPercentComplete)
                progress(d, t)
            }

            override fun onDepotCompleted(depotId: Int, compressedBytes: Long, uncompressedBytes: Long) {
                touch()
                val (d, t) = tracker.depotDone(depotId, uncompressedBytes)
                progress(d, t)
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
                // DepotDownloader can wait forever if a chunk fails on every CDN server; give up after a quiet period.
                val watchdog = launch {
                    while (true) {
                        delay(5_000)
                        if (System.currentTimeMillis() - lastActivity.get() > STALL_MS) {
                            done.completeExceptionally(SteamDownloadException("Download stalled: Steam sent no data for ${STALL_MS / 60_000} minutes. Tap Download to resume."))
                        }
                    }
                }
                try { done.await() } finally { watchdog.cancel() }
            }
        } finally {
            withContext(Dispatchers.IO) { runCatching { dd.close() } }
        }
    }

    companion object {
        const val STALL_MS = 3 * 60_000L
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

    private fun totals(): Pair<Long, Long> {
        val known = depots.values.filter { it.size > 0 }
        if (known.isEmpty()) return 0L to -1L
        val total = known.sumOf { it.size }
        val done = known.sumOf { (it.size * it.fraction.toDouble()).toLong() }
        return done.coerceAtMost(total) to total
    }
}
