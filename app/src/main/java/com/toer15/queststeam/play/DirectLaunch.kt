package com.toer15.queststeam.play

/**
 * What one Play tap does.
 *
 * A library game starts as itself: its Windows .exe under Box64/Wine. Play does not start
 * steam.exe, and the loading screen never says "Starting Steam" for a game. Opening Steam
 * (the separate Open Steam button) is the only path that starts the Steam client.
 */
object DirectLaunch {
    enum class SteamRole {
        /** This tap starts the game executable only. */
        NONE,
        /** The user asked to open Steam itself. */
        SHOW_STEAM,
    }

    data class Plan(
        val steam: SteamRole,
        /** Loading-screen line: [PREPARING], [launching], or Steam only when opening Steam itself. */
        val step: String,
        /** Games never block on the Steam client. */
        val waitForSteamSignIn: Boolean,
    )

    const val PREPARING = "Preparing runtime…"
    const val RUNNING = "Running"

    fun launching(title: String) = "Launching $title…"
    fun launching(title: String, seconds: Long) = "Launching $title… ${seconds}s"

    fun plan(
        title: String,
        openingSteam: Boolean,
        runtimeInstalled: Boolean,
        gameFileExists: Boolean,
    ): Plan {
        if (openingSteam) return Plan(SteamRole.SHOW_STEAM, "Starting Steam…", waitForSteamSignIn = false)
        if (!runtimeInstalled) return Plan(SteamRole.NONE, PREPARING, waitForSteamSignIn = false)
        if (!gameFileExists) return Plan(SteamRole.NONE, launching(title), waitForSteamSignIn = false)
        return Plan(SteamRole.NONE, launching(title), waitForSteamSignIn = false)
    }
}
