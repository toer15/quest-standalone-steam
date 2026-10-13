package com.toer15.queststeam.steam

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Steam OpenID 2.0 login. https://steamcommunity.com/dev */
object SteamOpenId {
    private const val ENDPOINT = "https://steamcommunity.com/openid/login"
    /** Never actually loaded; the WebView intercepts it. */
    const val RETURN_TO = "https://queststeam.local/auth"
    private val ID_REGEX = Regex("^https://steamcommunity\\.com/openid/id/(\\d{17})$")

    fun loginUrl(): String = Uri.parse(ENDPOINT).buildUpon()
        .appendQueryParameter("openid.ns", "http://specs.openid.net/auth/2.0")
        .appendQueryParameter("openid.mode", "checkid_setup")
        .appendQueryParameter("openid.return_to", RETURN_TO)
        .appendQueryParameter("openid.realm", RETURN_TO)
        .appendQueryParameter("openid.identity", "http://specs.openid.net/auth/2.0/identifier_select")
        .appendQueryParameter("openid.claimed_id", "http://specs.openid.net/auth/2.0/identifier_select")
        .build().toString()

    /** Verifies the assertion with Steam (check_authentication) and returns SteamID64, or null. */
    suspend fun verify(callback: Uri): String? = withContext(Dispatchers.IO) {
        val claimed = callback.getQueryParameter("openid.claimed_id") ?: return@withContext null
        val steamId = ID_REGEX.find(claimed)?.groupValues?.get(1) ?: return@withContext null
        val body = callback.queryParameterNames.joinToString("&") { k ->
            val v = if (k == "openid.mode") "check_authentication" else callback.getQueryParameter(k) ?: ""
            URLEncoder.encode(k, "UTF-8") + "=" + URLEncoder.encode(v, "UTF-8")
        }
        val conn = URL(ENDPOINT).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        conn.outputStream.use { it.write(body.toByteArray()) }
        val resp = conn.inputStream.bufferedReader().readText()
        if (resp.lines().any { it.trim() == "is_valid:true" }) steamId else null
    }
}
