package com.toer15.queststeam.play

/**
 * What one Play tap does.
 *
 * With Steam already running (started when the app opened, or for an earlier game) the game starts next to
 * it at once: Steam doesn't start again. With no Steam running, Steam starts first, in the background and
 * never shown, and the app starts the game next to it the moment it has signed in ([SteamRole.STEAM_FIRST]):
 * that Steam then stays for the next game. Steam can also be asked to start the game itself with
 * `steam.exe -silent -applaunch <appid>` ([SteamRole.APPLAUNCH], an option: on the Quest the game Steam
 * started crashed at once). Either way the game screen comes up right away and says what it waits for.
 * Opening Steam is the only path that shows Steam's window.
 */
object DirectLaunch {
    enum class SteamRole {
        /** This tap starts the game executable itself (Steam for Windows is not installed). */
        NONE,
        /** Steam's own `-applaunch` for this app id. The game opens. Steam's window does not. */
        APPLAUNCH,
        /** No Steam is running: it starts first (in the background), then the app starts the game next to it. */
        STEAM_FIRST,
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
     * What the game screen says in the middle until the game draws: "Launching R.E.P.O.… 34s · Steam is signing in
     * (34s of about 80s)". [lastSignInS]: how long Steam took to sign in last time, if known; [steamSeconds]: how long
     * this Steam has been starting.
     */
    fun waitingNote(title: String, seconds: Long, wait: Wait, lastSignInS: Long? = null, steamSeconds: Long? = null): String =
        launching(title, seconds) + when (wait) {
            Wait.NONE -> ""
            Wait.STEAM_SIGNING_IN -> " · Steam is signing in" + when {
                steamSeconds != null && lastSignInS != null -> " (${steamSeconds}s of about ${lastSignInS}s)"
                lastSignInS != null -> " (${lastSignInS}s last time)"
                steamSeconds != null -> " (${steamSeconds}s)"
                else -> ""
            }
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

    /**
     * [steamRunning]: Steam's session is up already (its flat games start in it). [steamStartsGame]: Steam is asked
     * to start the game itself when it isn't running (the experimental option); otherwise Steam starts first and
     * the app starts the game next to it.
     */
    fun plan(
        title: String,
        openingSteam: Boolean,
        runtimeInstalled: Boolean,
        gameFileExists: Boolean,
        appId: Int = 0,
        steamInstalled: Boolean = false,
        steamRunning: Boolean = false,
        steamStartsGame: Boolean = true,
    ): Plan {
        if (openingSteam) return Plan(SteamRole.SHOW_STEAM, "Starting Steam…", waitForSteamSignIn = false)
        if (!runtimeInstalled) return Plan(SteamRole.NONE, PREPARING, waitForSteamSignIn = false)
        if (!gameFileExists) return Plan(SteamRole.NONE, launching(title), waitForSteamSignIn = false)
        if (steamRunning) return Plan(SteamRole.BESIDE_STEAM, launching(title), waitForSteamSignIn = false)
        if (usesApplaunch(false, appId, steamInstalled)) {
            return Plan(if (steamStartsGame) SteamRole.APPLAUNCH else SteamRole.STEAM_FIRST, launching(title), waitForSteamSignIn = false)
        }
        return Plan(SteamRole.NONE, launching(title), waitForSteamSignIn = false)
    }
}
