package com.toer15.queststeam.play

/**
 * What one Play tap does.
 *
 * A library game is what Play shows: its Windows .exe on the game display. The loading screen
 * never says "Starting Steam" for a game. When Steam for Windows is installed, steam.exe still
 * runs with no window, because the game's Steamworks opens a pipe to that process (R.E.P.O.
 * exits with NoSteamClient otherwise). Opening Steam is the only path that shows Steam's window.
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

    /**
     * A library game fills the display as soon as its process is started.
     * Opening Steam itself keeps the Steam loading screen.
     */
    fun showImmediately(openingSteam: Boolean) = !openingSteam

    /**
     * Steam's client process has to be running for this game, with no Steam window.
     * The loading line stays [launching], not "Starting Steam".
     */
    fun clientBesideGame(openingSteam: Boolean, steamInstalled: Boolean) = !openingSteam && steamInstalled

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
