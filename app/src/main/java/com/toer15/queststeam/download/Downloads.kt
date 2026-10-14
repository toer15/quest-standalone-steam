package com.toer15.queststeam.download

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.toer15.queststeam.CrashGuard
import com.toer15.queststeam.launcher.GameLauncher
import com.toer15.queststeam.steam.SteamAccount
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
    data class Downloading(val bytes: Long, val total: Long, val etaSeconds: Long?, val source: String, val note: String? = null) : GameState {
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
    /** True when files already in the install dir can be reused (resume), so they aren't wiped first. */
    val resumable: Boolean get() = false
    /** Writes the game's files into [dest]; throws with a user-readable message on failure. */
    suspend fun fetch(ctx: Context, appId: Int, dest: File, status: (String) -> Unit, progress: ProgressFn)
}

/**
 * Downloads the game's Windows depots straight from Steam's CDN (JavaSteam DepotDownloader)
 * with the player's own Steam sign-in. Existing files are validated and reused on retry.
 */
class SteamDepotSource : DownloadSource {
    override val name = "Steam"
    override val resumable = true
    override suspend fun fetch(ctx: Context, appId: Int, dest: File, status: (String) -> Unit, progress: ProgressFn) {
        val account = SteamAccount.get(ctx)
        if (!account.session.isSignedIn) throw IllegalStateException(
            "To download, sign in with your Steam account: profile picture → Sign in to Steam."
        )
        account.downloader.download(appId, dest, status, progress)
    }
}

/** Copies a folder the user picked (SAF tree URI) into the game's install dir. Works today. */
class FolderImportSource(private val tree: Uri) : DownloadSource {
    override val name = "Import"
    override suspend fun fetch(ctx: Context, appId: Int, dest: File, status: (String) -> Unit, progress: ProgressFn) {
        status("Copying files…")
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

    init {
        // An error on a downloader/Steam thread would otherwise close the app: stop the download and show why.
        CrashGuard.onBackgroundError = { msg ->
            android.os.Handler(android.os.Looper.getMainLooper()).post { failActive("Download stopped: $msg. Tap Retry download to continue.") }
        }
    }

    private fun failActive(message: String) {
        val active = jobs.filterValues { it.isActive }.keys.toList()
        for (id in active) {
            jobs.remove(id)?.cancel()
            set(id, GameState.Failed(message))
        }
    }

    fun dirFor(appId: Int) = File(launcher.gamesDir, appId.toString())
    private fun marker(appId: Int) = File(dirFor(appId), ".qss-installed")

    fun refresh(appIds: Collection<Int>) = _states.update { m ->
        m + appIds.filter { m[it] !is GameState.Downloading }.associateWith { if (marker(it).exists()) GameState.Installed else GameState.NotInstalled }
    }

    fun stateOf(appId: Int) = _states.value[appId] ?: if (marker(appId).exists()) GameState.Installed else GameState.NotInstalled

    fun download(appId: Int, source: DownloadSource = primarySource) {
        if (jobs[appId]?.isActive == true) return
        // Files can arrive before the runtime is installed, but the prefix dir must exist.
        launcher.gamesDir.mkdirs()
        set(appId, GameState.Downloading(0, -1, null, source.name, "Starting…"))
        jobs[appId] = scope.launch {
            val dest = dirFor(appId)
            var note: String? = "Starting…"
            var last = GameState.Downloading(0, -1, null, source.name, note)
            // Rate from bytes received in this run only, so resumed bytes don't inflate it.
            var firstBytes = -1L
            var firstAt = 0L
            try {
                if (!source.resumable) dest.deleteRecursively()
                dest.mkdirs()
                var lastEmit = 0L
                source.fetch(ctx, appId, dest, status = { msg ->
                    note = msg
                    last = last.copy(note = msg)
                    set(appId, last)
                }) { done, total ->
                    val now = System.nanoTime()
                    if (now - lastEmit < 250_000_000L && done != total) return@fetch
                    lastEmit = now
                    if (firstBytes < 0) { firstBytes = done; firstAt = now }
                    val secs = (now - firstAt) / 1e9
                    val rate = if (secs > 1.0) (done - firstBytes) / secs else 0.0
                    val eta = if (rate > 0 && total > 0) ((total - done) / rate).toLong() else null
                    last = GameState.Downloading(done, total, eta, source.name, note)
                    set(appId, last)
                }
                if (!isActive) return@launch
                if (launcher.findExes(dest).isEmpty()) throw IllegalStateException("Download finished, but the game has no Windows .exe")
                marker(appId).writeText(source.name)
                set(appId, GameState.Installed)
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                // Keep Steam files so the next tap on Download resumes; imports start over.
                if (!source.resumable) dest.deleteRecursively()
                set(appId, GameState.Failed(e.message ?: e.toString()))
            }
        }
    }

    fun cancel(appId: Int) { jobs.remove(appId)?.cancel(); dirFor(appId).deleteRecursively(); set(appId, GameState.NotInstalled) }

    fun uninstall(appId: Int) { cancel(appId) }

    /** The .exe Steam itself starts (saved at download time), else the largest .exe in the game folder. */
    /** The .exe Steam starts for this game; [vr] picks Steam's separate "Launch in VR" entry when there is one. */
    fun mainExe(appId: Int, vr: Boolean = false): File? {
        val dir = dirFor(appId)
        val lines = launchLines(appId)
        val rel = (if (vr) lines?.getOrNull(3) else null)?.takeIf { it.isNotBlank() } ?: lines?.firstOrNull()
        rel?.takeIf { it.isNotBlank() }?.let { r ->
            val f = File(dir, r)
            if (f.isFile) return f
            // Steam paths are case-insensitive on Windows.
            dir.walkTopDown().firstOrNull { it.isFile && it.relativeTo(dir).path.equals(r, ignoreCase = true) }?.let { return it }
        }
        return launcher.findExes(dir).firstOrNull()
    }

    /** Launch arguments Steam uses for this game ("" if none). */
    fun launchArgs(appId: Int, vr: Boolean = false): String {
        val lines = launchLines(appId)
        return if (vr && !lines?.getOrNull(3).isNullOrBlank()) lines?.getOrNull(4).orEmpty() else lines?.getOrNull(1).orEmpty()
    }

    /** True if Steam lists a separate VR launch entry for this game. */
    fun hasVrLaunch(appId: Int) = !launchLines(appId)?.getOrNull(3).isNullOrBlank()

    private fun launchLines(appId: Int): List<String>? =
        File(dirFor(appId), com.toer15.queststeam.steamclient.SteamDownloader.LAUNCH_FILE).takeIf { it.isFile }?.readLines()

    private fun set(appId: Int, s: GameState) = _states.update { it + (appId to s) }
}
