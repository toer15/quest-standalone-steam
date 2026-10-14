package com.toer15.queststeam.steam

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Keyless Steam access, so the public app needs no built-in Web API key.
 * The player's own steamcommunity.com session (OpenID WebView cookies) is sent
 * with profile and games requests, so a private profile still loads for its owner.
 * A Steam CM / JavaSteam login is not used: that path needs a password and Steam Guard.
 */
object KeylessSteam {
    const val UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    const val SIGN_IN_AGAIN = "Sign out and sign in again"
    const val GAMES_PAGE = "https://steamcommunity.com/my/games/?tab=all"

    class NeedsLoginException(msg: String) : Exception(msg)

    data class Profile(val summary: PlayerSummary, val privacyState: String)

    /** True when the WebView cookie jar has a steamcommunity.com login session. */
    fun hasSession(cookie: String?) = cookie?.contains("steamLoginSecure") == true

    fun get(url: String, cookie: String? = null): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        c.instanceFollowRedirects = false
        c.setRequestProperty("User-Agent", UA)
        c.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        if (!cookie.isNullOrBlank()) c.setRequestProperty("Cookie", cookie)
        c.connectTimeout = 20_000; c.readTimeout = 30_000
        val code = c.responseCode
        val body = runCatching { (if (code >= 400) c.errorStream else c.inputStream)?.bufferedReader()?.readText() }.getOrNull() ?: ""
        return code to body
    }

    /** Profile XML. Pass the sign-in cookies so the owner can read a private profile. */
    fun profile(steamId: String, cookie: String? = null): Profile? {
        val (code, xml) = get("https://steamcommunity.com/profiles/$steamId/?xml=1", cookie)
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
        if (!hasSession(cookie)) throw NeedsLoginException(SIGN_IN_AGAIN)
        val (code, html) = get("https://steamcommunity.com/profiles/$steamId/games/?tab=all", cookie)
        return interpretGamesResponse(cookie, code, html)
    }

    /**
     * A signed-in owner can read their own games page even when the profile is private.
     * A missing session, a login redirect, or the visitor private-profile wall means the
     * cookies were missing or rejected.
     */
    fun interpretGamesResponse(cookie: String?, code: Int, html: String): List<OwnedGame> {
        if (hasSession(cookie) && code == 200) parseGamesPage(html)?.let { return it }
        if (!hasSession(cookie) || code in 300..399 || html.contains("/login/?redir=") || html.contains("profile_private_info")) {
            throw NeedsLoginException(SIGN_IN_AGAIN)
        }
        if (code != 200) throw Exception("Steam games page returned HTTP $code")
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
}
