package com.toer15.queststeam

import android.content.Context
import android.content.Intent
import com.toer15.queststeam.download.DownloadManager
import com.toer15.queststeam.download.GameState
import com.toer15.queststeam.steam.OwnedGame
import com.toer15.queststeam.steam.PlayerSummary
import com.toer15.queststeam.steam.SessionStore
import com.toer15.queststeam.store.StoreItem
import com.toer15.queststeam.store.StorePrice
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Debug-only sample library so the real UI can be captured without a Steam account.
 * Armed only by the `demo` intent extra. Release builds use the stub that never arms.
 *
 * Cover art is copied out of debug assets into the app cache on prepare. The image loader
 * reads those files, so a capture does not wait on the Steam CDN.
 */
object DemoShots {
    var armed: Boolean = false
        private set

    /** Deep link: home, filters, games, browse, card, store. */
    var screen: String = ""
        private set

    /** App id whose card opens already flipped, for the card screen. */
    var flipAppId: Int = 0
        private set

    private const val AVATAR = "https://avatars.steamstatic.com/fef49e7fa7e1997310d705b2a6158ff8dc1cdfeb_full.jpg"
    private var artDir: File? = null

    fun prepare(ctx: Context, intent: Intent): Boolean {
        armed = intent.getBooleanExtra("demo", false)
        if (!armed) return false
        screen = intent.getStringExtra("screen").orEmpty().lowercase()
        flipAppId = intent.getIntExtra("flip", 0)
        unpackArt(ctx)
        SessionStore(ctx).steamId = "76561197960287930"
        val games = sample()
        val a = JSONArray()
        games.forEach { g ->
            a.put(JSONObject().put("id", g.appId).put("n", g.name).put("p", g.playtimeMinutes).put("vr", g.isVr).put("g", JSONArray(g.genres)).put("s", g.sizeBytes))
        }
        File(ctx.filesDir, "library.json").writeText(a.toString())
        // One game is really installed on disk so the Installed label comes from the download manager.
        File(DownloadManager.get(ctx).dirFor(546560), ".qss-installed").apply { parentFile?.mkdirs(); writeText("1") }
        File(ctx.cacheDir, "demo-launch.txt").writeText("screen=$screen flip=$flipAppId")
        return true
    }

    /** Local header the image loader should use, or null when this id was not bundled. */
    fun artFile(appId: Int): File? {
        if (!armed) return null
        val f = artDir?.let { File(it, "$appId.jpg") } ?: return null
        return f.takeIf { it.isFile && it.length() > 512 }
    }

    fun profile(): PlayerSummary {
        val avatar = artDir?.let { File(it, "avatar.jpg") }?.takeIf { it.isFile && it.length() > 200 }?.toURI()?.toString() ?: AVATAR
        return PlayerSummary("76561197960287930", "Player", avatar, "https://steamcommunity.com/profiles/76561197960287930")
    }

    fun storeItems(): List<StoreItem> = listOf(
        StoreItem(730, "Counter-Strike 2"),
        StoreItem(570, "Dota 2"),
        StoreItem(1091500, "Cyberpunk 2077"),
        StoreItem(1245620, "ELDEN RING"),
        StoreItem(1174180, "Red Dead Redemption 2"),
        StoreItem(1086940, "Baldur's Gate 3"),
        StoreItem(292030, "The Witcher 3: Wild Hunt"),
        StoreItem(271590, "Grand Theft Auto V"),
        StoreItem(546560, "Half-Life: Alyx"),
        StoreItem(620, "Portal 2"),
        StoreItem(1145360, "Hades"),
        StoreItem(892970, "Valheim"),
        StoreItem(275850, "No Man's Sky"),
        StoreItem(548430, "Deep Rock Galactic"),
        StoreItem(814380, "Sekiro: Shadows Die Twice"),
        StoreItem(1599340, "Lost Ark"),
        StoreItem(413150, "Stardew Valley"),
        StoreItem(620980, "Beat Saber"),
        StoreItem(629730, "Blade & Sorcery"),
        StoreItem(367520, "Hollow Knight"),
    )

    fun storePrices(): Map<Int, StorePrice> = mapOf(
        730 to StorePrice("Free", free = true),
        570 to StorePrice("Free", free = true),
        1091500 to StorePrice("$29.99", "$59.99", 50),
        1245620 to StorePrice("$41.99", "$59.99", 30),
        1174180 to StorePrice("$23.99", "$59.99", 60),
        1086940 to StorePrice("$59.99"),
        292030 to StorePrice("$9.99", "$39.99", 75),
        271590 to StorePrice("$14.99", "$29.99", 50),
        546560 to StorePrice("$39.99"),
        620 to StorePrice("$1.99", "$9.99", 80),
        1145360 to StorePrice("$12.49", "$24.99", 50),
        892970 to StorePrice("$19.99"),
        275850 to StorePrice("$29.99", "$59.99", 50),
        548430 to StorePrice("$14.99", "$29.99", 50),
        814380 to StorePrice("$59.99"),
        1599340 to StorePrice("Free", free = true),
        413150 to StorePrice("$14.99"),
        620980 to StorePrice("$29.99"),
        629730 to StorePrice("$19.99"),
        367520 to StorePrice("$7.49", "$14.99", 50),
    )

    private fun unpackArt(ctx: Context) {
        val dir = File(ctx.cacheDir, "demo-art").apply { mkdirs() }
        artDir = dir
        val names = ctx.assets.list("demo-art").orEmpty()
        for (name in names) {
            val out = File(dir, name)
            if (out.isFile && out.length() > 512) continue
            ctx.assets.open("demo-art/$name").use { input -> out.outputStream().use { input.copyTo(it) } }
        }
    }

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
