package com.toer15.queststeam.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.toer15.queststeam.BuildConfig
import com.toer15.queststeam.download.DownloadManager
import com.toer15.queststeam.steam.OwnedGame
import com.toer15.queststeam.steam.PlayerSummary
import com.toer15.queststeam.steam.SessionStore
import com.toer15.queststeam.steam.SteamApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class LibraryViewModel(app: Application) : AndroidViewModel(app) {
    val downloads = DownloadManager.get(app)
    private val session = SessionStore(app)

    private val _games = MutableStateFlow<List<OwnedGame>>(emptyList())
    val games: StateFlow<List<OwnedGame>> = _games
    private val _profile = MutableStateFlow<PlayerSummary?>(null)
    val profile: StateFlow<PlayerSummary?> = _profile
    private val _status = MutableStateFlow("")
    val status: StateFlow<String> = _status
    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing

    val steamId get() = session.steamId

    init { sync() }

    fun sync() {
        val id = session.steamId ?: return
        if (_syncing.value) return
        val key = BuildConfig.STEAM_API_KEY
        if (key.isBlank()) { _status.value = "No Steam Web API key in this build (STEAM_API_KEY). See README."; return }
        _syncing.value = true
        viewModelScope.launch {
            try {
                _status.value = "Syncing Steam account…"
                _profile.value = SteamApi.getPlayerSummary(key, id)
                val owned = SteamApi.getOwnedGames(key, id).sortedBy { it.name.lowercase() }
                _games.value = owned
                downloads.refresh(owned.map { it.appId })
                var n = 0
                SteamApi.enrich(getApplication(), owned) {
                    n++; if (n % 10 == 0) { _status.value = "Loading genres $n/${owned.size}"; _games.value = owned.toList() }
                }
                _games.value = owned.toList()
                _status.value = "${owned.size} games · ${owned.count { it.isVr }} VR"
            } catch (e: Exception) {
                _status.value = "Sync failed: ${e.message} (is your Steam profile's game details public?)"
            } finally { _syncing.value = false }
        }
    }

    fun signOut() { session.steamId = null; _profile.value = null; _games.value = emptyList() }
}
