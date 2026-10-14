package com.toer15.queststeam.store

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/** A game in the Steam store. */
data class StoreItem(val appId: Int, val name: String)

/** Price as Steam shows it in the player's country ("$29.99", "-50%", "Free"). */
data class StorePrice(val final: String, val initial: String? = null, val discountPercent: Int = 0, val free: Boolean = false)

data class StoreDetails(val appId: Int, val name: String, val description: String, val headerImage: String?, val price: StorePrice?, val comingSoon: Boolean)

/**
 * Steam store over its public JSON endpoints (no key needed): browse/search every game page by
 * page, prices in the player's currency, and details. Buying happens on Steam's own checkout page.
 */
object StoreApi {
    enum class Section(val label: String, val query: String) {
        TopSellers("Top sellers", "filter=topsellers"),
        Trending("New & trending", "filter=popularnew"),
        Specials("Specials", "specials=1"),
        VR("VR games", "tags=21978"),
        Free("Free to play", "maxprice=free"),
        All("All games", ""),
    }

    const val PAGE = 50
    private val country get() = Locale.getDefault().country.ifBlank { "US" }.lowercase()

    /** One page of games: a store section, or every game matching [term] when it isn't blank. */
    suspend fun page(section: Section, term: String, start: Int): List<StoreItem> = withContext(Dispatchers.IO) {
        val q = buildString {
            append("https://store.steampowered.com/search/results/?json=1&infinite=1&category1=998")
            append("&start=$start&count=$PAGE&cc=$country&l=english")
            if (term.isNotBlank()) append("&term=").append(URLEncoder.encode(term.trim(), "UTF-8"))
            else if (section.query.isNotBlank()) append('&').append(section.query)
        }
        val items = runCatching { parseSearch(get(q)) }.getOrDefault(emptyList())
        // Fallback for search: the store's suggestion API (first results only).
        if (items.isEmpty() && term.isNotBlank() && start == 0)
            runCatching { parseStoreSearch(get("https://store.steampowered.com/api/storesearch/?term=${URLEncoder.encode(term.trim(), "UTF-8")}&l=english&cc=$country")) }.getOrDefault(emptyList())
        else items
    }

    /** Prices for many games at once (Steam allows a list of appids with filters=price_overview). */
    suspend fun prices(appIds: List<Int>): Map<Int, StorePrice> = withContext(Dispatchers.IO) {
        if (appIds.isEmpty()) return@withContext emptyMap()
        val out = HashMap<Int, StorePrice>()
        for (chunk in appIds.chunked(50)) {
            runCatching { out += parsePrices(get("https://store.steampowered.com/api/appdetails?appids=${chunk.joinToString(",")}&filters=price_overview&cc=$country")) }
        }
        out
    }

    suspend fun details(appId: Int): StoreDetails? = withContext(Dispatchers.IO) {
        runCatching {
            val o = JSONObject(get("https://store.steampowered.com/api/appdetails?appids=$appId&cc=$country&l=english"))
                .optJSONObject(appId.toString())?.optJSONObject("data") ?: return@runCatching null
            StoreDetails(
                appId, o.optString("name"),
                o.optString("short_description").replace(Regex("<[^>]+>"), "").trim(),
                o.optString("header_image").takeIf { it.startsWith("http") },
                if (o.optBoolean("is_free")) StorePrice("Free", free = true) else o.optJSONObject("price_overview")?.let(::price),
                o.optJSONObject("release_date")?.optBoolean("coming_soon") == true,
            )
        }.getOrNull()
    }

    fun storeUrl(appId: Int) = "https://store.steampowered.com/app/$appId/"

    // region parsing (unit-tested)

    private val APP_IN_URL = Regex("/apps/(\\d+)/")

    /** search/results?json=1: {"items":[{"name":"…","logo":"…/apps/620980/capsule_sm_120.jpg"}]} (bundles have no app id). */
    fun parseSearch(json: String): List<StoreItem> {
        val items = JSONObject(json).optJSONArray("items") ?: return emptyList()
        return (0 until items.length()).mapNotNull { i ->
            val o = items.getJSONObject(i)
            val id = APP_IN_URL.find(o.optString("logo"))?.groupValues?.get(1)?.toIntOrNull() ?: return@mapNotNull null
            StoreItem(id, o.optString("name"))
        }.distinctBy { it.appId }
    }

    /** api/storesearch: {"items":[{"type":"app","id":620980,"name":"Beat Saber"}]} */
    fun parseStoreSearch(json: String): List<StoreItem> {
        val items = JSONObject(json).optJSONArray("items") ?: return emptyList()
        return (0 until items.length()).mapNotNull { i ->
            val o = items.getJSONObject(i)
            if (o.optString("type", "app") != "app") return@mapNotNull null
            StoreItem(o.optInt("id").takeIf { it > 0 } ?: return@mapNotNull null, o.optString("name"))
        }
    }

    /** api/appdetails?filters=price_overview: free games come back with "data": []. */
    fun parsePrices(json: String): Map<Int, StorePrice> {
        val root = JSONObject(json)
        val out = HashMap<Int, StorePrice>()
        for (key in root.keys()) {
            val id = key.toIntOrNull() ?: continue
            val entry = root.optJSONObject(key) ?: continue
            if (!entry.optBoolean("success")) continue
            val po = entry.optJSONObject("data")?.optJSONObject("price_overview")
            out[id] = po?.let(::price) ?: if (entry.opt("data") is JSONArray || entry.optJSONObject("data") != null) StorePrice("Free", free = true) else continue
        }
        return out
    }

    private fun price(po: JSONObject): StorePrice {
        val d = po.optInt("discount_percent")
        return StorePrice(
            final = po.optString("final_formatted").ifBlank { "%.2f".format(po.optInt("final") / 100.0) },
            initial = po.optString("initial_formatted").takeIf { d > 0 && it.isNotBlank() },
            discountPercent = d,
        )
    }

    // endregion

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000; c.readTimeout = 20_000
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 12; Quest 3) QuestSteam")
        c.setRequestProperty("Accept-Language", "en")
        return c.inputStream.bufferedReader().use { it.readText() }
    }
}
