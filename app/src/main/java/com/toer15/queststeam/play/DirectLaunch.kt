package com.toer15.queststeam.play

/**
 * What one Play tap does.
 *
 * The user picked one library game. Play starts that game's Windows .exe and nothing else.
 * steam.exe is not started. The loading screen never says "Starting Steam" for a game.
 * Opening Steam (the separate Open Steam button) is the only path that starts steam.exe.
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

    /** Play on a library game starts that game's exe. It does not start steam.exe. */
    fun startsOnlyTheGame(openingSteam: Boolean) = !openingSteam

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
