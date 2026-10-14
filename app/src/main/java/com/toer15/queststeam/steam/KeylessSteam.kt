package com.toer15.queststeam.steam

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Keyless Steam access, so the public app needs no built-in Web API key.
 *  - Profile (name, avatar, privacy): public `steamcommunity.com/profiles/<id>/?xml=1`. Works anonymously (verified).
 *  - Owned games: `steamcommunity.com/profiles/<id>/games/?tab=all` now redirects anonymous requests to login,
 *    so we send the steamcommunity.com cookies from the user's own Steam sign-in (the OpenID WebView).
 */
object KeylessSteam {
    const val UA = "Mozilla/5.0 (Linux; Android 12; Quest 3) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36"

    class PrivateProfileException(msg: String) : Exception(msg)
    class NeedsLoginException(msg: String) : Exception(msg)

    data class Profile(val summary: PlayerSummary, val privacyState: String)

    fun get(url: String, cookie: String? = null): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        c.instanceFollowRedirects = false
        c.setRequestProperty("User-Agent", UA)
        if (!cookie.isNullOrBlank()) c.setRequestProperty("Cookie", cookie)
        c.connectTimeout = 20_000; c.readTimeout = 30_000
        val code = c.responseCode
        val body = runCatching { (if (code >= 400) c.errorStream else c.inputStream)?.bufferedReader()?.readText() }.getOrNull() ?: ""
        return code to body
    }

    fun profile(steamId: String): Profile? {
        val (code, xml) = get("https://steamcommunity.com/profiles/$steamId/?xml=1")
        if (code != 200) return null
        return parseProfileXml(steamId, xml)
    }

    fun parseProfileXml(steamId: String, xml: String): Profile? {
        fun tag(t: String) = Regex("<$t>(?:<!\\[CDATA\\[)?(.*?)(?:]]>)?</$t>", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.get(1)?.trim()
        val name = tag("steamID") ?: return null
        return Profile(
            PlayerSummary(steamId, name, tag("avatarFull") ?: "", "https://steamcommunity.com/profiles/$steamId"),
            tag("privacyState") ?: "unknown",
        )
    }

    /** Owned games via the logged-in community games page. [cookie] = CookieManager cookies for steamcommunity.com. */
    fun ownedGames(steamId: String, cookie: String?): List<OwnedGame> {
        val (code, html) = get("https://steamcommunity.com/profiles/$steamId/games/?tab=all", cookie)
        if (code in 300..399 || html.contains("/login/?redir=")) throw NeedsLoginException("Steam needs you to be signed in to read your games list. Sign out and sign in again.")
        if (code != 200) throw Exception("Steam games page returned HTTP $code")
        val games = parseGamesPage(html)
        if (games != null) return games
        if (html.contains("profile_private_info") || html.contains("private", ignoreCase = true) && html.contains("gameslist").not())
            throw PrivateProfileException(PRIVATE_MSG)
        throw Exception("Couldn't read the games list from Steam's page (format may have changed).")
    }

    /** Parses either the current `data-profile-gameslist="{...}"` attribute or the legacy `var rgGames = [...]`. */
    fun parseGamesPage(html: String): List<OwnedGame>? {
        Regex("data-profile-gameslist=\"([^\"]*)\"").find(html)?.let { m ->
            val json = JSONObject(htmlUnescape(m.groupValues[1]))
            return toGames(json.optJSONArray("rgGames") ?: JSONArray())
        }
        Regex("var rgGames\\s*=\\s*(\\[.*?]);", RegexOption.DOT_MATCHES_ALL).find(html)?.let { m ->
            return toGames(JSONArray(m.groupValues[1]))
        }
        return null
    }

    private fun toGames(a: JSONArray) = (0 until a.length()).map { i ->
        val g = a.getJSONObject(i)
        val pt = g.opt("playtime_forever") ?: g.opt("hours_forever")
        val minutes = when (pt) {
            is Number -> if (g.has("playtime_forever")) pt.toInt() else (pt.toDouble() * 60).toInt()
            is String -> ((pt.replace(",", "").toDoubleOrNull() ?: 0.0) * if (g.has("playtime_forever")) 1 else 60).toInt()
            else -> 0
        }
        OwnedGame(g.getInt("appid"), g.optString("name", "App ${g.getInt("appid")}"), minutes)
    }

    fun htmlUnescape(s: String) = s.replace("&quot;", "\"").replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")

    const val PRIVATE_MSG = "Your Steam game details are private. In Steam: Profile → Edit Profile → Privacy Settings → set \"Game details\" to Public, then Sync."
}
