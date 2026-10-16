package com.toer15.queststeam.play

/**
 * What one Play tap does.
 *
 * The chosen game is the program that fills the display. Steam, when a game needs the client
 * (Steamworks), starts silent beside it — `-silent`, and without its window on this screen — and
 * Play does not wait for Steam to sign in before the game process starts.
 */
object DirectLaunch {
    enum class SteamRole {
        /** No Steam client for this tap (not installed, turned off, or already running in the background). */
        NONE,
        /** Start Steam with no window, then the game immediately. */
        SILENT_BESIDE_GAME,
        /** The user asked to open Steam itself. */
        SHOW_STEAM,
    }

    data class Plan(
        val steam: SteamRole,
        /** Loading-screen line: [PREPARING], [launching], or Steam's own screen when opening Steam. */
        val step: String,
        /**
         * Always false for a game. The game executable starts at once; Steam may still be connecting.
         */
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
        steamInstalled: Boolean,
        startSteamWithGames: Boolean,
        steamAlreadyRunning: Boolean,
    ): Plan {
        if (openingSteam) return Plan(SteamRole.SHOW_STEAM, "Starting Steam…", waitForSteamSignIn = false)
        if (!runtimeInstalled) return Plan(SteamRole.NONE, PREPARING, waitForSteamSignIn = false)
        if (!gameFileExists) return Plan(SteamRole.NONE, launching(title), waitForSteamSignIn = false)
        val steam = when {
            // Already up: launch the game into that session. Don't bring Steam's window forward.
            steamAlreadyRunning -> SteamRole.NONE
            steamInstalled && startSteamWithGames -> SteamRole.SILENT_BESIDE_GAME
            else -> SteamRole.NONE
        }
        return Plan(steam, launching(title), waitForSteamSignIn = false)
    }
}
