package com.toer15.queststeam

import android.content.Context
import android.content.Intent
import com.toer15.queststeam.download.DownloadManager
import com.toer15.queststeam.download.GameState
import com.toer15.queststeam.steam.OwnedGame
import com.toer15.queststeam.steam.PlayerSummary
import com.toer15.queststeam.steam.SessionStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Debug-only sample library so the real UI can be captured without a Steam account.
 * Armed only by the `demo` intent extra. Release builds use the stub that never arms.
 */
object DemoShots {
    var armed: Boolean = false

    private const val AVATAR = "https://avatars.steamstatic.com/fef49e7fa7e1997310d705b2a6158ff8dc1cdfeb_full.jpg"

    fun prepare(ctx: Context, intent: Intent): Boolean {
        armed = intent.getBooleanExtra("demo", false)
        if (!armed) return false
        SessionStore(ctx).steamId = "76561197960287930"
        val games = sample()
        val a = JSONArray()
        games.forEach { g ->
            a.put(JSONObject().put("id", g.appId).put("n", g.name).put("p", g.playtimeMinutes).put("vr", g.isVr).put("g", JSONArray(g.genres)).put("s", g.sizeBytes))
        }
        File(ctx.filesDir, "library.json").writeText(a.toString())
        // One game is really installed on disk so the Installed label comes from the download manager.
        File(DownloadManager.get(ctx).dirFor(546560), ".qss-installed").apply { parentFile?.mkdirs(); writeText("1") }
        return true
    }

    fun profile() = PlayerSummary("76561197960287930", "Player", AVATAR, "https://steamcommunity.com/profiles/76561197960287930")

    fun applyCardStates(downloads: DownloadManager) {
        downloads.demoState(1091500, GameState.Downloading(1_820_000_000L, 4_500_000_000L, 186, "Steam", null))
        downloads.demoState(367520, GameState.Failed("The connection dropped. Tap Retry download to continue."))
    }

    private fun sample() = listOf(
        OwnedGame(1174180, "Red Dead Redemption 2", 8420, false, listOf("Action", "Adventure"), 119_000_000_000L),
        OwnedGame(1091500, "Cyberpunk 2077", 6120, false, listOf("RPG", "Action"), 70_000_000_000L),
        OwnedGame(1245620, "ELDEN RING", 5340, false, listOf("RPG", "Action"), 49_000_000_000L),
        OwnedGame(546560, "Half-Life: Alyx", 1860, true, listOf("Action", "Adventure"), 67_000_000_000L),
        OwnedGame(1086940, "Baldur's Gate 3", 4210, false, listOf("RPG", "Adventure"), 122_000_000_000L),
        OwnedGame(620980, "Beat Saber", 2400, true, listOf("Indie", "Casual"), 1_200_000_000L),
        OwnedGame(1145360, "Hades", 1980, false, listOf("Action", "Indie"), 1_300_000_000L),
        OwnedGame(367520, "Hollow Knight", 1560, false, listOf("Action", "Indie"), 1_100_000_000L),
        OwnedGame(413150, "Stardew Valley", 3120, false, listOf("Indie", "Simulation"), 500_000_000L),
        OwnedGame(892970, "Valheim", 2740, false, listOf("Adventure", "Survival"), 1_000_000_000L),
        OwnedGame(620, "Portal 2", 980, false, listOf("Action", "Adventure"), 8_000_000_000L),
        OwnedGame(271590, "Grand Theft Auto V", 3600, false, listOf("Action", "Adventure"), 110_000_000_000L),
        OwnedGame(292030, "The Witcher 3: Wild Hunt", 4500, false, listOf("RPG"), 50_000_000_000L),
        OwnedGame(730, "Counter-Strike 2", 5100, false, listOf("Action"), 30_000_000_000L),
        OwnedGame(570, "Dota 2", 2200, false, listOf("Strategy"), 40_000_000_000L),
        OwnedGame(275850, "No Man's Sky", 890, false, listOf("Adventure"), 15_000_000_000L),
        OwnedGame(548430, "Deep Rock Galactic", 1320, false, listOf("Action"), 3_000_000_000L),
        OwnedGame(629730, "Blade & Sorcery", 640, true, listOf("Action", "Indie"), 2_000_000_000L),
    )
}
