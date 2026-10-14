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

    /** Key priority: player's own key from Settings, then a build-time key (personal builds only; CI has none). */
    private fun effectiveKey(): String? = session.apiKey ?: BuildConfig.STEAM_API_KEY.takeIf { it.isNotBlank() }

    fun sync() {
        val id = session.steamId ?: return
        if (_syncing.value) return
        _syncing.value = true
        viewModelScope.launch {
            try {
                _status.value = "Syncing Steam account…"
                val key = effectiveKey()
                val prof = withContext(Dispatchers.IO) { runCatching { KeylessSteam.profile(id) }.getOrNull() }
                _profile.value = prof?.summary ?: key?.let { SteamApi.getPlayerSummary(it, id) }
                if (prof != null && prof.privacyState != "public" && key == null) {
                    _status.value = "Your Steam profile is ${prof.privacyState}. " + KeylessSteam.PRIVATE_MSG; return@launch
                }
                // 1) keyless via the user's own Steam sign-in cookies, 2) Web API key fallback.
                var source = "Steam profile"
                val owned = try {
                    withContext(Dispatchers.IO) { KeylessSteam.ownedGames(id, CookieManager.getInstance().getCookie("https://steamcommunity.com")) }
                } catch (e: Exception) {
                    if (key == null) throw e
                    source = "Steam Web API"
                    SteamApi.getOwnedGames(key, id)
                }.sortedBy { it.name.lowercase() }
                if (owned.isEmpty()) { _status.value = "Steam returned no games. " + KeylessSteam.PRIVATE_MSG; return@launch }
                _games.value = owned
                downloads.refresh(owned.map { it.appId })
                var n = 0
                SteamApi.enrich(getApplication(), owned) {
                    n++; if (n % 10 == 0) { _status.value = "Loading genres $n/${owned.size}"; _games.value = owned.toList() }
                }
                _games.value = owned.toList()
                _status.value = "${owned.size} games · ${owned.count { it.isVr }} VR · via $source"
            } catch (e: KeylessSteam.PrivateProfileException) {
                _status.value = e.message ?: KeylessSteam.PRIVATE_MSG
            } catch (e: Exception) {
                _status.value = "Sync failed: ${e.message}" + if (effectiveKey() == null) "\nTip: add your own Steam Web API key in the profile menu → Steam API key." else ""
            } finally { _syncing.value = false }
        }
    }

    fun signOut() { session.steamId = null; CookieManager.getInstance().removeAllCookies(null); _profile.value = null; _games.value = emptyList() }
}
