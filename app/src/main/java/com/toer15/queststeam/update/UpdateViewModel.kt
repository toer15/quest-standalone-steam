package com.toer15.queststeam.update

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

sealed interface UpdateUi {
    data object Hidden : UpdateUi
    data object Checking : UpdateUi
    data class UpToDate(val version: String) : UpdateUi
    data class Available(val release: Release) : UpdateUi
    data class Downloading(val release: Release, val bytes: Long, val total: Long, val etaSeconds: Long?, val bytesPerSecond: Long) : UpdateUi {
        val fraction get() = if (total > 0) (bytes.toFloat() / total).coerceIn(0f, 1f) else 0f
    }
    data class NeedsPermission(val release: Release, val apk: File) : UpdateUi
    data class Installing(val release: Release) : UpdateUi
    data class Failed(val message: String, val release: Release?) : UpdateUi
}

/** Drives the update popup: automatic check on launch, "Check for updates", download, install. */
class UpdateViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("updates", 0)
    private val _ui = MutableStateFlow<UpdateUi>(UpdateUi.Hidden)
    val ui: StateFlow<UpdateUi> = _ui
    private var job: Job? = null
    @Volatile private var cancelled = false

    init {
        // Quietly check on launch; only pop up when there is something new the player hasn't dismissed.
        viewModelScope.launch {
            val r = runCatching { withContext(Dispatchers.IO) { Updater.fetchLatest() } }.getOrNull() ?: return@launch
            if (Updater.isNewer(r) && prefs.getString("dismissed", null) != r.version && _ui.value == UpdateUi.Hidden) {
                _ui.value = UpdateUi.Available(r)
            }
        }
        viewModelScope.launch {
            Updater.installResult.collect { msg ->
                val cur = _ui.value
                if (msg != null && msg != "Installed" && cur is UpdateUi.Installing) _ui.value = UpdateUi.Failed(msg, cur.release)
            }
        }
    }

    /** "Check for updates" on the Home tab. */
    fun check() {
        job?.cancel()
        _ui.value = UpdateUi.Checking
        job = viewModelScope.launch {
            _ui.value = try {
                val r = withContext(Dispatchers.IO) { Updater.fetchLatest() }
                if (r != null && Updater.isNewer(r)) UpdateUi.Available(r) else UpdateUi.UpToDate(Updater.currentVersion)
            } catch (e: Exception) {
                UpdateUi.Failed(e.message ?: "Couldn't check for updates", null)
            }
        }
    }

    fun updateNow(r: Release) {
        job?.cancel()
        cancelled = false
        _ui.value = UpdateUi.Downloading(r, 0, r.apkSize, null, 0)
        job = viewModelScope.launch {
            try {
                val start = System.nanoTime()
                var lastEmit = 0L
                val apk = withContext(Dispatchers.IO) {
                    Updater.download(getApplication(), r, { cancelled }) { done, total ->
                        val now = System.nanoTime()
                        if (now - lastEmit < 200_000_000L && done != total) return@download
                        lastEmit = now
                        val secs = (now - start) / 1e9
                        val rate = if (secs > 0.3) (done / secs).toLong() else 0L
                        val eta = if (rate > 0 && total > 0) (total - done) / rate else null
                        _ui.value = UpdateUi.Downloading(r, done, total, eta, rate)
                    }
                }
                install(r, apk)
            } catch (e: Exception) {
                if (!cancelled) _ui.value = UpdateUi.Failed(e.message ?: "Download failed", r)
            }
        }
    }

    fun install(r: Release, apk: File) {
        val ctx = getApplication<Application>()
        if (!Updater.canInstall(ctx)) {
            _ui.value = UpdateUi.NeedsPermission(r, apk)
            Updater.openInstallPermissionSettings(ctx)
            return
        }
        _ui.value = UpdateUi.Installing(r)
        try {
            Updater.install(ctx, apk)
        } catch (e: Exception) {
            _ui.value = UpdateUi.Failed("Couldn't start the installer: ${e.message}", r)
        }
    }

    fun cancel() { cancelled = true; job?.cancel(); _ui.value = UpdateUi.Hidden }

    fun dismiss() {
        (_ui.value as? UpdateUi.Available)?.let { prefs.edit().putString("dismissed", it.release.version).apply() }
        job?.cancel()
        _ui.value = UpdateUi.Hidden
    }
}
