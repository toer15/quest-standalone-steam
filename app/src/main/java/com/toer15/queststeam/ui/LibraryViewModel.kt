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
import com.toer15.queststeam.steam.KeylessSteam
import android.webkit.CookieManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

    var apiKey: String?
        get() = session.apiKey
        set(v) { session.apiKey = v; sync() }

    /** Optional fallback only: a key the player pasted, or a personal build-time key. CI has none. */
    private fun effectiveKey(): String? = session.apiKey ?: BuildConfig.STEAM_API_KEY.takeIf { it.isNotBlank() }

    fun sync() {
        val id = session.steamId ?: return
        if (_syncing.value) return
        _syncing.value = true
        viewModelScope.launch {
            try {
                _status.value = "Syncing Steam account…"
                val key = effectiveKey()
                val cookie = CookieManager.getInstance().getCookie("https://steamcommunity.com")
                val prof = withContext(Dispatchers.IO) { runCatching { KeylessSteam.profile(id, cookie) }.getOrNull() }
                if (prof != null) _profile.value = prof.summary
                else if (key != null) _profile.value = SteamApi.getPlayerSummary(key, id)
                // Always read the owner's games page with the sign-in cookies, including private profiles.
                var source = "Steam session"
                val owned = try {
                    withContext(Dispatchers.IO) { KeylessSteam.ownedGames(id, cookie) }
                } catch (e: Exception) {
                    if (key == null) throw e
                    source = "Steam Web API"
                    SteamApi.getOwnedGames(key, id)
                }.sortedBy { it.name.lowercase() }
                if (owned.isEmpty()) {
                    _games.value = emptyList()
                    _status.value = "Steam returned no games."
                    return@launch
                }
                _games.value = owned
                downloads.refresh(owned.map { it.appId })
                var n = 0
                SteamApi.enrich(getApplication(), owned) {
                    n++; if (n % 10 == 0) { _status.value = "Loading genres $n/${owned.size}"; _games.value = owned.toList() }
                }
                _games.value = owned.toList()
                _status.value = "${owned.size} games · ${owned.count { it.isVr }} VR · via $source"
            } catch (e: KeylessSteam.NeedsLoginException) {
                _status.value = KeylessSteam.SIGN_IN_AGAIN
            } catch (e: Exception) {
                _status.value = "Sync failed: ${e.message}" + if (effectiveKey() == null) "\nTip: add your own Steam Web API key in the profile menu → Steam API key." else ""
            } finally { _syncing.value = false }
        }
    }

    fun signOut() { session.steamId = null; CookieManager.getInstance().removeAllCookies(null); _profile.value = null; _games.value = emptyList() }
}
