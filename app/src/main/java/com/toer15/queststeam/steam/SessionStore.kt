package com.toer15.queststeam.steam

import android.content.Context

/** Stores the public SteamID64 and an optional user-provided API key. No passwords are ever stored. */
class SessionStore(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("session", Context.MODE_PRIVATE)
    var steamId: String?
        get() = prefs.getString("steam_id", null)
        set(v) { prefs.edit().putString("steam_id", v).apply() }
    /** Optional personal Steam Web API key the player pasted in Settings (stored only on this device). */
    var apiKey: String?
        get() = prefs.getString("api_key", null)?.takeIf { it.isNotBlank() }
        set(v) { prefs.edit().putString("api_key", v?.trim()).apply() }
}
