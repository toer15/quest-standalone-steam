package com.toer15.queststeam.play

/**
 * What one Play tap does.
 *
 * Steam itself starts a game with `steam.exe -silent -applaunch <appid>`, then that game's own
 * arguments. Play uses that same command when Steam isn't running yet, so the game opens the way it
 * does from Steam, and that Steam stays for the next game. With Steam already running (started for an
 * earlier game, or when the app opened), the game starts next to it at once: Steam doesn't start again.
 * The screen says Launching that game. Steam's window is not shown. Opening Steam is the only path
 * that shows Steam.
 */
object DirectLaunch {
    enum class SteamRole {
        /** This tap starts the game executable itself (Steam for Windows is not installed). */
        NONE,
        /** Steam's own `-applaunch` for this app id. The game opens. Steam's window does not. */
        APPLAUNCH,
        /** Steam is already running: the game starts next to it, and Steam doesn't start a second time. */
        BESIDE_STEAM,
        /** The user asked to open Steam itself. */
        SHOW_STEAM,
    }

    data class Plan(
        val steam: SteamRole,
        /** Loading-screen line: [PREPARING], [launching], or Steam only when opening Steam itself. */
        val step: String,
        /** The loading screen never waits on the Steam client: the game's screen comes up at once. */
        val waitForSteamSignIn: Boolean,
    )

    const val PREPARING = "Preparing runtime…"
    const val RUNNING = "Running"

    fun launching(title: String) = "Launching $title…"
    fun launching(title: String, seconds: Long) = "Launching $title… ${seconds}s"

    /** What a game whose screen is up waits for before it runs. */
    enum class Wait {
        /** Nothing but the game itself loading. */
        NONE,
        /** Steam is still signing in (games need it signed in, like on a PC). */
        STEAM_SIGNING_IN,
        /** Steam has been asked to start the game (applaunch) and hasn't yet. */
        STEAM_STARTING_GAME,
    }

    /**
     * The line at the top of the game screen until the game draws: "Launching R.E.P.O.… 12s · Steam is signing in
     * (93s last time)". [lastSignInS]: how long Steam took to sign in last time, if known.
     */
    fun waitingNote(title: String, seconds: Long, wait: Wait, lastSignInS: Long? = null): String = launching(title, seconds) + when (wait) {
        Wait.NONE -> ""
        Wait.STEAM_SIGNING_IN -> " · Steam is signing in" + (lastSignInS?.let { " (${it}s last time)" } ?: "")
        Wait.STEAM_STARTING_GAME -> " · Steam is starting it"
    }

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

    /** [steamRunning]: Steam's session is up already (its flat games start in it). */
    fun plan(
        title: String,
        openingSteam: Boolean,
        runtimeInstalled: Boolean,
        gameFileExists: Boolean,
        appId: Int = 0,
        steamInstalled: Boolean = false,
        steamRunning: Boolean = false,
    ): Plan {
        if (openingSteam) return Plan(SteamRole.SHOW_STEAM, "Starting Steam…", waitForSteamSignIn = false)
        if (!runtimeInstalled) return Plan(SteamRole.NONE, PREPARING, waitForSteamSignIn = false)
        if (!gameFileExists) return Plan(SteamRole.NONE, launching(title), waitForSteamSignIn = false)
        if (steamRunning) return Plan(SteamRole.BESIDE_STEAM, launching(title), waitForSteamSignIn = false)
        if (usesApplaunch(false, appId, steamInstalled)) return Plan(SteamRole.APPLAUNCH, launching(title), waitForSteamSignIn = false)
        return Plan(SteamRole.NONE, launching(title), waitForSteamSignIn = false)
    }
}
