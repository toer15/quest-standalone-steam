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
import com.toer15.queststeam.steam.SteamAccount
import com.toer15.queststeam.steamclient.SteamLoginException
import android.webkit.CookieManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class LibraryViewModel(app: Application) : AndroidViewModel(app) {
    val downloads = DownloadManager.get(app)
    private val session = SessionStore(app)
    private val steam = SteamAccount.get(app).session
    private val cacheFile = File(app.filesDir, "library.json")

    private val _games = MutableStateFlow<List<OwnedGame>>(emptyList())
    val games: StateFlow<List<OwnedGame>> = _games
    private val _profile = MutableStateFlow<PlayerSummary?>(null)
    val profile: StateFlow<PlayerSummary?> = _profile
    private val _status = MutableStateFlow("")
    val status: StateFlow<String> = _status
    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing

    val steamId get() = session.steamId
    /** True when signed in through the Steam client (needed for downloads), not just the website. */
    val steamClientSignedIn get() = steam.isSignedIn

    init {
        loadCache()
        sync()
    }

    private fun loadCache() = runCatching {
        if (!cacheFile.exists()) return@runCatching
        val a = JSONArray(cacheFile.readText())
        val list = (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            OwnedGame(o.getInt("id"), o.getString("n"), o.optInt("p"), o.optBoolean("vr"),
                o.optJSONArray("g")?.let { g -> (0 until g.length()).map(g::getString) } ?: emptyList())
        }
        _games.value = list
        downloads.refresh(list.map { it.appId })
    }

    private fun saveCache(list: List<OwnedGame>) = runCatching {
        val a = JSONArray()
        list.forEach { g ->
            a.put(JSONObject().put("id", g.appId).put("n", g.name).put("p", g.playtimeMinutes).put("vr", g.isVr).put("g", JSONArray(g.genres)))
        }
        cacheFile.writeText(a.toString())
    }

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
                _status.value = "Syncing your Steam games…"
                val key = effectiveKey()
                val cookie = CookieManager.getInstance().getCookie("https://steamcommunity.com")
                val prof = withContext(Dispatchers.IO) { runCatching { KeylessSteam.profile(id, cookie) }.getOrNull() }
                if (prof != null) _profile.value = prof.summary
                else if (key != null) _profile.value = SteamApi.getPlayerSummary(key, id)
                var source: String
                val owned = if (steam.isSignedIn) {
                    // Straight from Steam's servers over the signed-in client connection.
                    source = "Steam"
                    val apps = steam.ownedGames()
                    if (_profile.value == null) steam.personaName.value?.let { _profile.value = PlayerSummary(id, it, "", "") }
                    apps.map { OwnedGame(it.appId, it.name, it.playtimeMinutes) }
                } else {
                    // Website sign-in only: read the owner's games page with the sign-in cookies.
                    source = "Steam website"
                    try {
                        withContext(Dispatchers.IO) { KeylessSteam.ownedGames(id, cookie) }
                    } catch (e: Exception) {
                        if (key == null) throw e
                        source = "Steam Web API"
                        SteamApi.getOwnedGames(key, id)
                    }
                }.sortedBy { it.name.lowercase() }
                // Keep VR flags/genres already known from the cache until enrichment refreshes them.
                val known = _games.value.associateBy { it.appId }
                owned.forEach { g -> known[g.appId]?.let { k -> g.isVr = k.isVr; g.genres = k.genres } }
                if (owned.isEmpty()) {
                    _games.value = emptyList()
                    _status.value = "Steam returned no games."
                    return@launch
                }
                _games.value = owned
                downloads.refresh(owned.map { it.appId })
                var n = 0
                SteamApi.enrich(getApplication(), owned) {
                    n++; if (n % 50 == 0) { _status.value = "Loading genres $n/${owned.size}"; _games.value = owned.toList() }
                }
                _games.value = owned.toList()
                saveCache(owned)
                _status.value = "${owned.size} games · ${owned.count { it.isVr }} VR · synced from $source" +
                    if (!steam.isSignedIn) "\nTo download games, tap your profile picture → Sign in to Steam." else ""
            } catch (e: SteamLoginException) {
                _status.value = if (e.needsNewLogin) "Your Steam sign-in expired. Tap your profile picture → Sign in to Steam." else "Sync failed: ${e.message}"
            } catch (e: KeylessSteam.NeedsLoginException) {
                _status.value = KeylessSteam.SIGN_IN_AGAIN
            } catch (e: Exception) {
                _status.value = "Sync failed: ${e.message}" + if (effectiveKey() == null) "\nTip: add your own Steam Web API key in the profile menu → Steam API key." else ""
            } finally { _syncing.value = false }
        }
    }

    fun signOut() {
        steam.signOut()
        session.steamId = null
        CookieManager.getInstance().removeAllCookies(null)
        cacheFile.delete()
        _profile.value = null
        _games.value = emptyList()
    }
}
