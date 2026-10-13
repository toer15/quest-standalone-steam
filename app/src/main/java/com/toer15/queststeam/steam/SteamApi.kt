package com.toer15.queststeam.steam

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL

data class OwnedGame(
    val appId: Int,
    val name: String,
    val playtimeMinutes: Int,
    var isVr: Boolean = false,
)

object SteamApi {
    // Store category ids that indicate VR support (heuristic; Steam doesn't document these officially).
    private val VR_CATEGORIES = setOf(31, 53, 54) // VR Support, VR Supported, VR Only

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

    /** Marks VR titles using the public store appdetails endpoint (rate limited; ~200 req / 5 min). */
    suspend fun tagVrGames(games: List<OwnedGame>) = coroutineScope {
        val limit = Semaphore(4)
        games.map { g ->
            async(Dispatchers.IO) {
                limit.withPermit {
                    runCatching {
                        val json = JSONObject(URL("https://store.steampowered.com/api/appdetails?appids=${g.appId}&filters=categories").readText())
                        val cats = json.getJSONObject(g.appId.toString()).optJSONObject("data")?.optJSONArray("categories")
                        if (cats != null) g.isVr = (0 until cats.length()).any { cats.getJSONObject(it).getInt("id") in VR_CATEGORIES }
                    }
                }
            }
        }.awaitAll()
    }
}
