package com.toer15.queststeam.steam

import android.content.Context

/** Stores only the public SteamID64. No passwords or tokens are ever stored. */
class SessionStore(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("session", Context.MODE_PRIVATE)
    var steamId: String?
        get() = prefs.getString("steam_id", null)
        set(v) { prefs.edit().putString("steam_id", v).apply() }
}
