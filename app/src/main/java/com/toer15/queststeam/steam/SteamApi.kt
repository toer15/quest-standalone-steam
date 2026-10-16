package com.toer15.queststeam.steam

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL

/** Stable for Compose: equality covers every field, and lists are re-emitted after changes. */
@androidx.compose.runtime.Stable
data class OwnedGame(
    val appId: Int,
    val name: String,
    val playtimeMinutes: Int,
    var isVr: Boolean = false,
    var genres: List<String> = emptyList(),
    /** Install size from Steam (bytes), 0 until known. */
    var sizeBytes: Long = 0L,
) {
    val headerUrl get() = "https://cdn.cloudflare.steamstatic.com/steam/apps/$appId/header.jpg"
    val capsuleUrl get() = "https://cdn.cloudflare.steamstatic.com/steam/apps/$appId/library_600x900.jpg"
}

data class PlayerSummary(val steamId: String, val personaName: String, val avatarUrl: String, val profileUrl: String)

object SteamApi {
    // Store category ids that indicate VR support (heuristic; not officially documented).
    private val VR_CATEGORIES = setOf(31, 53, 54)

    suspend fun getOwnedGames(apiKey: String, steamId: String): List<OwnedGame> = withContext(Dispatchers.IO) {
        val url = "https://api.steampowered.com/IPlayerService/GetOwnedGames/v1/" +
            "?key=$apiKey&steamid=$steamId&include_appinfo=1&include_played_free_games=1&format=json"
        val games = JSONObject(URL(url).readText()).getJSONObject("response").optJSONArray("games")
            ?: return@withContext emptyList()
        (0 until games.length()).map { i ->
            val g = games.getJSONObject(i)
            OwnedGame(g.getInt("appid"), g.optString("name", "App ${g.getInt("appid")}"), g.optInt("playtime_forever"))
        }
    }

    suspend fun getPlayerSummary(apiKey: String, steamId: String): PlayerSummary? = withContext(Dispatchers.IO) {
        runCatching {
            val url = "https://api.steampowered.com/ISteamUser/GetPlayerSummaries/v2/?key=$apiKey&steamids=$steamId"
            val p = JSONObject(URL(url).readText()).getJSONObject("response").getJSONArray("players").getJSONObject(0)
            PlayerSummary(steamId, p.optString("personaname"), p.optString("avatarfull"), p.optString("profileurl"))
        }.getOrNull()
    }

    /**
     * Fills isVr + genres from the public store appdetails endpoint (rate limited ~200 req/5 min),
     * cached in SharedPreferences so later syncs are fast.
     */
    suspend fun enrich(ctx: Context, games: List<OwnedGame>, onEach: () -> Unit = {}) = coroutineScope {
        val cache = ctx.getSharedPreferences("appdetails", Context.MODE_PRIVATE)
        val limit = Semaphore(4)
        games.map { g ->
            async(Dispatchers.IO) {
                val cached = cache.getString(g.appId.toString(), null)
                val json = cached ?: limit.withPermit {
                    runCatching {
                        val root = JSONObject(URL("https://store.steampowered.com/api/appdetails?appids=${g.appId}&filters=categories,genres").readText())
                        val data = root.getJSONObject(g.appId.toString()).optJSONObject("data") ?: JSONObject()
                        val slim = JSONObject()
                            .put("cats", JSONArray((data.optJSONArray("categories")?.let { a -> (0 until a.length()).map { a.getJSONObject(it).getInt("id") } } ?: emptyList<Int>())))
                            .put("genres", JSONArray((data.optJSONArray("genres")?.let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("description") } } ?: emptyList<String>())))
                            .toString()
                        cache.edit().putString(g.appId.toString(), slim).apply(); slim
                    }.getOrNull()
                }
                if (json != null) {
                    val o = JSONObject(json)
                    val cats = o.getJSONArray("cats"); val gen = o.getJSONArray("genres")
                    g.isVr = (0 until cats.length()).any { cats.getInt(it) in VR_CATEGORIES }
                    g.genres = (0 until gen.length()).map { gen.getString(it) }
                }
                onEach()
            }
        }.awaitAll()
    }
}
