package com.toer15.queststeam.play

import android.content.Context
import java.io.File

/**
 * The last game played next to Steam, for "Get my last game ready too" (Runtime settings → Steam for
 * Windows): when Steam gets ready as the app opens, that game starts too, muted and off screen, and
 * waits paused at its menu, so Play shows it in a few seconds (see GameRunner.getLastGameReady).
 */
object LastGame {
    data class Game(val exe: File, val args: String, val title: String, val appId: Int)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("runtime", 0)

    fun enabled(ctx: Context) = prefs(ctx).getBoolean("ready_last_game", true)
    fun setEnabled(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("ready_last_game", on).apply()

    fun get(ctx: Context): Game? {
        val p = prefs(ctx)
        val exe = p.getString("last_game_exe", null)?.let(::File)?.takeIf { it.isFile } ?: return null
        return Game(exe, p.getString("last_game_args", null).orEmpty(), p.getString("last_game_title", null) ?: exe.nameWithoutExtension, p.getInt("last_game_appid", 0))
    }

    fun set(ctx: Context, exe: File, args: String, title: String, appId: Int) = prefs(ctx).edit()
        .putString("last_game_exe", exe.path).putString("last_game_args", args).putString("last_game_title", title).putInt("last_game_appid", appId).apply()

    /** It closed while starting: it isn't started in the background again until you play it next. */
    fun clear(ctx: Context) = prefs(ctx).edit().remove("last_game_exe").remove("last_game_args").remove("last_game_title").remove("last_game_appid").apply()
}
