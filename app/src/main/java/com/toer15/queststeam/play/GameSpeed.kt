package com.toer15.queststeam.play

import android.content.Context
import com.toer15.queststeam.launcher.GameLauncher
import com.toer15.queststeam.launcher.RuntimeCore

/**
 * How fast the Windows translation (Box64) runs games and Steam (Runtime settings → Game speed).
 *
 * Fast (default): Box64's own settings, which translate x86 code to much faster ARM code than
 * Winlator's "Intermediate" preset (native CPU flags, call/return shortcuts, fast float rounding),
 * plus Box64's translation cache on storage for games too, so a game's second start skips most of
 * the translating. Safe: the Intermediate preset the app used before, for a game that crashes with Fast.
 */
object GameSpeed {
    private const val PREF = "box64_speed"
    const val FAST = "fast"
    const val SAFE = "safe"

    fun selected(ctx: Context): String =
        ctx.getSharedPreferences("runtime", 0).getString(PREF, FAST)?.takeIf { it == FAST || it == SAFE } ?: FAST

    fun select(ctx: Context, mode: String) = ctx.getSharedPreferences("runtime", 0).edit().putString(PREF, mode).apply()

    /** What every Wine program gets on top of the runtime's environment in the selected mode. */
    fun env(ctx: Context, l: GameLauncher): Map<String, String> =
        if (selected(ctx) == FAST) RuntimeCore.BOX64_PRESET_KEYS.associateWith { RuntimeCore.UNSET } + SteamWindows.translationCacheEnv(l)
        else emptyMap()

    /** For the log. */
    fun describe(ctx: Context): String =
        if (selected(ctx) == FAST) "Fast (Box64's own settings, translation cache on storage)" else "Safe (Winlator's Intermediate preset)"
}
