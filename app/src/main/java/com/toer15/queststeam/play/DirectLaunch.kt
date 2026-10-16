package com.toer15.queststeam.play

/**
 * What one Play tap does.
 *
 * Steam itself starts a game with `steam.exe -silent -applaunch <appid>`, then that game's own
 * arguments. Play uses that same command, so the game opens the way it does from Steam. The screen
 * says Launching that game. Steam's window is not shown. Opening Steam is the only path that
 * shows Steam.
 */
object DirectLaunch {
    enum class SteamRole {
        /** This tap starts the game executable itself (Steam for Windows is not installed). */
        NONE,
        /** Steam's own `-applaunch` for this app id. The game opens. Steam's window does not. */
        APPLAUNCH,
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

    /** Play on a library game shows that game, not Steam's window. */
    fun startsOnlyTheGame(openingSteam: Boolean) = !openingSteam

    /** Steam's own Play command: `-silent -applaunch <appid>`, then the game's arguments. */
    fun usesApplaunch(openingSteam: Boolean, appId: Int, steamInstalled: Boolean) =
        !openingSteam && steamInstalled && appId > 0

    /**
     * The command Steam runs for Play. Flags that belong to steam.exe come first.
     * Everything after the app id is handed to the game, the same way Steam does it.
     */
    fun applaunchArgs(appId: Int, gameArgs: List<String>, steamFlags: List<String> = emptyList()): List<String> =
        listOf("-silent") + steamFlags + listOf("-applaunch", appId.toString()) + gameArgs

    fun plan(
        title: String,
        openingSteam: Boolean,
        runtimeInstalled: Boolean,
        gameFileExists: Boolean,
        appId: Int = 0,
        steamInstalled: Boolean = false,
    ): Plan {
        if (openingSteam) return Plan(SteamRole.SHOW_STEAM, "Starting Steam…", waitForSteamSignIn = false)
        if (!runtimeInstalled) return Plan(SteamRole.NONE, PREPARING, waitForSteamSignIn = false)
        if (!gameFileExists) return Plan(SteamRole.NONE, launching(title), waitForSteamSignIn = false)
        if (usesApplaunch(false, appId, steamInstalled)) return Plan(SteamRole.APPLAUNCH, launching(title), waitForSteamSignIn = false)
        return Plan(SteamRole.NONE, launching(title), waitForSteamSignIn = false)
    }
}
