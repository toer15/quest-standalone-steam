package com.toer15.queststeam.download

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.toer15.queststeam.launcher.GameLauncher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/** Per-game install state shown on the cards. */
sealed interface GameState {
    data object NotInstalled : GameState
    data class Downloading(val bytes: Long, val total: Long, val etaSeconds: Long?, val source: String) : GameState {
        val fraction get() = if (total > 0) bytes.toFloat() / total else 0f
    }
    data object Installed : GameState
    data class Failed(val message: String) : GameState
}

/** Progress callback: bytes done, total bytes (or -1). */
typealias ProgressFn = (Long, Long) -> Unit

/** Pluggable source of a game's Windows files. */
interface DownloadSource {
    val name: String
    /** Writes the game's files into [dest]; throws with a user-readable message on failure. */
    suspend fun fetch(ctx: Context, appId: Int, dest: File, progress: ProgressFn)
}

/**
 * Steam depot download. Not implemented yet: needs a Steam CM client (e.g. JavaSteam's
 * ContentDownloader) with the user's own Steam login + Steam Guard, depot manifests and keys.
 * Kept as a pluggable source so it can be dropped in without touching the UI.
 */
class SteamDepotSource : DownloadSource {
    override val name = "Steam"
    override suspend fun fetch(ctx: Context, appId: Int, dest: File, progress: ProgressFn) {
        throw UnsupportedOperationException(
            "Direct Steam downloads aren't implemented yet. Download the game's Windows files on a PC " +
                "(SteamCMD/DepotDownloader, see docs/DEPOTS.md) and use \"Import from folder\"."
        )
    }
}

/** Copies a folder the user picked (SAF tree URI) into the game's install dir. Works today. */
class FolderImportSource(private val tree: Uri) : DownloadSource {
    override val name = "Import"
    override suspend fun fetch(ctx: Context, appId: Int, dest: File, progress: ProgressFn) {
        val root = DocumentFile.fromTreeUri(ctx, tree) ?: error("Can't open folder")
        val total = sizeOf(root)
        var done = 0L
        fun copy(doc: DocumentFile, dir: File) {
            for (c in doc.listFiles()) {
                val n = c.name ?: continue
                if (c.isDirectory) copy(c, File(dir, n).apply { mkdirs() })
                else ctx.contentResolver.openInputStream(c.uri)?.use { i ->
                    File(dir, n).outputStream().use { o ->
                        val buf = ByteArray(1 shl 16)
                        while (true) { val k = i.read(buf); if (k < 0) break; o.write(buf, 0, k); done += k; progress(done, total) }
                    }
                }
            }
        }
        dest.mkdirs(); copy(root, dest)
    }
    private fun sizeOf(d: DocumentFile): Long = d.listFiles().sumOf { if (it.isDirectory) sizeOf(it) else it.length() }
}

/** App-wide download/install manager. Install dir: <wine prefix>/drive_c/games/<appId>. */
class DownloadManager private constructor(private val ctx: Context) {
    companion object {
        @Volatile private var inst: DownloadManager? = null
        fun get(ctx: Context) = inst ?: synchronized(this) { inst ?: DownloadManager(ctx.applicationContext).also { inst = it } }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val launcher = GameLauncher(ctx)
    private val jobs = mutableMapOf<Int, Job>()
    private val _states = MutableStateFlow<Map<Int, GameState>>(emptyMap())
    val states: StateFlow<Map<Int, GameState>> = _states

    var primarySource: DownloadSource = SteamDepotSource()

    fun dirFor(appId: Int) = File(launcher.gamesDir, appId.toString())
    private fun marker(appId: Int) = File(dirFor(appId), ".qss-installed")

    fun refresh(appIds: Collection<Int>) = _states.update { m ->
        m + appIds.filter { m[it] !is GameState.Downloading }.associateWith { if (marker(it).exists()) GameState.Installed else GameState.NotInstalled }
    }

    fun stateOf(appId: Int) = _states.value[appId] ?: if (marker(appId).exists()) GameState.Installed else GameState.NotInstalled

    fun download(appId: Int, source: DownloadSource = primarySource) {
        if (jobs[appId]?.isActive == true) return
        if (!launcher.isInstalled && source !is SteamDepotSource) {
            // Files can be imported before the runtime exists, but the prefix dir must exist.
            launcher.gamesDir.mkdirs()
        }
        set(appId, GameState.Downloading(0, -1, null, source.name))
        jobs[appId] = scope.launch {
            val dest = dirFor(appId)
            val start = System.nanoTime()
            try {
                dest.deleteRecursively(); dest.mkdirs()
                var lastEmit = 0L
                source.fetch(ctx, appId, dest) { done, total ->
                    val now = System.nanoTime()
                    if (now - lastEmit < 200_000_000L && done != total) return@fetch
                    lastEmit = now
                    val secs = (now - start) / 1e9
                    val rate = if (secs > 0.5) done / secs else 0.0
                    val eta = if (rate > 0 && total > 0) ((total - done) / rate).toLong() else null
                    set(appId, GameState.Downloading(done, total, eta, source.name))
                }
                if (!isActive) return@launch
                if (launcher.findExes(dest).isEmpty()) throw IllegalStateException("No .exe found in the imported files")
                marker(appId).writeText(source.name)
                set(appId, GameState.Installed)
            } catch (e: Throwable) {
                dest.deleteRecursively()
                set(appId, GameState.Failed(e.message ?: e.toString()))
            }
        }
    }

    fun cancel(appId: Int) { jobs.remove(appId)?.cancel(); dirFor(appId).deleteRecursively(); set(appId, GameState.NotInstalled) }

    fun uninstall(appId: Int) { cancel(appId) }

    fun mainExe(appId: Int): File? = launcher.findExes(dirFor(appId)).firstOrNull()

    private fun set(appId: Int, s: GameState) = _states.update { it + (appId to s) }
}
