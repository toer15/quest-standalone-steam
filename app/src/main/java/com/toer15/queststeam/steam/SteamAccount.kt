package com.toer15.queststeam.steam

import android.content.Context
import android.os.Build
import com.toer15.queststeam.BuildConfig
import com.toer15.queststeam.steamclient.LoginStore
import com.toer15.queststeam.steamclient.SavedLogin
import com.toer15.queststeam.steamclient.SteamDownloader
import com.toer15.queststeam.steamclient.SteamSession

/**
 * App-wide Steam client sign-in (JavaSteam). Keeps the account name + refresh token in private
 * storage, like the Steam client's "Remember me". The password is never stored.
 */
class SteamAccount private constructor(ctx: Context) {
    companion object {
        @Volatile private var inst: SteamAccount? = null
        fun get(ctx: Context) = inst ?: synchronized(this) { inst ?: SteamAccount(ctx.applicationContext).also { inst = it } }
    }

    private val prefs = ctx.getSharedPreferences("steam_client", Context.MODE_PRIVATE)
    private val sessionStore = SessionStore(ctx)

    private val store = object : LoginStore {
        override fun load(): SavedLogin? {
            val name = prefs.getString("account", null) ?: return null
            val token = prefs.getString("refresh_token", null) ?: return null
            return SavedLogin(name, token, prefs.getLong("steam_id", 0L))
        }

        override fun save(login: SavedLogin?) {
            if (login == null) { prefs.edit().clear().apply(); return }
            prefs.edit()
                .putString("account", login.accountName)
                .putString("refresh_token", login.refreshToken)
                .putLong("steam_id", login.steamId)
                .apply()
            // The rest of the app (profile picture, cache keys) keys off the SteamID64.
            if (login.steamId != 0L) sessionStore.steamId = login.steamId.toString()
        }
    }

    val session = SteamSession(store, deviceName = (if (BuildConfig.QUEST_BUILD) "Quest Steam" else "Quest Steam Phone") + " (${Build.MODEL})")
    val downloader = SteamDownloader(session)
}
